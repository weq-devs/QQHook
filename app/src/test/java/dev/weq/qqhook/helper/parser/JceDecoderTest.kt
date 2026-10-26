package dev.weq.qqhook.helper.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JCE 编码：头字节 `(tag << 4) | type`，tag >= 15 时高半字节为 0xF + 完整 tag 字节。
 *
 * 主要向量直接移植自 WeQ 的 `packages/codec/test/reverse.test.ts`，覆盖全部类型；
 * 这些用例同时验证 `RvJson` 的 `{tag: value}` 输出。
 */
class JceDecoderTest {

    private fun json(data: ByteArray): String? =
        JceDecoder.tryDecode(data)?.let { RvJson.encode(RvJson.nodesToJson(it.nodes), pretty = false) }

    // ---------------------------------------------------------------- 全类型向量

    /**
     * 一个完整的 JCE 结构体（顶层 STRUCT_BEGIN tag0 → ... → STRUCT_END）：
     *   tag1  STRING1 "hi"
     *   tag2  INT 42
     *   tag3  ZERO_TAG 0
     *   tag4  BYTE -1 (0xff)
     *   tag5  LONG 0x0102030405060708
     *   tag6  DOUBLE 1.5
     *   tag7  LIST[3] {1, 2, 3}      （size = tag0 INT 字段）
     *   tag8  MAP{"k":"v"}           （size = tag0 BYTE 字段）
     *   tag9  SIMPLE_LIST deadbeef
     *   tag10 STRUCT{ tag1 BYTE 7 }
     *   tag11 SIMPLE_LIST = protobuf {1:1, 2:"hi"}   ← 混合嵌套
     */
    private val fullHex =
        "0a " +                                        // STRUCT_BEGIN tag0
            "16 02 68 69 " +                           // 1: "hi"
            "22 00 00 00 2a " +                        // 2: 42
            "3c " +                                    // 3: zero
            "40 ff " +                                 // 4: -1
            "53 01 02 03 04 05 06 07 08 " +            // 5: long
            "65 3f f8 00 00 00 00 00 00 " +            // 6: 1.5
            "79 02 00 00 00 03 00 01 00 02 00 03 " +   // 7: list
            "88 00 01 06 01 6b 16 01 76 " +            // 8: map
            "9d 00 00 04 de ad be ef " +               // 9: simple list
            "aa 10 07 0b " +                           // 10: nested struct
            "bd 00 00 06 08 01 12 02 68 69 " +         // 11: simple list = protobuf
            "0b"                                       // STRUCT_END

    @Test
    fun `全类型向量输出与 WeQ 完全一致`() {
        assertEquals(
            """{"0":{"1":"hi","2":42,"3":0,"4":-1,"5":"72623859790382856","6":1.5,""" +
                """"7":[1,2,3],"8":{"k":"v"},"9":"0xdeadbeef","10":{"1":7},"11":{"1":1,"2":"hi"}}}""",
            json(RvFixtures.hex(fullHex)),
        )
    }

    @Test
    fun `顶层 STRUCT 包裹被识别为 tag0 对象`() {
        val r = JceDecoder.tryDecode(RvFixtures.hex(fullHex))
        assertNotNull(r)
        assertEquals(1, r!!.nodes.size)
        assertEquals(0, r.nodes[0].tag)
        assertTrue(RvFeature.WRAPPED_IN_STRUCT in r.features)
        assertEquals(RvFixtures.hex(fullHex).size, r.consumed)
    }

    // ---------------------------------------------------------------- 标量

    @Test
    fun `解析 int 字段`() {
        val data = RvFixtures.head(2, 1) + byteArrayOf(0, 0, 0, 100)
        val r = JceDecoder.tryDecode(data)
        assertNotNull(r)
        assertEquals(1, r!!.nodes.size)
        assertEquals(1, r.nodes[0].tag)
        val v = r.nodes[0].value as RvValue.IntValue
        assertEquals(100L, v.displayLong())
    }

