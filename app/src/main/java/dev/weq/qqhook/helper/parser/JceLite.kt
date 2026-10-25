package dev.weq.qqhook.helper.parser

import java.nio.charset.StandardCharsets

/**
 * 无 schema 的 JCE（Tars）结构展开。
 *
 * JCE 的 wire format 是 `tag(1 字节) + type(1 字节) + value`，type 的低 4 位是类型：
 * 0=byte 1=short 2=int 3=long 4=float 5=double 6=string 7=map 8=list 9=struct 13=simpleList。
 * 容器类型的长度可能占 1 或 2 字节（首字节高位为 1 表示两字节）。
 *
 * 和 [AutoParser] 一样不追求 schema 精确，只求把人能看懂的结构摊开。
 */
object JceLite {
    private const val MAX_DEPTH = 10
    private const val MAX_ELEMENTS = 10_000

    sealed interface Value {
        data class Num(val value: Long, val typeName: String) : Value
        data class Real(val value: Double, val typeName: String) : Value
        data class Text(val text: String) : Value
        data class Bytes(val hex: String, val size: Int) : Value
        data class Struct(val fields: List<Field>) : Value
        data class ListValue(val items: List<Value>, val elementType: String) : Value
        data class MapValue(val entries: List<Pair<Value, Value>>) : Value
    }

    data class Field(
        val tag: Int,
        val typeName: String,
        val value: Value,
        val offset: Int,
        val length: Int,
    )

    data class Result(
        val fields: List<Field>,
        val consumed: Int,
        val valid: Boolean,
    )

    fun parse(data: ByteArray, offset: Int = 0): Result {
        val fields = mutableListOf<Field>()
        var pos = offset
        var ok = true
        var guard = 0

        while (pos < data.size) {
            if (++guard > MAX_ELEMENTS) { ok = false; break }
            val start = pos
            val head = readHead(data, pos) ?: run { ok = false; break }
            pos = head.third
            val parsed = readValue(data, pos, head.second, head.first, 0)
            if (parsed == null) { ok = false; break }
            pos = parsed.second
            fields += Field(head.first, parsed.first.typeName(), parsed.first, start, pos - start)
        }

        return Result(fields, pos - offset, ok && fields.isNotEmpty())
    }

    /**
     * 读一个字段头。
     *
     * Tars/JCE 把 type 和 tag 合并在一个字节里：`(type << 4) | tag`。
     * 当 tag >= 15 时低 4 位为 0x0F，真正的 tag 紧跟其后。
     *
     * @return (tag, type) 与新的位置
     */
    private fun readHead(data: ByteArray, start: Int): Triple<Int, Int, Int>? {
        if (start >= data.size) return null
        val b = data[start].toInt() and 0xFF
        val type = (b ushr 4) and 0x0F
        val lowTag = b and 0x0F
        return if (lowTag == 0x0F) {
            if (start + 1 >= data.size) return null
            Triple(data[start + 1].toInt() and 0xFF, type, start + 2)
        } else {
            Triple(lowTag, type, start + 1)
        }
    }

    private fun Value.typeName(): String = when (this) {
        is Value.Num -> typeName
        is Value.Real -> typeName
        is Value.Text -> "string"
        is Value.Bytes -> "bytes"
        is Value.Struct -> "struct"
        is Value.ListValue -> "list<$elementType>"
        is Value.MapValue -> "map"
    }

