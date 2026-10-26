package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无 schema protobuf 解析。
 *
 * 主向量移植自 WeQ 的 `packages/codec/test/reverse.test.ts`。
 */
class ProtoDecoderTest {

    private fun json(data: ByteArray): String? =
        ProtoDecoder.tryDecode(data)?.let { RvJson.encode(RvJson.nodesToJson(it.nodes), pretty = false) }

    /** 手写 protobuf：field 1 = varint 150 → 08 96 01 */
    private val varint150 = RvFixtures.hex("08 96 01")

    /** field 2 = string "abc" → 12 03 61 62 63 */
    private val stringField = RvFixtures.hex("12 03 61 62 63")

    /**
     * 08 96 01                 1: 150
     * 12 02 68 69              2: "hi"
     * 1a 02 20 01              3: {4: 1}   单字段嵌套
     * 28 01 / 28 02 / 28 03    5: [1, 2, 3]
     * 30 ff…01                 6: 2^64-1
     * 39 1f85eb51b81e0940      7: fixed64 3.14
     */
    private val fullHex =
        "08 96 01 12 02 68 69 1a 02 20 01 28 01 28 02 28 03 " +
            "30 ff ff ff ff ff ff ff ff ff 01 39 1f 85 eb 51 b8 1e 09 40"

    @Test
    fun `全类型向量输出与 WeQ 完全一致`() {
        assertEquals(
            """{"1":150,"2":"hi","3":{"4":1},"5":[1,2,3],""" +
                """"6":"18446744073709551615","7":"4614253070214989087"}""",
            json(RvFixtures.hex(fullHex)),
        )
    }

    @Test
    fun `解析单个 varint 字段`() {
        val r = ProtoDecoder.tryDecode(varint150)
        assertNotNull(r)
        assertEquals(1, r!!.nodes.size)
        assertEquals(1, r.nodes[0].tag)
        val v = r.nodes[0].value as RvValue.IntValue
        assertEquals(150L, v.raw)
        assertEquals(0, v.bits)
        assertEquals(varint150.size, r.consumed)
    }

    @Test
    fun `解析字符串字段`() {
        val r = ProtoDecoder.tryDecode(stringField)
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertEquals("abc", tryReadableText(bytes.bytes))
        assertNull(bytes.nested)
    }

    @Test
    fun `嵌套 message 自动递归展开`() {
        // field 3 是嵌套 message，内含 field 1 = varint 1
        val r = ProtoDecoder.tryDecode(RvFixtures.hex("1a 02 08 01"))
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertNotNull("应识别为嵌套 message", bytes.nested)
        assertEquals(RvKind.PROTOBUF, bytes.nestedKind)
        assertEquals(1L, (bytes.nested!![0].value as RvValue.IntValue).raw)
    }

    @Test
    fun `单字段嵌套也能展开`() {
        // 0a 02 20 01 → 非文本，内部只有一个字段 4=1
        val r = ProtoDecoder.tryDecode(RvFixtures.hex("0a 02 20 01"))
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertNotNull(bytes.nested)
        assertEquals(4, bytes.nested!![0].tag)
    }

    @Test
    fun `可读文本不会被当成嵌套 message`() {
        // "hi" 本身能被解成 {13: 105}，但它是合法文本且只有 1 个字段 → 按文本
        val r = ProtoDecoder.tryDecode(RvFixtures.hex("12 02 68 69"))
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertNull(bytes.nested)
        assertEquals("hi", tryReadableText(bytes.bytes))
    }

    @Test
    fun `中文 UTF-8 字符串正确识别`() {
        val text = "位置共享"
        val body = text.toByteArray(Charsets.UTF_8)
        val data = byteArrayOf(0x0A, body.size.toByte()) + body
        val r = ProtoDecoder.tryDecode(data)
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertEquals(text, tryReadableText(bytes.bytes))
    }

    @Test
    fun `二进制不会被误判成文本`() {
        val binary = RvFixtures.hex("0a 08 00 01 02 03 04 05 06 07")
        val r = ProtoDecoder.tryDecode(binary)
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertNull(tryReadableText(bytes.bytes))
    }

    @Test
    fun `group（wire 3、4）展开为对象`() {
        // field 1 start-group, 内含 field 2 = varint 3, field 1 end-group
        val data = RvFixtures.hex("0b 10 03 0c")
        val r = ProtoDecoder.tryDecode(data)
        assertNotNull(r)
        val obj = r!!.nodes[0].value as RvValue.Obj
        assertEquals(2, obj.fields[0].tag)
        assertEquals(3L, (obj.fields[0].value as RvValue.IntValue).raw)
    }

    @Test
    fun `截断的数据被拒绝`() {
        // 声明长度 5 但只有 2 字节
        assertNull(ProtoDecoder.tryDecode(RvFixtures.hex("0a 05 61 62")))
    }

    @Test
    fun `拒绝 JCE 字节`() {
        assertNull(ProtoDecoder.tryDecode(RvFixtures.hex("0a 16 02 68 69 22 00 00 00 2a")))
    }

    @Test
    fun `长度前缀不参与解析`() {
        val body = varint150
        val full = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        val r = ProtoDecoder.tryDecode(full, offset = 4)
        assertNotNull(r)
        assertEquals(150L, (r!!.nodes[0].value as RvValue.IntValue).raw)
    }
}