    @Test
    fun `解析 long 字段`() {
        val data = RvFixtures.head(3, 2) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        val r = JceDecoder.tryDecode(data)
        val v = r!!.nodes[0].value as RvValue.IntValue
        assertEquals(1L, v.displayLong())
        assertEquals(64, v.bits)
    }

    @Test
    fun `解析 byte 字段并按有符号展示`() {
        // 0xff → -1
        val data = RvFixtures.head(0, 0) + byteArrayOf(0xFF.toByte())
        val r = JceDecoder.tryDecode(data)
        val v = r!!.nodes[0].value as RvValue.IntValue
        assertEquals(-1L, v.displayLong())
        assertEquals("-1", v.displayString())
    }

    @Test
    fun `解析 short 字段`() {
        val data = RvFixtures.head(1, 1) + byteArrayOf(0x01, 0x02)
        val r = JceDecoder.tryDecode(data)
        assertEquals(258L, (r!!.nodes[0].value as RvValue.IntValue).displayLong())
    }

    @Test
    fun `解析 float 与 double`() {
        val f = RvFixtures.head(4, 1) + byteArrayOf(0x3F, 0x80.toByte(), 0, 0) // 1.0f
        assertEquals(1.0, (JceDecoder.tryDecode(f)!!.nodes[0].value as RvValue.FloatValue).value, 0.0001)

        val d = RvFixtures.head(5, 2) + byteArrayOf(0x3F, 0xF8.toByte(), 0, 0, 0, 0, 0, 0) // 1.5
        assertEquals(1.5, (JceDecoder.tryDecode(d)!!.nodes[0].value as RvValue.FloatValue).value, 0.0001)
    }

    @Test
    fun `ZERO_TAG 解出 0`() {
        val data = RvFixtures.head(12, 3)
        val r = JceDecoder.tryDecode(data)
        val v = r!!.nodes[0].value as RvValue.IntValue
        assertEquals(0L, v.raw)
        assertEquals(0, v.bits)
    }

    // ---------------------------------------------------------------- 字符串

    @Test
    fun `解析 STRING1`() {
        val data = RvFixtures.str1(3, "abc")
        val r = JceDecoder.tryDecode(data)
        assertEquals("abc", (r!!.nodes[0].value as RvValue.Str).text)
    }

    @Test
    fun `解析 STRING4`() {
        val text = "abcdef"
        val data = RvFixtures.head(7, 1) +
            byteArrayOf(0, 0, 0, text.length.toByte()) + text.toByteArray()
        val r = JceDecoder.tryDecode(data)
        assertEquals(text, (r!!.nodes[0].value as RvValue.Str).text)
    }

    @Test
    fun `中文 STRING1 正确解码`() {
        val r = JceDecoder.tryDecode(RvFixtures.str1(1, "测试"))
        assertEquals("测试", (r!!.nodes[0].value as RvValue.Str).text)
    }

    @Test
    fun `tag 大于等于 15 用扩展字节`() {
        val data = RvFixtures.head(2, 20) + byteArrayOf(0, 0, 0, 5)
        val r = JceDecoder.tryDecode(data)
        assertEquals(20, r!!.nodes[0].tag)
        assertEquals(5L, (r.nodes[0].value as RvValue.IntValue).displayLong())
    }

    // ---------------------------------------------------------------- 容器

    @Test
    fun `解析嵌套 STRUCT`() {
        val inner = RvFixtures.head(2, 1) + byteArrayOf(0, 0, 0, 5)
        val data = RvFixtures.head(10, 4) + inner + RvFixtures.head(11, 0)
        val r = JceDecoder.tryDecode(data)
        val obj = r!!.nodes[0].value as RvValue.Obj
        assertEquals(1, obj.fields.size)
        assertEquals(5L, (obj.fields[0].value as RvValue.IntValue).displayLong())
    }

    @Test
    fun `STRUCT 缺少 STRUCT_END 时拒绝解析`() {
        val inner = RvFixtures.head(2, 1) + byteArrayOf(0, 0, 0, 5)
        val data = RvFixtures.head(10, 4) + inner
        assertNull(JceDecoder.tryDecode(data))
    }

