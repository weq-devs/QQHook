package dev.weq.qqhook.helper.parser

/**
 * 无 schema 的 JCE（Tars）解码。
 *
 * 编码规则严格对齐项目自带的 `moe/ore/tars/TarsInputStream`：
 *  - 头字节：**低 4 位 type、高 4 位 tag**；tag == 15 时再读 1 字节作为完整 tag。
 *  - 类型：0 BYTE / 1 SHORT / 2 INT / 3 LONG / 4 FLOAT / 5 DOUBLE /
 *          6 STRING1 / 7 STRING4 / 8 MAP / 9 LIST /
 *          10 STRUCT_BEGIN / 11 STRUCT_END / 12 ZERO_TAG / 13 SIMPLE_LIST。
 *  - 容器 size 用 `read(0, 0, true)` 编码：size 本身是一个 tag=0 的带头字段
 *    （ZERO_TAG / BYTE / SHORT / INT）。只有该读法不合理时才回退老版无头紧凑长度。
 *  - 数值为 Java 大端有符号语义，float/double 大端。
 *
 * 解析是严格的：任何截断/非法字节都抛 [WireError]，[tryDecode] 只在完整消费时返回结果。
 */
object JceDecoder {
    private const val BYTE = 0
    private const val SHORT = 1
    private const val INT = 2
    private const val LONG = 3
    private const val FLOAT = 4
    private const val DOUBLE = 5
    private const val STRING1 = 6
    private const val STRING4 = 7
    private const val MAP = 8
    private const val LIST = 9
    private const val STRUCT_BEGIN = 10
    private const val STRUCT_END = 11
    private const val ZERO_TAG = 12
    private const val SIMPLE_LIST = 13

    /** 单个容器元素数的防御性上限。 */
    private const val MAX_CONTAINER_SIZE = 1_000_000

    /** JCE 字符串长度上限（对齐 TarsBase.MAX_STRING_LENGTH）。 */
    private const val MAX_STRING_LENGTH = 100 * 1024 * 1024

    /** 解码顶层消息；失败抛 [WireError]。 */
    fun decode(data: ByteArray, offset: Int = 0, depth: Int = 0): RvDecoded =
        Reader(data, offset, depth).readTopLevel()

