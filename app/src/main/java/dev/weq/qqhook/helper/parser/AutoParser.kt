package dev.weq.qqhook.helper.parser

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * 无 schema 的 protobuf 启发式解析。
 *
 * QQ 的 SSO 包绝大多数是 protobuf，但没有 .proto 定义。这里不追求 100% 正确，
 * 而是尽量把结构展开到人看得懂：
 *
 * - 按 varint key 切字段，识别 wire type（0 varint / 1 fixed64 / 2 length-delimited / 5 fixed32）
 * - length-delimited 的字段依次尝试：**递归当 protobuf 解** → 当 UTF-8 字符串 → 当 hex
 * - 嵌套深度受限，避免畸形数据导致爆栈
 * - 字段编号旁标注猜测的类型（string / varint / message / fixed 等）
 */
object AutoParser {
    private const val MAX_DEPTH = 12
    /** 一个 length-delimited 字段最多尝试解析多少字节，超过就不递归。 */
    private const val MAX_NESTED_BYTES = 512 * 1024

    sealed interface Value {
        data class Varint(val value: Long, val zigzag: Long) : Value
        data class Fixed(val hex: String, val asLong: Long?) : Value
        data class Text(val text: String) : Value
        data class Bytes(val hex: String, val size: Int) : Value
        data class Message(val fields: List<Field>) : Value
    }

    data class Field(
        val number: Int,
        val wireType: Int,
        val typeName: String,
        val value: Value,
        /** 该字段在原始字节里的偏移与长度，便于 hex 对照。 */
        val offset: Int,
        val length: Int,
    )

    data class Result(
        val fields: List<Field>,
        val consumed: Int,
        val valid: Boolean,
    )

    /**
     * 解析一段 protobuf 字节。
     *
     * @param data 原始字节
     * @param offset 起点（跳过 4 字节长度前缀时传 4）
     */
    fun parse(data: ByteArray, offset: Int = 0): Result {
        val fields = mutableListOf<Field>()
        var pos = offset
        var ok = true

        while (pos < data.size) {
            val fieldStart = pos
            val key = readVarint(data, pos) ?: run { ok = false; break }
            pos = key.second
            val keyValue = key.first
            if (keyValue == 0L) { ok = false; break }

            val wireType = (keyValue and 0x7).toInt()
            val fieldNumber = (keyValue ushr 3).toInt()
            if (fieldNumber <= 0) { ok = false; break }

            when (wireType) {
                0 -> {
                    val v = readVarint(data, pos) ?: run { ok = false; break }
                    pos = v.second
                    fields += Field(
                        fieldNumber, wireType, "varint",
                        Value.Varint(v.first, zigzagDecode(v.first)),
                        fieldStart, pos - fieldStart,
                    )
                }

                1 -> {
                    if (pos + 8 > data.size) { ok = false; break }
                    val slice = data.copyOfRange(pos, pos + 8)
                    pos += 8
                    fields += Field(
                        fieldNumber, wireType, "fixed64",
                        Value.Fixed(slice.toHexString(), slice.readLongLe()),
                        fieldStart, pos - fieldStart,
                    )
                }

                2 -> {
                    val len = readVarint(data, pos) ?: run { ok = false; break }
                    pos = len.second
                    val size = len.first
                    if (size < 0 || size > Int.MAX_VALUE || pos + size > data.size) { ok = false; break }
                    val body = data.copyOfRange(pos, pos + size.toInt())
                    pos += size.toInt()
                    val value = interpretDelimited(body, fieldNumber, 0)
                    fields += Field(
                        fieldNumber, wireType, value.typeName(),
                        value, fieldStart, pos - fieldStart,
                    )
                }

                5 -> {
                    if (pos + 4 > data.size) { ok = false; break }
                    val slice = data.copyOfRange(pos, pos + 4)
                    pos += 4
                    fields += Field(
                        fieldNumber, wireType, "fixed32",
                        Value.Fixed(slice.toHexString(), (slice.readIntLe().toLong() and 0xFFFFFFFFL)),
                        fieldStart, pos - fieldStart,
                    )
                }

                3 -> {
                    // 已废弃的 group：QQ 的部分包仍在用。
                    // 读到匹配的 end-group（wire type 4）为止，内部字段照常展开。
                    val inner = mutableListOf<Field>()
                    val end = readGroup(data, pos, fieldNumber, inner, 0)
                    if (end == null) { ok = false; break }
                    pos = end
                    fields += Field(
                        fieldNumber, 3, "group",
                        Value.Message(inner), fieldStart, pos - fieldStart,
                    )
                }

                else -> {
                    // 4 单独出现（没有对应 start）或 6/7 都是非法
                    ok = false
                    break
                }
            }
        }

        return Result(fields, pos - offset, ok && fields.isNotEmpty())
    }

