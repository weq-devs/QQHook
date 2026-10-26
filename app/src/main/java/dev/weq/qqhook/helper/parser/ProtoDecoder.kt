package dev.weq.qqhook.helper.parser

/**
 * 无 schema 的 protobuf 解码。
 *
 * QQ 的 SSO 包绝大多数是 protobuf，但没有 .proto 定义。这里按 wire format
 * 自描述的特性展开：按 varint key 切字段，识别 wire type
 * （0 varint / 1 fixed64 / 2 length-delimited / 3 start-group / 5 fixed32）。
 *
 * length-delimited 字段的判定见 [nestedOf]：能完整再解一层就是嵌套消息，
 * 否则按「可读文本 > hex」展示。
 *
 * 解析是严格的：任何截断/非法 wire type 都抛 [WireError]，因此「成功即完整消费」。
 */
object ProtoDecoder {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_START_GROUP = 3
    private const val WIRE_END_GROUP = 4
    private const val WIRE_FIXED32 = 5

    /** 解码顶层消息；失败抛 [WireError]。 */
    fun decode(data: ByteArray, offset: Int = 0, depth: Int = 0): RvDecoded {
        val reader = Reader(data, offset, depth)
        val nodes = reader.readMessage(endGroupTag = null)
        return RvDecoded(nodes, reader.pos - offset, RvKind.PROTOBUF, reader.features)
    }

    /** 尝试完整解析：无异常、完整消费、至少 1 个字段才返回结果。 */
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

    private class Reader(
        private val data: ByteArray,
        private val start: Int,
        private val depth: Int,
    ) {
        var pos = start
            private set
        val features = mutableSetOf<RvFeature>()

        /**
         * 读一个消息体。
         *
         * @param endGroupTag 非空时表示正在读 group 内部，遇到同编号的 end-group 返回；
         *                    为 null 时读到缓冲区结束。
         */
        fun readMessage(endGroupTag: Int?): List<RvNode> {
            if (depth > RV_MAX_DEPTH) throw WireError("protobuf: 嵌套过深")
            val fields = mutableListOf<RvNode>()

            while (pos < data.size) {
                val key = readVarint() ?: throw WireError("protobuf: key 截断")
                if (key == 0L) throw WireError("protobuf: key 为 0")

                val wireType = (key and 0x7).toInt()
                val number = (key ushr 3).toInt()
                if (number <= 0) throw WireError("protobuf: 字段编号非法 $number")

                if (wireType == WIRE_END_GROUP) {
                    return if (endGroupTag != null && number == endGroupTag) fields
                    else throw WireError("protobuf: 意外的 end-group")
                }

                fields += RvNode(number, readValue(number, wireType))
            }

            // 顶层读到结尾是正常的；group 内部没等到 end-group 才是截断。
            if (endGroupTag != null) throw WireError("protobuf: group 缺少 end-group")
            return fields
        }

        private fun readValue(number: Int, wireType: Int): RvValue = when (wireType) {
            WIRE_VARINT -> RvValue.IntValue(readVarint() ?: throw WireError("varint 截断"), 0, signed = false)

            WIRE_FIXED64 -> {
                if (pos + 8 > data.size) throw WireError("fixed64 截断")
                val slice = data.copyOfRange(pos, pos + 8)
                pos += 8
                RvValue.Fixed(slice, 64)
            }

            WIRE_FIXED32 -> {
                if (pos + 4 > data.size) throw WireError("fixed32 截断")
                val slice = data.copyOfRange(pos, pos + 4)
                pos += 4
                RvValue.Fixed(slice, 32)
            }

            WIRE_LEN -> {
                val len = readVarint() ?: throw WireError("LEN 长度截断")
                if (len < 0 || len > Int.MAX_VALUE) throw WireError("LEN 长度非法 $len")
                if (pos + len > data.size) throw WireError("LEN 载荷截断")
                val body = data.copyOfRange(pos, pos + len.toInt())
                pos += len.toInt()
                val nested = nestedOf(body, depth)
                if (nested != null) features += RvFeature.HAS_NESTED
                RvValue.Bytes(body, nested?.nodes, nested?.kind)
            }

            // 已废弃的 group：QQ 的部分包仍在用。内部字段照常展开。
            WIRE_START_GROUP -> RvValue.Obj(readMessage(endGroupTag = number))

            else -> throw WireError("protobuf: 未知 wire type $wireType")
        }

        /** 读 varint，返回无符号位模式；越界或超过 10 字节返回 null。 */
        private fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos].toInt() and 0xFF
                pos++
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) return null
            }
            return null
        }
    }
}
