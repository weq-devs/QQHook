package dev.weq.qqhook.helper.parser

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

/**
 * protobuf / JCE 通用 AST。
 *
 * 两种 wire format 都产出同一套节点，UI 渲染和 JSON 输出各只写一次。
 * 类型不写进 JSON —— 展示形态是简洁的 `{tag: value}`，需要时由 UI 附加转换按钮。
 */
enum class RvKind { PROTOBUF, JCE }

/** 判定用的结构特征（protobuf / JCE 都能解时靠它区分）。 */
enum class RvFeature {
    /** 顶层被 STRUCT_BEGIN tag0 … STRUCT_END 包裹（JCE 强特征）。 */
    WRAPPED_IN_STRUCT,
    HAS_MAP,
    HAS_LIST,
    HAS_STR,
    HAS_FLOAT,
    HAS_ZERO_TAG,
    /** 出现了嵌套 message（protobuf 强特征）。 */
    HAS_NESTED,
}

data class RvNode(val tag: Int, val value: RvValue)

data class RvMapEntry(val key: RvValue, val value: RvNode)

sealed interface RvValue {
    /**
     * 整数。
     *
     * [raw] 是**无符号位模式**（按大端读出的原始位），显示时再解释：
     * - protobuf varint：`bits = 0, signed = false` → 无符号十进制
     * - JCE BYTE/SHORT/INT/LONG：`bits = 8/16/32/64, signed = true` → 二补码有符号
     */
    class IntValue(val raw: Long, val bits: Int, val signed: Boolean) : RvValue

    class FloatValue(val value: Double) : RvValue

    /** protobuf wire type 1/5 的原始定长块。 */
    class Fixed(val bytes: ByteArray, val bits: Int) : RvValue

    /** JCE STRING1 / STRING4（已按宽松 UTF-8 解码，原始字节保留用于回退）。 */
    class Str(val text: String, val bytes: ByteArray) : RvValue

    /**
     * 原始字节：protobuf LEN 字段、JCE SIMPLE_LIST。
     *
     * [nested] 非空表示已成功再解一层，渲染时默认展开；原始 [bytes] 始终保留。
     */
    class Bytes(
        val bytes: ByteArray,
        val nested: List<RvNode>? = null,
        val nestedKind: RvKind? = null,
    ) : RvValue

    /** protobuf 嵌套消息 / JCE STRUCT。 */
    class Obj(val fields: List<RvNode>) : RvValue

    /** JCE LIST：元素各自带 head。 */
    class ListValue(val items: List<RvNode>) : RvValue

    /** JCE MAP：键值对，键可为任意标量。 */
    class MapValue(val entries: List<RvMapEntry>) : RvValue
}

/** 一次解码的结果。 */
data class RvDecoded(
    val nodes: List<RvNode>,
    /** 实际消费的字节数（相对 offset）。 */
    val consumed: Int,
    val kind: RvKind,
    val features: Set<RvFeature> = emptySet(),
)

/** 嵌套解析的结果。 */
internal class Nested(val nodes: List<RvNode>, val kind: RvKind)

/** wire 层错误。两个解码器共用，`tryDecode` 靠它判定失败。 */
class WireError(message: String) : Exception(message)

/** 递归深度上限：QQ 真实消息最多嵌套几层，16 足够且能挡住畸形数据。 */
internal const val RV_MAX_DEPTH = 16

/** 一个 length-delimited 字段最多尝试嵌套解析多少字节。 */
internal const val RV_MAX_NESTED_BYTES = 512 * 1024

/**
 * 尝试把一段 length-delimited / SIMPLE_LIST 载荷再解一层。
 *
 * 判定顺序（对齐 WeQ `nestedBytes`）：
 *  1. 先试 protobuf：完整消费且至少 1 个字段；
 *  2. 若这段字节本身是合法可读文本、且只能解出 1 个字段，按文本处理
 *     （避免把 "hi" 误判成 `{13: 105}`）；
 *  3. 非文本、protobuf 解不动时再试 JCE（QQ 的 envelope 里两格式会互相嵌套）。
 *
 * @param depth 当前所在深度；本函数内部会用 `depth + 1` 递归
 */
internal fun nestedOf(payload: ByteArray, depth: Int): Nested? {
    if (depth + 1 >= RV_MAX_DEPTH) return null
    if (payload.size < 2 || payload.size > RV_MAX_NESTED_BYTES) return null

    val text = tryReadableText(payload)

    val proto = ProtoDecoder.tryDecode(payload, depth = depth + 1)
    if (proto != null && proto.nodes.isNotEmpty() && (text == null || proto.nodes.size >= 2)) {
        return Nested(proto.nodes, RvKind.PROTOBUF)
    }
    if (text != null) return null

    val jce = JceDecoder.tryDecode(payload, depth = depth + 1)
    if (jce != null && jce.nodes.isNotEmpty()) return Nested(jce.nodes, RvKind.JCE)
    return null
}

// ---------------------------------------------------------------- 文本判定

/** 严格 UTF-8 解码；非法序列返回 null。 */
fun tryUtf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    null
}

/**
 * 严格 UTF-8 且不含控制字符（`\t \n \r` 除外）才算「可读文本」。
 *
 * 比原来按可打印字符占比打分的启发式更硬：二进制里偶尔凑出的合法 UTF-8
 * 往往夹着控制字节，会被这里挡掉。
 */
fun tryReadableText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val s = tryUtf8(bytes) ?: return null
    for (c in s) {
        if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') return null
    }
    return s
}

/** 宽松 UTF-8 解码（非法字节替换为 U+FFFD），用于 JCE 字符串。 */
fun decodeUtf8Loose(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

// ---------------------------------------------------------------- 数值展示

/**
 * 默认展示值：JCE 定宽按有符号（Java 语义），protobuf varint 按无符号。
 *
 * `bits == 64` 时 [raw] 本身就是 64 位二补码，无需再扩展。
 */
fun RvValue.IntValue.displayLong(): Long = when {
    !signed -> raw
    bits == 64 -> raw
    bits in 1..63 -> (raw shl (64 - bits)) shr (64 - bits)
    else -> raw
}

fun RvValue.IntValue.displayString(): String =
    if (signed) displayLong().toString() else java.lang.Long.toUnsignedString(raw)

// ---------------------------------------------------------------- 字节工具

internal fun ByteArray.toHexLower(): String {
    if (isEmpty()) return ""
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}
