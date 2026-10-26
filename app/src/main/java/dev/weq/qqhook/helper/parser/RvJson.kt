package dev.weq.qqhook.helper.parser

/**
 * `{tag: value}` 的 JSON 输出。
 *
 * 刻意不依赖 `org.json`：
 *  - `app/src/test` 是 JVM 单测，Android SDK 的 `org.json` 在那里是抛异常的 stub；
 *  - JVM 版 `JSONObject` 用 `HashMap`，不保证 tag 顺序。
 *
 * 这里用自己的一层中间值 + 手写编码器，顺便控制大整数与浮点的字面量格式。
 */
sealed interface RvJsonValue {
    /** 已格式化好的数字字面量（大整数会退化成 [Str]）。 */
    class Num(val literal: String) : RvJsonValue

    class Str(val text: String) : RvJsonValue

    class Bool(val value: Boolean) : RvJsonValue

    class Arr(val items: List<RvJsonValue>) : RvJsonValue

    class Obj(val entries: List<Pair<String, RvJsonValue>>) : RvJsonValue
}

object RvJson {
    /** JSON 安全整数上限：超过就用字符串，避免下游解析丢精度。 */
    private const val MAX_SAFE = 9007199254740991L

    /** 数组内联显示的长度上限（估算）。 */
    private const val INLINE_MAX = 60

    data class Group(val tag: Int, val values: List<RvValue>)

    /**
     * 按 tag 分组，保持首次出现的顺序。
     * 同 tag 多次出现 → 后续转成数组（protobuf repeated / JCE 重复字段）。
     */
    fun group(nodes: List<RvNode>): List<Group> {
        val map = LinkedHashMap<Int, MutableList<RvValue>>()
        for (n in nodes) map.getOrPut(n.tag) { mutableListOf() }.add(n.value)
        return map.map { (tag, values) -> Group(tag, values) }
    }

    /** 字段列表 → JSON 对象；单值直接给，多值给数组。 */
    fun nodesToJson(nodes: List<RvNode>): RvJsonValue.Obj {
        val entries = group(nodes).map { g ->
            val value = if (g.values.size == 1) {
                valueToJson(g.values[0])
            } else {
                RvJsonValue.Arr(g.values.map(::valueToJson))
            }
            g.tag.toString() to value
        }
        return RvJsonValue.Obj(entries)
    }

    fun valueToJson(v: RvValue): RvJsonValue = when (v) {
        is RvValue.IntValue -> intToJson(v)
        is RvValue.FloatValue -> floatToJson(v.value)
        is RvValue.Fixed -> fixedToJson(v)
        is RvValue.Str -> strToJson(v)
        is RvValue.Bytes -> when {
            v.nested != null -> nodesToJson(v.nested)
            v.bytes.isEmpty() -> RvJsonValue.Str("")
            else -> tryReadableText(v.bytes)?.let { RvJsonValue.Str(it) }
                ?: RvJsonValue.Str("0x" + v.bytes.toHexLower())
        }

        is RvValue.Obj -> nodesToJson(v.fields)
        is RvValue.ListValue -> RvJsonValue.Arr(v.items.map { valueToJson(it.value) })
        is RvValue.MapValue -> mapToJson(v)
    }

