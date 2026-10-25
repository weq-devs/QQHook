package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JceLiteTest {

    @Test
    fun `解析 int 字段`() {
        // tag=1 type=2(int) 值 100 → 01 02 00 00 00 64
        val data = byteArrayOf(0x01, 0x02, 0x00, 0x00, 0x00, 0x64)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        assertEquals(1, r.fields.size)
        assertEquals(1, r.fields[0].tag)
        val v = r.fields[0].value
        assertTrue(v is JceLite.Value.Num)
        assertEquals(100L, (v as JceLite.Value.Num).value)
        assertEquals("int", v.typeName)
    }

    @Test
    fun `解析 long 字段`() {
        // tag=2 type=3(long) 值 1
        val data = byteArrayOf(0x02, 0x03, 0, 0, 0, 0, 0, 0, 0, 0x01)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        val v = r.fields[0].value as JceLite.Value.Num
        assertEquals(1L, v.value)
        assertEquals("long", v.typeName)
    }

    @Test
    fun `解析 string 字段`() {
        // tag=3 type=6(string) len=3 "abc"
        val data = byteArrayOf(0x03, 0x06, 0x03, 0x61, 0x62, 0x63)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        val v = r.fields[0].value
        assertTrue(v is JceLite.Value.Text)
        assertEquals("abc", (v as JceLite.Value.Text).text)
    }

    @Test
    fun `解析 short 字段`() {
        // tag=1 type=1(short) 值 258 (0x0102)
        val data = byteArrayOf(0x01, 0x01, 0x01, 0x02)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        assertEquals(258L, (r.fields[0].value as JceLite.Value.Num).value)
    }

    @Test
    fun `解析 struct 字段`() {
        // tag=4 type=9(struct) 内含 tag=1 type=2 int 5
        // 内层: 01 02 00 00 00 05 (6 字节)
        // 外层: 04 09 + 内层
        val inner = byteArrayOf(0x01, 0x02, 0x00, 0x00, 0x00, 0x05)
        val data = byteArrayOf(0x04, 0x09) + inner
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        val v = r.fields[0].value
        assertTrue("应识别为 struct，实际 ${v::class.simpleName}", v is JceLite.Value.Struct)
        val fields = (v as JceLite.Value.Struct).fields
        assertEquals(1, fields.size)
        assertEquals(5L, (fields[0].value as JceLite.Value.Num).value)
    }

    @Test
    fun `解析 list 字段`() {
        // tag=5 type=8(list) size=2，元素为 tag=0 type=2 int
        // 元素1: 00 02 00 00 00 01
        // 元素2: 00 02 00 00 00 02
        val data = byteArrayOf(0x05, 0x08, 0x02) +
            byteArrayOf(0x00, 0x02, 0x00, 0x00, 0x00, 0x01) +
            byteArrayOf(0x00, 0x02, 0x00, 0x00, 0x00, 0x02)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        val v = r.fields[0].value
        assertTrue("应识别为 list，实际 ${v::class.simpleName}", v is JceLite.Value.ListValue)
        val items = (v as JceLite.Value.ListValue).items
        assertEquals(2, items.size)
        assertEquals(1L, (items[0] as JceLite.Value.Num).value)
        assertEquals(2L, (items[1] as JceLite.Value.Num).value)
    }

    @Test
    fun `中文 string 正确解码`() {
        val text = "测试"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val data = byteArrayOf(0x01, 0x06, bytes.size.toByte()) + bytes
        val r = JceLite.parse(data)
        assertEquals(text, (r.fields[0].value as JceLite.Value.Text).text)
    }

    @Test
    fun `空数据不崩溃`() {
        val r = JceLite.parse(ByteArray(0))
        assertTrue(r.fields.isEmpty())
    }
}
