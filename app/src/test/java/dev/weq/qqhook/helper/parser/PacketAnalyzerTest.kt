package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketAnalyzerTest {

    @Test
    fun `空数据识别为 EMPTY`() {
        val a = PacketAnalyzer.analyze(ByteArray(0))
        assertEquals(PacketAnalyzer.Kind.EMPTY, a.kind)
        assertEquals(1.0, a.confidence, 0.001)
        assertNull(a.nodes)
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
    fun `4 字节长度前缀按整包长度识别`() {
        // QQ 帧头长度是整包长度（含 4 字节头本身）
        val body = RvFixtures.hex("08 96 01")
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
    fun `4 字节长度前缀按负载长度也能识别`() {
        val body = RvFixtures.hex("08 96 01")
        val withPrefix = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        assertEquals(4, PacketAnalyzer.analyze(withPrefix).prefixLen)
    }

    @Test
    fun `2 字节长度前缀能识别`() {
        val body = RvFixtures.hex("08 96 01")
        val total = body.size + 2
        val withPrefix = byteArrayOf((total ushr 8).toByte(), total.toByte()) + body
        val a = PacketAnalyzer.analyze(withPrefix)
        assertEquals(2, a.prefixLen)
        assertEquals(PacketAnalyzer.Kind.PROTOBUF, a.kind)
    }

    @Test
    fun `1 字节长度前缀能识别`() {
        val body = RvFixtures.hex("08 96 01")
        val withPrefix = byteArrayOf((body.size + 1).toByte()) + body
        val a = PacketAnalyzer.analyze(withPrefix)
        assertEquals(1, a.prefixLen)
        assertEquals(PacketAnalyzer.Kind.PROTOBUF, a.kind)
    }

    @Test
    fun `无长度前缀时 prefixLen 为 0`() {
        val a = PacketAnalyzer.analyze(RvFixtures.hex("08 96 01"))
        assertEquals(0, a.prefixLen)
    }

    @Test
    fun `典型 protobuf 包识别为 PROTOBUF`() {
        val data = RvFixtures.hex("08 96 01 12 03 61 62 63")
        val a = PacketAnalyzer.analyze(data)
        assertEquals(PacketAnalyzer.Kind.PROTOBUF, a.kind)
        assertTrue(a.confidence > 0.5)
        assertNotNull(a.nodes)
    }

    @Test
    fun `典型 JCE 包识别为 JCE`() {
        // WeQ 的全类型 JCE 向量
        val jce = RvFixtures.hex(
            "0a 16 02 68 69 22 00 00 00 2a 3c 40 ff " +
                "53 01 02 03 04 05 06 07 08 65 3f f8 00 00 00 00 00 00 " +
                "79 02 00 00 00 03 00 01 00 02 00 03 " +
                "88 00 01 06 01 6b 16 01 76 " +
                "9d 00 00 04 de ad be ef " +
                "aa 10 07 0b " +
                "bd 00 00 06 08 01 12 02 68 69 " +
                "0b",
        )
        val a = PacketAnalyzer.analyze(jce)
        assertEquals(PacketAnalyzer.Kind.JCE, a.kind)
        assertNotNull(a.nodes)
        assertTrue(a.confidence > 0.7)
    }

    @Test
    fun `长度前缀被优先剥离（前缀字节本身也能当字段解析时）`() {
        // 这段 body 和「前缀 + body」都能完整解析成 JCE：前缀的 4 个字节会被当成
        // tag0/BYTE 0 之类的假字段吃进去。必须优先采用剥掉前缀的结果。
        val body = RvFixtures.hex("22 00 00 00 2a 16 02 68 69 0b")
        val total = body.size + 4
        val withPrefix = byteArrayOf(
            (total ushr 24).toByte(), (total ushr 16).toByte(),
            (total ushr 8).toByte(), total.toByte(),
        ) + body
        val a = PacketAnalyzer.analyze(withPrefix)
        assertEquals(PacketAnalyzer.Kind.JCE, a.kind)
        assertEquals(4, a.prefixLen)
    }

    // SummaryCard 这类 SSO 命令的 JCE 正文里带 "SummaryCardServantObj"，
    // 曾被 TRPC 信封判定（只要看到 Servant/Obj 就认定是 trpc）抢先截胡。
    // 真实抓包形如 `00 00 10 75 | 10 02 2c 3c 4c 56 15 SummaryCardServantObj 66 0e ReqSummaryCard …`。
    // fixture 只保留决定判定的那几个字段（tag1 BYTE / tag5、tag6 STRING1），不含真实 uin。
    private fun summaryCardSend(): ByteArray {
        // tag1=BYTE(2)、tag5=STRING1("SummaryCardServantObj")、tag6=STRING1("ReqSummaryCard")
        val body = RvFixtures.hex("10 02 56 15") +
            "SummaryCardServantObj".toByteArray() +
            RvFixtures.hex("66 0e") +
            "ReqSummaryCard".toByteArray()
        val total = body.size + 4
        return byteArrayOf(
            (total ushr 24).toByte(), (total ushr 16).toByte(),
            (total ushr 8).toByte(), total.toByte(),
        ) + body
    }

    @Test
    fun `带 ServantObj 字样的 JCE 包不被误判为 TRPC`() {
        val a = PacketAnalyzer.analyze(summaryCardSend())
        assertEquals(PacketAnalyzer.Kind.JCE, a.kind)
        assertNotNull(a.nodes)
        assertEquals(4, a.prefixLen)
    }

    @Test
    fun `确实是 trpc 信封时仍判定为 TRPC`() {
        // 没有可解析的结构、只有明文服务名时才走 TRPC
        val trpc = (
            byteArrayOf(0x7f, 0x02, 0x00, 0x11) +
                "trpc.qqprofile.ConfigServantObj".toByteArray() +
                byteArrayOf(0x00, 0xff, 0x7e, 0x81, 0x00, 0x9c)
            )
        assertEquals(PacketAnalyzer.Kind.TRPC, PacketAnalyzer.analyze(trpc).kind)
    }

    @Test
    fun `dataJson 输出标准 JSON`() {
        val a = PacketAnalyzer.analyze(RvFixtures.hex("08 96 01 12 02 68 69"))
        assertEquals("""{"1":150,"2":"hi"}""", a.dataJson(pretty = false))
    }

    @Test
    fun `无结构时 dataJson 为 null`() {
        assertNull(PacketAnalyzer.analyze("plain text here".toByteArray()).dataJson())
    }

    @Test
    fun `置信度在 0 到 1 之间`() {
        val samples = listOf(
            ByteArray(0),
            RvFixtures.hex("08 01"),
            "text".toByteArray(),
            ByteArray(32) { it.toByte() },
        )
        for (s in samples) {
            val c = PacketAnalyzer.analyze(s).confidence
            assertTrue("置信度越界: $c", c in 0.0..1.0)
        }
    }
}