    /** 编码成 JSON 文本。[pretty] = true 时缩进 2 空格。 */
    fun encode(value: RvJsonValue, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    // ------------------------------------------------------------ 标量

    private fun intToJson(v: RvValue.IntValue): RvJsonValue {
        if (!v.signed) {
            // 无符号：raw < 0 表示超过 Long.MAX_VALUE，必然超 2^53。
            return if (v.raw >= 0 && v.raw <= MAX_SAFE) RvJsonValue.Num(v.raw.toString())
            else RvJsonValue.Str(java.lang.Long.toUnsignedString(v.raw))
        }
        val d = v.displayLong()
        return if (d in -MAX_SAFE..MAX_SAFE) RvJsonValue.Num(d.toString())
        else RvJsonValue.Str(d.toString())
    }

    private fun floatToJson(d: Double): RvJsonValue =
        if (d.isFinite()) RvJsonValue.Num(formatDouble(d)) else RvJsonValue.Str(d.toString())

    /** 整数值的 double 不带 `.0`，其余走标准 `toString`。 */
    private fun formatDouble(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** protobuf wire 1/5：按小端无符号整数展示（与 WeQ 一致）。 */
    private fun fixedToJson(v: RvValue.Fixed): RvJsonValue {
        val u: Long = if (v.bits == 64) {
            var r = 0L
            for (i in 7 downTo 0) r = (r shl 8) or (v.bytes[i].toLong() and 0xFF)
            r
        } else {
            var r = 0L
            for (i in 3 downTo 0) r = (r shl 8) or (v.bytes[i].toLong() and 0xFF)
            r and 0xFFFFFFFFL
        }
        return if (u >= 0 && u <= MAX_SAFE) RvJsonValue.Num(u.toString())
        else RvJsonValue.Str(java.lang.Long.toUnsignedString(u))
    }

    /**
     * JCE 字符串：正常输出文本（控制字符交给编码器转义）。
     *
     * 唯一的例外是文本里出现 U+FFFD —— 说明原始字节不是合法 UTF-8，宽松解码
     * 已经丢了信息，这时退回 hex。
     */
    private fun strToJson(v: RvValue.Str): RvJsonValue =
        if ('�' in v.text) RvJsonValue.Str("0x" + v.bytes.toHexLower())
        else RvJsonValue.Str(v.text)

    private fun mapToJson(v: RvValue.MapValue): RvJsonValue.Obj {
        val map = LinkedHashMap<String, RvJsonValue>()
        for (e in v.entries) map[keyToString(e.key)] = valueToJson(e.value.value)
        return RvJsonValue.Obj(map.map { (k, value) -> k to value })
    }

    fun keyToString(key: RvValue): String = when (key) {
        is RvValue.IntValue -> key.displayString()
        is RvValue.Str -> key.text
        is RvValue.FloatValue -> formatDouble(key.value)
        is RvValue.Bytes -> "hex:" + key.bytes.toHexLower()
        else -> "…"
    }

    // ------------------------------------------------------------ 编码

    private fun write(sb: StringBuilder, v: RvJsonValue, pretty: Boolean, depth: Int) {
        when (v) {
            is RvJsonValue.Num -> sb.append(v.literal)
            is RvJsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            is RvJsonValue.Str -> writeString(sb, v.text)
            is RvJsonValue.Arr -> writeArray(sb, v.items, pretty, depth)
            is RvJsonValue.Obj -> writeObject(sb, v.entries, pretty, depth)
        }
    }

    private fun writeArray(sb: StringBuilder, items: List<RvJsonValue>, pretty: Boolean, depth: Int) {
        if (items.isEmpty()) {
            sb.append("[]")
            return
        }
        // 全标量且短 → 内联成 [1, 2, 3]，贴近 WeQ 的 allInline。
        val inline = items.all { it is RvJsonValue.Num || it is RvJsonValue.Str || it is RvJsonValue.Bool } &&
            items.sumOf { estimateLength(it) } + items.size * 2 <= INLINE_MAX
        if (inline) {
            sb.append('[')
            items.forEachIndexed { i, item ->
                if (i > 0) sb.append(if (pretty) ", " else ",")
                write(sb, item, pretty = false, depth = depth)
            }
            sb.append(']')
            return
        }
        sb.append('[')
        items.forEachIndexed { i, item ->
            if (pretty) newline(sb, depth + 1)
            write(sb, item, pretty, depth + 1)
            if (i < items.lastIndex) sb.append(',')
        }
        if (pretty) newline(sb, depth)
        sb.append(']')
    }

    private fun writeObject(
        sb: StringBuilder,
        entries: List<Pair<String, RvJsonValue>>,
        pretty: Boolean,
        depth: Int,
    ) {
        if (entries.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        entries.forEachIndexed { i, (key, value) ->
            if (pretty) newline(sb, depth + 1)
            writeString(sb, key)
            sb.append(':')
            if (pretty) sb.append(' ')
            write(sb, value, pretty, depth + 1)
            if (i < entries.lastIndex) sb.append(',')
        }
        if (pretty) newline(sb, depth)
        sb.append('}')
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun estimateLength(v: RvJsonValue): Int = when (v) {
        is RvJsonValue.Num -> v.literal.length
        is RvJsonValue.Bool -> 5
        is RvJsonValue.Str -> v.text.length + 2
        is RvJsonValue.Arr -> 40
        is RvJsonValue.Obj -> 40
    }

    /** JSON 字符串转义：控制字符、引号、反斜杠、孤立代理项。 */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(hex4(c.code))
                // 孤立代理项：直接输出会让结果不是合法 UTF-8
                Character.isHighSurrogate(c) && (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) -> {
                    sb.append("\\u").append(hex4(c.code))
                }

                Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s[i - 1])) -> {
                    sb.append("\\u").append(hex4(c.code))
                }

                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun hex4(code: Int): String =
        code.toString(16).padStart(4, '0')
}
