package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tars/JCE 编码：字段头是一个字节 `(type << 4) | tag`，
 * tag >= 15 时低 4 位为 0x0F，真正的 tag 紧跟其后。
 */
class JceLiteTest {

    /** 构造字段头。 */
    private fun head(type: Int, tag: Int): ByteArray =
        if (tag < 15) byteArrayOf((((type shl 4) or tag) and 0xFF).toByte())
        else byteArrayOf((((type shl 4) or 0x0F) and 0xFF).toByte(), tag.toByte())

    @Test
    fun `解析 int 字段`() {
        // type=2(int) tag=1，值 100
        val data = head(2, 1) + byteArrayOf(0, 0, 0, 100)
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
        // type=3(long) tag=2，值 1
        val data = head(3, 2) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        val v = r.fields[0].value as JceLite.Value.Num
        assertEquals(1L, v.value)
        assertEquals("long", v.typeName)
    }

    @Test
    fun `解析 string 字段`() {
        // type=6(string) tag=3，长度 3 "abc"
        val data = head(6, 3) + byteArrayOf(3) + "abc".toByteArray()
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        val v = r.fields[0].value
        assertTrue(v is JceLite.Value.Text)
        assertEquals("abc", (v as JceLite.Value.Text).text)
    }

    @Test
    fun `解析 short 字段`() {
        // type=1(short) tag=1，值 258 (0x0102)
        val data = head(1, 1) + byteArrayOf(0x01, 0x02)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        assertEquals(258L, (r.fields[0].value as JceLite.Value.Num).value)
    }

    @Test
    fun `解析 byte 字段`() {
        // type=0(byte) tag=0，值 7
        val data = head(0, 0) + byteArrayOf(7)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        assertEquals(7L, (r.fields[0].value as JceLite.Value.Num).value)
    }

    @Test
    fun `tag 大于等于 15 用扩展字节`() {
        // type=2(int) tag=20
        val data = head(2, 20) + byteArrayOf(0, 0, 0, 5)
        val r = JceLite.parse(data)
        assertTrue(r.valid)
        assertEquals(20, r.fields[0].tag)
        assertEquals(5L, (r.fields[0].value as JceLite.Value.Num).value)
    }

    @Test
    fun `解析 struct 字段`() {
        // type=9(struct) tag=4，内含 int tag=1 值 5
        val inner = head(2, 1) + byteArrayOf(0, 0, 0, 5)
        val data = head(9, 4) + inner
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
        // type=8(list) tag=5，size=2，元素为 int
        val data = head(8, 5) + byteArrayOf(2) +
            head(2, 0) + byteArrayOf(0, 0, 0, 1) +
            head(2, 0) + byteArrayOf(0, 0, 0, 2)
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
        val data = head(6, 1) + byteArrayOf(bytes.size.toByte()) + bytes
        val r = JceLite.parse(data)
        assertEquals(text, (r.fields[0].value as JceLite.Value.Text).text)
    }

    @Test
    fun `空数据不崩溃`() {
        val r = JceLite.parse(ByteArray(0))
        assertTrue(r.fields.isEmpty())
    }
}