    /** 读一个值，返回 (值, 新位置)。 */
    private fun readValue(
        data: ByteArray,
        start: Int,
        rawType: Int,
        tag: Int,
        depth: Int,
    ): Pair<Value, Int>? {
        val type = rawType
        var pos = start

        return when (type) {
            0 -> { // byte
                if (pos + 1 > data.size) return null
                val v = data[pos].toLong() and 0xFF
                Value.Num(v, "byte") to (pos + 1)
            }

            1 -> { // short
                if (pos + 2 > data.size) return null
                val v = readShortBe(data, pos).toLong()
                Value.Num(v, "short") to (pos + 2)
            }

            2 -> { // int
                if (pos + 4 > data.size) return null
                val v = readIntBe(data, pos).toLong()
                Value.Num(v, "int") to (pos + 4)
            }

            3 -> { // long
                if (pos + 8 > data.size) return null
                val v = readLongBe(data, pos)
                Value.Num(v, "long") to (pos + 8)
            }

            4 -> { // float
                if (pos + 4 > data.size) return null
                val bits = readIntBe(data, pos)
                Value.Real(Float.fromBits(bits).toDouble(), "float") to (pos + 4)
            }

            5 -> { // double
                if (pos + 8 > data.size) return null
                val bits = readLongBe(data, pos)
                Value.Real(Double.fromBits(bits), "double") to (pos + 8)
            }

            6 -> { // string
                val len = readLength(data, pos) ?: return null
                pos = len.second
                val size = len.first
                if (size < 0 || pos + size > data.size) return null
                val body = data.copyOfRange(pos, pos + size)
                pos += size
                val text = String(body, StandardCharsets.UTF_8)
                Value.Text(text) to pos
            }

            7 -> { // map
                val size = readLength(data, pos) ?: return null
                pos = size.second
                if (size.first < 0 || size.first > MAX_ELEMENTS) return null
                val entries = mutableListOf<Pair<Value, Value>>()
                repeat(size.first) {
                    val k = readTypedValue(data, pos, tag, depth + 1) ?: return null
                    pos = k.second
                    val v = readTypedValue(data, pos, tag, depth + 1) ?: return null
                    pos = v.second
                    entries += k.first to v.first
                }
                Value.MapValue(entries) to pos
            }

            8 -> { // list
                val size = readLength(data, pos) ?: return null
                pos = size.second
                if (size.first < 0 || size.first > MAX_ELEMENTS) return null
                val items = mutableListOf<Value>()
                var elemType = "?"
                repeat(size.first) {
                    val v = readTypedValue(data, pos, tag, depth + 1) ?: return null
                    pos = v.second
                    elemType = v.first.typeName()
                    items += v.first
                }
                Value.ListValue(items, elemType) to pos
            }

            9 -> { // struct
                if (depth >= MAX_DEPTH) return null
                val nested = parse(data, pos)
                if (!nested.valid) return null
                Value.Struct(nested.fields) to (pos + nested.consumed)
            }

            13 -> { // simpleList：1 字节元素类型 + 长度 + 数据
                if (pos >= data.size) return null
                val elemType = data[pos].toInt() and 0xFF
                pos++
                val size = readLength(data, pos) ?: return null
                pos = size.second
                if (size.first < 0 || size.first > MAX_ELEMENTS) return null
                val items = mutableListOf<Value>()
                repeat(size.first) {
                    val v = readValue(data, pos, elemType, tag, depth + 1) ?: return null
                    pos = v.second
                    items += v.first
                }
                Value.ListValue(items, "type$elemType") to pos
            }

            else -> null
        }
    }

    /** 容器内部读元素：先读 tag+type 头，再读值。 */
    private fun readTypedValue(
        data: ByteArray,
        start: Int,
        expectTag: Int,
        depth: Int,
    ): Pair<Value, Int>? {
        val head = readHead(data, start) ?: return null
        return readValue(data, head.third, head.second, head.first, depth)
    }

    /**
     * JCE 的长度编码：首字节 < 0x80 就是长度本身；
     * 否则低 7 位是**长度字节数**（1~4），后面跟对应字节数的大端长度。
     */
    private fun readLength(data: ByteArray, start: Int): Pair<Int, Int>? {
        if (start >= data.size) return null
        val b = data[start].toInt() and 0xFF
        if (b < 0x80) return b to (start + 1)

        val n = b and 0x7F
        if (n !in 1..4 || start + 1 + n > data.size) return null
        var v = 0
        for (i in 0 until n) {
            v = (v shl 8) or (data[start + 1 + i].toInt() and 0xFF)
        }
        return v to (start + 1 + n)
    }

    private fun readShortBe(d: ByteArray, p: Int): Short =
        (((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)).toShort()

    private fun readIntBe(d: ByteArray, p: Int): Int =
        ((d[p].toInt() and 0xFF) shl 24) or
            ((d[p + 1].toInt() and 0xFF) shl 16) or
            ((d[p + 2].toInt() and 0xFF) shl 8) or
            (d[p + 3].toInt() and 0xFF)

    private fun readLongBe(d: ByteArray, p: Int): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (d[p + i].toLong() and 0xFF)
        return r
    }
}