    /**
     * 尝试按 JCE 完整解析：无异常、完整消费、至少 1 个字段才返回结果。
     * 完整消费这一条能显著降低把 protobuf 误判成 JCE 的概率。
     */
    fun tryDecode(data: ByteArray, offset: Int = 0, depth: Int = 0): RvDecoded? {
        if (data.size - offset <= 0) return null
        return try {
            val r = decode(data, offset, depth)
            if (r.nodes.isEmpty() || r.consumed != data.size - offset) null else r
        } catch (_: WireError) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private class Head(val tag: Int, val type: Int)

    private class Reader(
        private val data: ByteArray,
        private val start: Int,
        private val depth: Int,
    ) {
        private var pos = start
        private val features = mutableSetOf<RvFeature>()

        private val remaining: Int get() = data.size - pos

        fun readTopLevel(): RvDecoded {
            val nodes = mutableListOf<RvNode>()

            while (pos < data.size) {
                val head = readHead()
                if (head.type == STRUCT_END) break

                // 顶层 STRUCT_BEGIN tag0 包裹：整体包成 {0: {...}}，与 QQHook 对齐。
                if (head.type == STRUCT_BEGIN && head.tag == 0 && nodes.isEmpty()) {
                    features += RvFeature.WRAPPED_IN_STRUCT
                    nodes += RvNode(0, RvValue.Obj(readStruct()))
                    continue
                }

                nodes += RvNode(head.tag, readValue(head.type))
            }

            return RvDecoded(nodes, pos - start, RvKind.JCE, features)
        }

        // ------------------------------------------------------------ 头与基础读取

        private fun readHead(): Head {
            if (pos >= data.size) throw WireError("JCE: 头字节截断")
            val b = data[pos].toInt() and 0xFF
            val type = b and 0x0F
            var tag = (b ushr 4) and 0x0F
            var size = 1
            if (tag == 0x0F) {
                if (pos + 1 >= data.size) throw WireError("JCE: 扩展 tag 截断")
                tag = data[pos + 1].toInt() and 0xFF
                size = 2
            }
            pos += size
            return Head(tag, type)
        }

        private fun readU8(): Int {
            if (pos >= data.size) throw WireError("JCE: 数据截断")
            return data[pos++].toInt() and 0xFF
        }

        private fun readU16(): Int {
            if (pos + 2 > data.size) throw WireError("JCE: 数据截断")
            val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        private fun readU32(): Long {
            if (pos + 4 > data.size) throw WireError("JCE: 数据截断")
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
            pos += 4
            return v
        }

        private fun readU64(): Long {
            if (pos + 8 > data.size) throw WireError("JCE: 数据截断")
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
            pos += 8
            return v
        }

        private fun readBytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw WireError("JCE: 数据截断（需要 $n 字节）")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        // ------------------------------------------------------------ 值

        private fun readValue(type: Int): RvValue = when (type) {
            BYTE -> RvValue.IntValue(readU8().toLong(), 8, signed = true)
            SHORT -> RvValue.IntValue(readU16().toLong(), 16, signed = true)
            INT -> RvValue.IntValue(readU32(), 32, signed = true)
            LONG -> RvValue.IntValue(readU64(), 64, signed = true)
            FLOAT -> {
                features += RvFeature.HAS_FLOAT
                RvValue.FloatValue(Float.fromBits(readU32().toInt()).toDouble())
            }

            DOUBLE -> {
                features += RvFeature.HAS_FLOAT
                RvValue.FloatValue(Double.fromBits(readU64()))
            }
            STRING1 -> readString(readU8())
            STRING4 -> {
                val len = readU32()
                if (len < 0 || len > MAX_STRING_LENGTH) throw WireError("JCE: 字符串过长 $len")
                readString(len.toInt())
            }

            MAP -> readMap()
            LIST -> readList()
            STRUCT_BEGIN -> RvValue.Obj(readStruct())
            STRUCT_END -> throw WireError("JCE: 意外的 STRUCT_END")
            ZERO_TAG -> {
                features += RvFeature.HAS_ZERO_TAG
                RvValue.IntValue(0, 0, signed = false)
            }

            SIMPLE_LIST -> readSimpleList()
            else -> throw WireError("JCE: 未知类型 $type")
        }

        private fun readString(len: Int): RvValue.Str {
            if (len < 0 || len > MAX_STRING_LENGTH) throw WireError("JCE: 字符串过长 $len")
            val bytes = readBytes(len)
            features += RvFeature.HAS_STR
            return RvValue.Str(decodeUtf8Loose(bytes), bytes)
        }

        /** 结构体：必须读到 STRUCT_END，否则视为截断。 */
        private fun readStruct(): List<RvNode> {
            val fields = mutableListOf<RvNode>()
            while (true) {
                if (pos >= data.size) throw WireError("JCE: 结构体缺少 STRUCT_END")
                val head = readHead()
                if (head.type == STRUCT_END) return fields
                fields += RvNode(head.tag, readValue(head.type))
            }
        }

        private fun readList(): RvValue {
            val n = readSize(minBytesPer = 1)
            guardSize(n, 1)
            val items = ArrayList<RvNode>(n)
            repeat(n) {
                val head = readHead()
                items += RvNode(head.tag, readValue(head.type))
            }
            features += RvFeature.HAS_LIST
            return RvValue.ListValue(items)
        }

        private fun readMap(): RvValue {
            val n = readSize(minBytesPer = 2)
            guardSize(n, 2)
            val entries = ArrayList<RvMapEntry>(n)
            repeat(n) {
                val kh = readHead()
                val key = readValue(kh.type)
                val vh = readHead()
                entries += RvMapEntry(key, RvNode(vh.tag, readValue(vh.type)))
            }
            features += RvFeature.HAS_MAP
            return RvValue.MapValue(entries)
        }

        private fun readSimpleList(): RvValue {
            val head = readHead()
            if (head.type != BYTE) throw WireError("JCE: SIMPLE_LIST 元素类型必须是 BYTE")
            val n = readSize(minBytesPer = 1)
            guardSize(n, 1)
            val bytes = readBytes(n)
            // SIMPLE_LIST 常常装的是「再一层 protobuf/JCE」（混合嵌套），自动下钻。
            val nested = nestedOf(bytes, depth)
            return RvValue.Bytes(bytes, nested?.nodes, nested?.kind)
        }

        // ------------------------------------------------------------ 容器 size

        /**
         * 容器 size：标准读法是一个 tag=0 的带头字段。
         *
         * 该读法非法或结果不合理时，回退老版无头紧凑长度。标准读法的固有歧义
         * （紧凑长度 `0x02` 会被当成 tag=0/type=INT 的头）靠 [plausible] 挡回去。
         */
        private fun readSize(minBytesPer: Int): Int {
            val saved = pos
            try {
                val head = readHead()
                if (head.tag != 0) throw WireError("JCE: size 字段 tag 非 0")
                val n = when (head.type) {
                    ZERO_TAG -> 0
                    BYTE -> readU8()
                    SHORT -> readU16()
                    INT -> {
                        val v = readU32()
                        if (v > 0x7FFFFFFFL) throw WireError("JCE: size 超出 int 范围")
                        v.toInt()
                    }

                    else -> throw WireError("JCE: 非法 size 类型 ${head.type}")
                }
                if (!plausible(n, minBytesPer)) throw WireError("JCE: size $n 不合理")
                return n
            } catch (_: WireError) {
                pos = saved
                return readSizeCompact()
            }
        }

        private fun plausible(n: Int, minBytesPer: Int): Boolean =
            n in 0..MAX_CONTAINER_SIZE && n.toLong() * minBytesPer <= remaining.toLong() + 2

        /** 老版无头紧凑长度：首字节 < 0x80 就是长度，否则两字节大端。 */
        private fun readSizeCompact(): Int {
            val b = readU8()
            if (b and 0x80 == 0) return b
            return ((b and 0x7F) shl 8) or readU8()
        }

        private fun guardSize(n: Int, minBytesPer: Int) {
            if (n < 0 || n > MAX_CONTAINER_SIZE || n.toLong() * minBytesPer > remaining.toLong() + 2) {
                throw WireError("JCE: 容器 size $n 不合理")
            }
        }
    }
}