    /**
     * 判断 length-delimited 的字节体是什么，并给出展示值。
     *
     * 判定顺序很关键：先试嵌套 protobuf（能完整消费且字段合法），
     * 再试 UTF-8，最后退回 hex。
     */
    private fun interpretDelimited(body: ByteArray, fieldNumber: Int, depth: Int): Value {
        if (body.isEmpty()) return Value.Text("")

        // 先判断这段字节本身是不是合法文本：合法文本优先当字符串，
        // 否则 "abc" 这种会被误拆成 protobuf 字段。
        val text = tryDecodeUtf8(body)
        val isText = text != null && isMostlyPrintable(text)

        // 1. 嵌套 message（文本优先，避免把普通字符串拆成 message）
        if (!isText && depth < MAX_DEPTH && body.size <= MAX_NESTED_BYTES && body.size >= 2) {
            val nested = parse(body, 0)
            if (nested.valid && nested.consumed == body.size && looksLikeMessage(nested)) {
                return Value.Message(nested.fields)
            }
        }

        // 2. 文本
        if (isText) return Value.Text(text)

        // 3. 退回 hex
        return Value.Bytes(body.toHexString(), body.size)
    }

    /**
     * 嵌套结果看起来像 message 吗？
     *
     * 到这里时已经排除了「整段是合法文本」的情况，所以条件可以放宽：
     * 只要有字段、字段编号合理即可。单 varint 字段也接受（很常见，
     * 比如 `08 01`）。
     */
    private fun looksLikeMessage(r: Result): Boolean {
        if (r.fields.isEmpty()) return false
        // 字段编号通常不大；如果出现离谱的编号，多半是误判
        if (r.fields.any { it.number > 100_000 }) return false
        return true
    }

    private fun Value.typeName(): String = when (this) {
        is Value.Message -> "message"
        is Value.Text -> "string"
        is Value.Bytes -> "bytes"
        is Value.Varint -> "varint"
        is Value.Fixed -> "fixed"
    }

    // ---------------------------------------------------------------- 基础读取

    /**
     * 读取一个 group 的内容，直到遇到同编号的 end-group。
     *
     * @return 结束位置（end-group 之后）；解析失败返回 null
     */
    private fun readGroup(
        data: ByteArray,
        start: Int,
        groupNumber: Int,
        out: MutableList<Field>,
        depth: Int,
    ): Int? {
        if (depth > MAX_DEPTH) return null
        var pos = start
        while (pos < data.size) {
            val fieldStart = pos
            val key = readVarint(data, pos) ?: return null
            pos = key.second
            val wireType = (key.first and 0x7).toInt()
            val number = (key.first ushr 3).toInt()

            if (wireType == 4) {
                // end-group：编号必须匹配
                return if (number == groupNumber) pos else null
            }

            when (wireType) {
                0 -> {
                    val v = readVarint(data, pos) ?: return null
                    pos = v.second
                    out += Field(number, 0, "varint", Value.Varint(v.first, zigzagDecode(v.first)),
                        fieldStart, pos - fieldStart)
                }
                1 -> {
                    if (pos + 8 > data.size) return null
                    val slice = data.copyOfRange(pos, pos + 8)
                    pos += 8
                    out += Field(number, 1, "fixed64",
                        Value.Fixed(slice.toHexString(), slice.readLongLe()), fieldStart, pos - fieldStart)
                }
                2 -> {
                    val len = readVarint(data, pos) ?: return null
                    pos = len.second
                    if (len.first < 0 || len.first > Int.MAX_VALUE || pos + len.first > data.size) return null
                    val body = data.copyOfRange(pos, pos + len.first.toInt())
                    pos += len.first.toInt()
                    val value = interpretDelimited(body, number, depth + 1)
                    out += Field(number, 2, value.typeName(), value, fieldStart, pos - fieldStart)
                }
                3 -> {
                    val inner = mutableListOf<Field>()
                    val end = readGroup(data, pos, number, inner, depth + 1) ?: return null
                    pos = end
                    out += Field(number, 3, "group", Value.Message(inner), fieldStart, pos - fieldStart)
                }
                5 -> {
                    if (pos + 4 > data.size) return null
                    val slice = data.copyOfRange(pos, pos + 4)
                    pos += 4
                    out += Field(number, 5, "fixed32",
                        Value.Fixed(slice.toHexString(), (slice.readIntLe().toLong() and 0xFFFFFFFFL)),
                        fieldStart, pos - fieldStart)
                }
                else -> return null
            }
        }
        // 没等到 end-group
        return null
    }

    /** 读 varint，返回 (值, 新位置)；越界或超过 10 字节返回 null。 */
    fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    private fun zigzagDecode(v: Long): Long = (v ushr 1) xor -(v and 1)

    private fun ByteArray.readLongLe(): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (this[i].toLong() and 0xFF)
        return r
    }

    private fun ByteArray.readIntLe(): Int {
        var r = 0
        for (i in 3 downTo 0) r = (r shl 8) or (this[i].toInt() and 0xFF)
        return r
    }

    // ---------------------------------------------------------------- 文本判定

    fun tryDecodeUtf8(bytes: ByteArray): String? = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** 可打印字符占比够高才算「真的是文本」，避免二进制被硬解成乱码。 */
    private fun isMostlyPrintable(s: String): Boolean {
        if (s.isEmpty()) return false
        var printable = 0
        for (c in s) {
            if (c.isLetterOrDigit() || c in " \t\r\n.,:;!?-_/@#%&*()[]{}<>+=|\\\"'`~^\$" ||
                c.code in 0x4E00..0x9FFF || // CJK
                c.code in 0x3000..0x303F    // CJK 标点
            ) printable++
        }
        return printable.toDouble() / s.length >= 0.85
    }
}

internal fun ByteArray.toHexString(): String {
    if (isEmpty()) return ""
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}
