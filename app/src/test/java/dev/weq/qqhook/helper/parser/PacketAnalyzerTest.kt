package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketAnalyzerTest {

    @Test
    fun `空数据识别为 EMPTY`() {
        val a = PacketAnalyzer.analyze(ByteArray(0))
        assertEquals(PacketAnalyzer.Kind.EMPTY, a.kind)
        assertEquals(1.0, a.confidence, 0.001)
    }

    @Test
    fun `纯文本识别为 TEXT`() {
        val a = PacketAnalyzer.analyze("hello world this is plain text".toByteArray())
        assertEquals(PacketAnalyzer.Kind.TEXT, a.kind)
        assertEquals("hello world this is plain text", a.text)
    }

    @Test
    fun `JSON 文本识别为 TEXT`() {
        val json = """{"code":0,"msg":"suc"}""".toByteArray()
        val a = PacketAnalyzer.analyze(json)
        assertEquals(PacketAnalyzer.Kind.TEXT, a.kind)
        assertEquals("""{"code":0,"msg":"suc"}""", a.text)
    }

    @Test
    fun `长度前缀按整包长度识别`() {
        // QQ 帧头长度是整包长度（含 4 字节头本身）
        val body = byteArrayOf(0x08, 0x96.toByte(), 0x01)
        val total = body.size + 4
        val withPrefix = byteArrayOf(
            (total ushr 24).toByte(), (total ushr 16).toByte(),
            (total ushr 8).toByte(), total.toByte(),
        ) + body
        val a = PacketAnalyzer.analyze(withPrefix)
        assertEquals(4, a.prefixLen)
        assertEquals(body.size, a.bodySize)
        assertEquals(PacketAnalyzer.Kind.PROTOBUF, a.kind)
    }

    @Test
    fun `长度前缀按剩余长度也能识别`() {
        val body = byteArrayOf(0x08, 0x96.toByte(), 0x01)
        val withPrefix = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        assertEquals(4, PacketAnalyzer.analyze(withPrefix).prefixLen)
    }

    @Test
    fun `无长度前缀时 prefixLen 为 0`() {
        val body = byteArrayOf(0x08, 0x96.toByte(), 0x01)
        val a = PacketAnalyzer.analyze(body)
        assertEquals(0, a.prefixLen)
    }

    @Test
    fun `典型 protobuf 包识别为 PROTOBUF`() {
        // 两个字段：varint + string
        val data = byteArrayOf(0x08, 0x96.toByte(), 0x01) +
            byteArrayOf(0x12, 0x03, 0x61, 0x62, 0x63)
        val a = PacketAnalyzer.analyze(data)
        assertEquals(PacketAnalyzer.Kind.PROTOBUF, a.kind)
        assertTrue(a.confidence > 0.5)
    }

    @Test
    fun `置信度在 0 到 1 之间`() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0x08, 0x01),
            "text".toByteArray(),
            ByteArray(32) { it.toByte() },
        )
        for (s in samples) {
            val c = PacketAnalyzer.analyze(s).confidence
            assertTrue("置信度越界: $c", c in 0.0..1.0)
        }
    }
}
