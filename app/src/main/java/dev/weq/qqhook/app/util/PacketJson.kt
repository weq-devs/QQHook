package dev.weq.qqhook.app.util

import dev.weq.qqhook.app.model.CapturePacket
import dev.weq.qqhook.helper.parser.PacketAnalyzer
import dev.weq.qqhook.helper.parser.RvJson
import dev.weq.qqhook.helper.parser.RvJsonValue
import dev.weq.qqhook.helper.parser.toHexLower

/**
 * 单条包的标准 JSON：元信息 + 解析结果。
 *
 * 详情页的复制 / 分享走这里。已解析的包给 `data`，未解析的（HEX / TRPC / EMPTY）
 * 退回 `hex`，避免复制时丢数据。
 */
object PacketJson {
    fun encode(p: CapturePacket, analysis: PacketAnalyzer.Analysis?): String {
        val entries = mutableListOf<Pair<String, RvJsonValue>>()

        entries += "from" to RvJsonValue.Bool(p.from)
        entries += "cmd" to RvJsonValue.Str(p.cmd)
        entries += "seq" to RvJsonValue.Num(p.seq.toString())
        entries += "uin" to RvJsonValue.Num(p.uin.toString())
        entries += "time" to RvJsonValue.Num(p.time.toString())
        entries += "size" to RvJsonValue.Num(p.buffer.size.toString())

        if (analysis != null) {
            entries += "kind" to RvJsonValue.Str(analysis.kind.name)
            entries += "prefixLen" to RvJsonValue.Num(analysis.prefixLen.toString())
            entries += "gzipped" to RvJsonValue.Bool(analysis.gzipped)
        }

        when {
            analysis?.nodes != null -> entries += "data" to RvJson.nodesToJson(analysis.nodes)
            analysis?.text != null -> entries += "text" to RvJsonValue.Str(analysis.text)
            else -> entries += "hex" to RvJsonValue.Str(p.buffer.toHexLower())
        }

        return RvJson.encode(RvJsonValue.Obj(entries), pretty = true)
    }
}