    @Test
    fun `解析 LIST（标准带头 size）`() {
        val data = RvFixtures.head(9, 5) + RvFixtures.size(2) +
            RvFixtures.head(2, 0) + byteArrayOf(0, 0, 0, 1) +
            RvFixtures.head(2, 0) + byteArrayOf(0, 0, 0, 2)
        val r = JceDecoder.tryDecode(data)
        val list = r!!.nodes[0].value as RvValue.ListValue
        assertEquals(2, list.items.size)
        assertEquals(1L, (list.items[0].value as RvValue.IntValue).displayLong())
        assertEquals(2L, (list.items[1].value as RvValue.IntValue).displayLong())
    }

    @Test
    fun `解析 MAP`() {
        // tag8 MAP, size = tag0 BYTE 1, 键 tag0 STRING1 "k", 值 tag1 STRING1 "v"
        val data = RvFixtures.hex("88 00 01 06 01 6b 16 01 76")
        val r = JceDecoder.tryDecode(data)
        val map = r!!.nodes[0].value as RvValue.MapValue
        assertEquals(1, map.entries.size)
        assertEquals("k", (map.entries[0].key as RvValue.Str).text)
        assertEquals("v", (map.entries[0].value.value as RvValue.Str).text)
    }

    @Test
    fun `解析 SIMPLE_LIST 为字节`() {
        val data = RvFixtures.hex("9d 00 00 04 de ad be ef")
        val r = JceDecoder.tryDecode(data)
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertEquals("deadbeef", bytes.bytes.toHexLower())
        assertNull(bytes.nested)
    }

    @Test
    fun `SIMPLE_LIST 内容能再解一层时自动展开`() {
        val data = RvFixtures.hex("bd 00 00 06 08 01 12 02 68 69")
        val r = JceDecoder.tryDecode(data)
        val bytes = r!!.nodes[0].value as RvValue.Bytes
        assertNotNull(bytes.nested)
        assertEquals(RvKind.PROTOBUF, bytes.nestedKind)
        assertEquals("""{"1":1,"2":"hi"}""", RvJson.encode(RvJson.nodesToJson(bytes.nested!!), false))
    }

    @Test
    fun `紧凑长度回退`() {
        // 标准读法会把 0x02 当成 tag=0/type=INT 的 size 头，吞掉 4 字节；plausible 失败后回退紧凑长度 2
        val data = RvFixtures.head(9, 5) + byteArrayOf(0x02) +
            RvFixtures.head(2, 0) + byteArrayOf(0, 0, 0, 1) +
            RvFixtures.head(2, 0) + byteArrayOf(0, 0, 0, 2)
        val r = JceDecoder.tryDecode(data)
        assertNotNull("紧凑长度应能回退解析", r)
        val list = r!!.nodes[0].value as RvValue.ListValue
        assertEquals(2, list.items.size)
    }

    @Test
    fun `两字节紧凑长度回退`() {
        val n = 200
        val elems = (0 until n).flatMap { RvFixtures.head(2, 0).toList() + listOf(0, 0, 0, 1) }
            .toByteArray()
        val data = RvFixtures.head(9, 5) + byteArrayOf(0x80.toByte(), n.toByte()) + elems
        val r = JceDecoder.tryDecode(data)
        assertNotNull(r)
        assertEquals(n, (r!!.nodes[0].value as RvValue.ListValue).items.size)
    }

    // ---------------------------------------------------------------- 拒绝

    @Test
    fun `空数据返回 null`() {
        assertNull(JceDecoder.tryDecode(ByteArray(0)))
    }

    @Test
    fun `截断数据返回 null`() {
        // 声明 STRING1 长度 5 但只有 2 字节
        val data = RvFixtures.head(6, 1) + byteArrayOf(5) + "ab".toByteArray()
        assertNull(JceDecoder.tryDecode(data))
    }

    @Test
    fun `拒绝 protobuf 字节`() {
        assertNull(JceDecoder.tryDecode(RvFixtures.hex("08 96 01 12 02 68 69")))
    }
}
