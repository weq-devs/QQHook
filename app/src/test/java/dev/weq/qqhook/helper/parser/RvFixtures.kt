package dev.weq.qqhook.helper.parser

/** 测试用的字节构造工具。 */
object RvFixtures {

    /** 把 "0a 16 02" 这类 hex 字符串转成字节。 */
    fun hex(s: String): ByteArray =
        s.replace(Regex("[\\s:]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * JCE 字段头：**低 4 位 type、高 4 位 tag**。
     * tag >= 15 时高半字节为 0xF，完整 tag 紧跟其后。
     */
    fun head(type: Int, tag: Int): ByteArray =
        if (tag < 15) {
            byteArrayOf((((tag shl 4) or type) and 0xFF).toByte())
        } else {
            byteArrayOf((((0xF shl 4) or type) and 0xFF).toByte(), tag.toByte())
        }

    /** 标准容器 size：一个 tag=0 的带头字段。 */
    fun size(n: Int): ByteArray = when {
        n == 0 -> head(12, 0) // ZERO_TAG
        n <= 0xFF -> head(0, 0) + byteArrayOf(n.toByte()) // BYTE
        else -> head(1, 0) + byteArrayOf((n ushr 8).toByte(), n.toByte()) // SHORT
    }

    fun int(tag: Int, v: Int): ByteArray = head(2, tag) + byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    fun str1(tag: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return head(6, tag) + byteArrayOf(b.size.toByte()) + b
    }
}
