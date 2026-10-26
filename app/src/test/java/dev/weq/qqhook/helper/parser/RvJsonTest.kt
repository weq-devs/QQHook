package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `{tag: value}` JSON 输出。不依赖 `org.json`（JVM 单测里那是抛异常的 stub）。
 */
class RvJsonTest {

    private fun enc(nodes: List<RvNode>, pretty: Boolean = false): String =
        RvJson.encode(RvJson.nodesToJson(nodes), pretty)

    @Test
    fun `简单对象`() {
        assertEquals(
            """{"1":150,"2":"hi"}""",
            enc(ProtoDecoder.decode(RvFixtures.hex("08 96 01 12 02 68 69")).nodes),
        )
    }

    @Test
    fun `重复 tag 合并为数组`() {
        // 28 01 / 28 02 / 28 03 → tag 5 出现三次
        assertEquals(
            """{"5":[1,2,3]}""",
            enc(ProtoDecoder.decode(RvFixtures.hex("28 01 28 02 28 03")).nodes),
        )
    }

    @Test
    fun `大整数输出为字符串`() {
        val nodes = listOf(
            RvNode(1, RvValue.IntValue(-1L, 0, signed = false)), // 2^64-1
        )
        assertEquals("""{"1":"18446744073709551615"}""", enc(nodes))
    }

    @Test
    fun `安全范围内的大整数仍是数字`() {
        val nodes = listOf(RvNode(1, RvValue.IntValue(9007199254740991L, 0, signed = false)))
        assertEquals("""{"1":9007199254740991}""", enc(nodes))
    }

    @Test
    fun `JCE 定宽整数按有符号展示`() {
        val nodes = listOf(RvNode(1, RvValue.IntValue(0xFFL, 8, signed = true)))
        assertEquals("""{"1":-1}""", enc(nodes))
    }

    @Test
    fun `bytes 三态 - 嵌套优先`() {
        val nested = listOf(RvNode(4, RvValue.IntValue(1L, 0, false)))
        val nodes = listOf(RvNode(3, RvValue.Bytes(byteArrayOf(0x20, 0x01), nested, RvKind.PROTOBUF)))
        assertEquals("""{"3":{"4":1}}""", enc(nodes))
    }

    @Test
    fun `bytes 三态 - 可读文本`() {
        val nodes = listOf(RvNode(2, RvValue.Bytes("hi".toByteArray(), null, null)))
        assertEquals("""{"2":"hi"}""", enc(nodes))
    }

    @Test
    fun `bytes 三态 - 空`() {
        val nodes = listOf(RvNode(2, RvValue.Bytes(ByteArray(0), null, null)))
        assertEquals("""{"2":""}""", enc(nodes))
    }

    @Test
    fun `bytes 三态 - 非法 UTF-8 退 hex`() {
        // 0xff 不是合法 UTF-8 起始字节
        val nodes = listOf(RvNode(2, RvValue.Bytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), null, null)))
        assertEquals("""{"2":"0xfffe"}""", enc(nodes))
    }

    @Test
    fun `map 的键转字符串`() {
        val map = RvValue.MapValue(
            listOf(
                RvMapEntry(RvValue.Str("k", "k".toByteArray()), RvNode(1, RvValue.Str("v", "v".toByteArray()))),
                RvMapEntry(RvValue.IntValue(7L, 32, signed = true), RvNode(1, RvValue.IntValue(1L, 32, true))),
            ),
        )
        assertEquals("""{"1":{"k":"v","7":1}}""", enc(listOf(RvNode(1, map))))
    }

    @Test
    fun `NaN 与 Infinity 输出为字符串`() {
        assertEquals("""{"1":"NaN"}""", enc(listOf(RvNode(1, RvValue.FloatValue(Double.NaN)))))
        assertEquals("""{"1":"Infinity"}""", enc(listOf(RvNode(1, RvValue.FloatValue(Double.POSITIVE_INFINITY)))))
    }

    @Test
    fun `字符串转义`() {
        val text = "a\"b\\c\nd\t\u0001"
        assertEquals("""{"1":"a\"b\\c\nd\t\u0001"}""", enc(listOf(RvNode(1, RvValue.Str(text, text.toByteArray())))))
    }

    @Test
    fun `孤立代理项被转义`() {
        val text = "a\uD800b"
        assertEquals("""{"1":"a\ud800b"}""", enc(listOf(RvNode(1, RvValue.Str(text, text.toByteArray())))))
    }

    @Test
    fun `pretty 缩进`() {
        val nodes = ProtoDecoder.decode(RvFixtures.hex("08 01 12 02 68 69")).nodes
        assertEquals(
            """
            {
              "1": 1,
              "2": "hi"
            }
            """.trimIndent(),
            enc(nodes, pretty = true),
        )
    }

    @Test
    fun `fixed64 按无符号展示`() {
        val bytes = RvFixtures.hex("1f 85 eb 51 b8 1e 09 40")
        assertEquals("""{"7":"4614253070214989087"}""", enc(listOf(RvNode(7, RvValue.Fixed(bytes, 64)))))
    }

    @Test
    fun `fixed32 按无符号展示`() {
        val bytes = RvFixtures.hex("ff ff ff ff")
        assertEquals("""{"7":4294967295}""", enc(listOf(RvNode(7, RvValue.Fixed(bytes, 32)))))
    }
}
