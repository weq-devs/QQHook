package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoParserTest {

    /** 手写 protobuf：field 1 = varint 150 → 08 96 01 */
    private val varint150 = byteArrayOf(0x08, 0x96.toByte(), 0x01)

    /** field 2 = string "abc" → 12 03 61 62 63 */
    private val stringField = byteArrayOf(0x12, 0x03, 0x61, 0x62, 0x63)

    @Test
    fun `解析单个 varint 字段`() {
        val r = AutoParser.parse(varint150)
        assertTrue(r.valid)
        assertEquals(1, r.fields.size)
        assertEquals(1, r.fields[0].number)
        assertEquals(0, r.fields[0].wireType)
        assertEquals(150L, (r.fields[0].value as AutoParser.Value.Varint).value)
        assertEquals(varint150.size, r.consumed)
    }

    @Test
    fun `解析字符串字段`() {
        val r = AutoParser.parse(stringField)
        assertTrue(r.valid)
        assertEquals(2, r.fields[0].number)
        assertEquals(2, r.fields[0].wireType)
        val v = r.fields[0].value
        assertTrue(v is AutoParser.Value.Text)
        assertEquals("abc", (v as AutoParser.Value.Text).text)
    }

    @Test
    fun `长度前缀不参与解析`() {
        // 4 字节大端长度 + body
        val body = varint150
        val full = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        val r = AutoParser.parse(full, 4)
        assertTrue(r.valid)
        assertEquals(150L, (r.fields[0].value as AutoParser.Value.Varint).value)
    }

    @Test
    fun `嵌套 message 自动递归展开`() {
        // field 3 是嵌套 message，内含 field 1 = varint 1
        // 内层: 08 01 (2 字节) → 外层: 1a 02 08 01
        val nested = byteArrayOf(0x1A, 0x02, 0x08, 0x01)
        val r = AutoParser.parse(nested)
        assertTrue(r.valid)
        val v = r.fields[0].value
        assertTrue("应识别为嵌套 message，实际 ${v::class.simpleName}", v is AutoParser.Value.Message)
        val inner = (v as AutoParser.Value.Message).fields
        assertEquals(1, inner.size)
        assertEquals(1L, (inner[0].value as AutoParser.Value.Varint).value)
    }

    @Test
    fun `中文 UTF-8 字符串正确识别`() {
        // field 1 = "位置共享" (12 字节 UTF-8)
        val text = "位置共享"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val field = byteArrayOf(0x0A, bytes.size.toByte()) + bytes
        val r = AutoParser.parse(field)
        assertTrue(r.valid)
        val v = r.fields[0].value
        assertTrue(v is AutoParser.Value.Text)
        assertEquals(text, (v as AutoParser.Value.Text).text)
    }

    @Test
    fun `二进制不会被误判成文本`() {
        // 0x00..0x0F 这类不可打印字节
        val binary = byteArrayOf(0x0A, 0x08, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val r = AutoParser.parse(binary)
        val v = r.fields[0].value
        assertTrue("二进制应落到 Bytes，实际 ${v::class.simpleName}", v is AutoParser.Value.Bytes)
    }

    @Test
    fun `zigzag 解码正确`() {
        // field 1 = varint 2 → zigzag 1
        val r = AutoParser.parse(byteArrayOf(0x08, 0x02))
        val v = r.fields[0].value as AutoParser.Value.Varint
        assertEquals(2L, v.value)
        assertEquals(1L, v.zigzag)
    }

    @Test
    fun `截断的数据标记为无效`() {
        // 声明长度 5 但只有 2 字节
        val truncated = byteArrayOf(0x0A, 0x05, 0x61, 0x62)
        val r = AutoParser.parse(truncated)
        assertTrue("截断数据不应标记为完全有效", !r.valid || r.consumed < truncated.size)
    }

    @Test
    fun `readVarint 处理多字节`() {
        val v = AutoParser.readVarint(byteArrayOf(0x96.toByte(), 0x01), 0)
        assertNotNull(v)
        assertEquals(150L, v!!.first)
        assertEquals(2, v.second)
    }
}
