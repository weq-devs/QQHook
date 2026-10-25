package dev.weq.qqhook.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeGrouperTest {

    private fun pkt(
        seq: Int,
        from: Boolean,
        cmd: String = "test.cmd",
        time: Long = 0,
        size: Int = 10,
    ) = CapturePacket(
        from = from,
        cmd = cmd,
        seq = seq,
        time = time,
        buffer = ByteArray(size),
    )

    @Test
    fun `空列表返回空结果`() {
        assertTrue(ExchangeGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `同 seq 的收发合并为一次交互`() {
        val list = listOf(
            pkt(100, from = false, time = 1000),
            pkt(100, from = true, time = 1050),
        )
        val result = ExchangeGrouper.group(list)
        assertEquals(1, result.size)
        val ex = result[0]
        assertEquals(100, ex.seq)
        assertEquals(1000L, ex.request?.time)
        assertEquals(1050L, ex.response?.time)
        assertEquals(50L, ex.costMs)
    }

    @Test
    fun `不同 seq 分开成多个交互`() {
        val list = listOf(
            pkt(1, from = false),
            pkt(2, from = false),
            pkt(1, from = true),
            pkt(2, from = true),
        )
        val result = ExchangeGrouper.group(list)
        assertEquals(2, result.size)
        assertEquals(setOf(1, 2), result.map { it.seq }.toSet())
    }

    @Test
    fun `只有发送没有接收时 response 为空`() {
        val list = listOf(pkt(7, from = false, time = 100))
        val ex = ExchangeGrouper.group(list)[0]
        assertNull(ex.response)
        assertEquals(100L, ex.request?.time)
        assertTrue(ex.hasError)
        assertNull(ex.costMs)
    }

    @Test
    fun `只有接收没有发送时 request 为空`() {
        val list = listOf(pkt(8, from = true))
        val ex = ExchangeGrouper.group(list)[0]
        assertNull(ex.request)
        assertEquals(0, ex.requestSize)
        assertTrue(ex.response != null)
    }

    @Test
    fun `同 seq 多条发送取最早的`() {
        val list = listOf(
            pkt(9, from = false, time = 200),
            pkt(9, from = false, time = 100),
            pkt(9, from = true, time = 300),
        )
        val ex = ExchangeGrouper.group(list)[0]
        assertEquals(100L, ex.request?.time)
    }

    @Test
    fun `同 seq 多条接收取最晚的`() {
        val list = listOf(
            pkt(10, from = false, time = 100),
            pkt(10, from = true, time = 200),
            pkt(10, from = true, time = 400),
        )
        val ex = ExchangeGrouper.group(list)[0]
        assertEquals(400L, ex.response?.time)
        assertEquals(300L, ex.costMs)
    }

    @Test
    fun `cmd 优先取发送侧的`() {
        val list = listOf(
            pkt(11, from = false, cmd = "send.cmd", time = 100),
            pkt(11, from = true, cmd = "recv.cmd", time = 200),
        )
        val ex = ExchangeGrouper.group(list)[0]
        assertEquals("send.cmd", ex.cmd)
    }

    @Test
    fun `发送侧 cmd 为空时回落到接收侧`() {
        val list = listOf(
            pkt(12, from = false, cmd = "", time = 100),
            pkt(12, from = true, cmd = "recv.cmd", time = 200),
        )
        assertEquals("recv.cmd", ExchangeGrouper.group(list)[0].cmd)
    }

    @Test
    fun `costMs 不为负`() {
        // 响应时间早于请求（乱序到达）
        val list = listOf(
            pkt(13, from = false, time = 500),
            pkt(13, from = true, time = 100),
        )
        assertEquals(0L, ExchangeGrouper.group(list)[0].costMs)
    }

    @Test
    fun `packetsOf 返回同 seq 全部包并按时间排序`() {
        val list = listOf(
            pkt(20, from = true, time = 300),
            pkt(20, from = false, time = 100),
            pkt(21, from = false, time = 200),
        )
        val all = ExchangeGrouper.packetsOf(list, 20)
        assertEquals(2, all.size)
        assertEquals(100L, all[0].time)
        assertEquals(300L, all[1].time)
    }

    @Test
    fun `保持 seq 首次出现的顺序`() {
        val list = listOf(
            pkt(3, from = false),
            pkt(1, from = false),
            pkt(2, from = false),
        )
        val order = ExchangeGrouper.group(list).map { it.seq }
        assertEquals(listOf(3, 1, 2), order)
    }
}
