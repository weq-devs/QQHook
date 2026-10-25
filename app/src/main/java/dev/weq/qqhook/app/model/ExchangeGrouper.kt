package dev.weq.qqhook.app.model

/**
 * 把扁平的包列表按 seq 合并成「交互」。
 *
 * QQ 的 SSO 用 seq 关联请求与响应。同 seq 可能有多条（重发 / 分片），
 * 取**最早的发包**和**最晚的回包**代表这一次交互。
 *
 * 没有配对的一侧会单独成卡（超时未回 / 只抓到一侧）。
 */
object ExchangeGrouper {

    fun group(packets: List<CapturePacket>): List<CaptureExchange> {
        if (packets.isEmpty()) return emptyList()

        // seq -> (最早的请求, 最晚的响应)
        val requests = HashMap<Int, CapturePacket>()
        val responses = HashMap<Int, CapturePacket>()
        // 保持出现顺序，结果按这个顺序输出
        val order = ArrayList<Int>()

        for (p in packets) {
            if (order.lastOrNull() != p.seq && !requests.containsKey(p.seq) && !responses.containsKey(p.seq)) {
                order.add(p.seq)
            }
            if (p.from) {
                val prev = responses[p.seq]
                if (prev == null || p.time >= prev.time) responses[p.seq] = p
            } else {
                val prev = requests[p.seq]
                if (prev == null || p.time < prev.time) requests[p.seq] = p
            }
        }

        return order.map { seq ->
            val req = requests[seq]
            val resp = responses[seq]
            CaptureExchange(
                seq = seq,
                cmd = req?.cmd?.takeIf { it.isNotBlank() } ?: resp?.cmd.orEmpty(),
                request = req,
                response = resp,
            )
        }
    }

    /** 同一交互内的所有原始包，按时间排序（详情页用）。 */
    fun packetsOf(packets: List<CapturePacket>, seq: Int): List<CapturePacket> =
        packets.filter { it.seq == seq }.sortedBy { it.time }
}
