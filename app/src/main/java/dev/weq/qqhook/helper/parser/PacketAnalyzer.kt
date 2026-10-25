package dev.weq.qqhook.helper.parser

/**
 * 包分析统一入口：自动判断 protobuf / JCE / 纯文本，并给出一份可展示的结构。
 *
 * SSO 包的布局通常有两种：
 * - `[4 字节大端长度][protobuf/JCE body]`
 * - 直接是 body
 *
 * 长度前缀用「首 4 字节是否等于剩余长度」来判定。
 */
object PacketAnalyzer {
    enum class Kind { PROTOBUF, JCE, TEXT, HEX, EMPTY }

    data class Analysis(
        val kind: Kind,
        /** 跳过的长度前缀字节数（0 或 4）。 */
        val prefixLen: Int,
        val bodySize: Int,
        /** protobuf 的结构化结果，JCE/文本时为 null。 */
        val protobuf: AutoParser.Result?,
        /** JCE 的结构化结果。 */
        val jce: JceLite.Result?,
        /** 直接可展示的文本（TEXT 时非空）。 */
        val text: String?,
        /** 置信度 0..1，用于 UI 提示「猜得准不准」。 */
        val confidence: Double,
    )

    fun analyze(buffer: ByteArray): Analysis {
        if (buffer.isEmpty()) {
            return Analysis(Kind.EMPTY, 0, 0, null, null, null, 1.0)
        }

        val prefix = detectPrefix(buffer)
        val body = if (prefix > 0) buffer.copyOfRange(prefix, buffer.size) else buffer

        // 1. 纯文本直接返回
        val asText = AutoParser.tryDecodeUtf8(body)
        if (asText != null && asText.length == body.size && body.size >= 3 && isPlainText(asText)) {
            return Analysis(Kind.TEXT, prefix, body.size, null, null, asText, 0.9)
        }

        // 2. protobuf 尝试
        val pb = AutoParser.parse(body)
        val pbScore = scoreProtobuf(pb, body.size)

        // 3. JCE 尝试
        val jce = JceLite.parse(body)
        val jceScore = scoreJce(jce, body.size)

        return when {
            pbScore >= jceScore && pbScore > 0.3 ->
                Analysis(Kind.PROTOBUF, prefix, body.size, pb, null, null, pbScore)

            jceScore > 0.3 ->
                Analysis(Kind.JCE, prefix, body.size, null, jce, null, jceScore)

            else -> Analysis(Kind.HEX, prefix, body.size, pb.takeIf { it.fields.isNotEmpty() }, null, null, 0.2)
        }
    }

    /** 首 4 字节大端值恰好等于剩余长度 → 认为是长度前缀。 */
    private fun detectPrefix(buffer: ByteArray): Int {
        if (buffer.size < 5) return 0
        val declared = ((buffer[0].toInt() and 0xFF) shl 24) or
            ((buffer[1].toInt() and 0xFF) shl 16) or
            ((buffer[2].toInt() and 0xFF) shl 8) or
            (buffer[3].toInt() and 0xFF)
        return if (declared == buffer.size - 4) 4 else 0
    }

    /** 结构化程度越高、消费越完整，分数越高。 */
    private fun scoreProtobuf(r: AutoParser.Result, total: Int): Double {
        if (r.fields.isEmpty()) return 0.0
        val coverage = if (total == 0) 0.0 else r.consumed.toDouble() / total
        var score = 0.3 + coverage * 0.4
        // 有嵌套 message 是很强的正信号
        if (r.fields.any { it.value is AutoParser.Value.Message }) score += 0.2
        // 字段编号都合理再加一点
        if (r.fields.all { it.number in 1..1000 }) score += 0.1
        return score.coerceAtMost(1.0)
    }

    private fun scoreJce(r: JceLite.Result, total: Int): Double {
        if (r.fields.isEmpty()) return 0.0
        val coverage = if (total == 0) 0.0 else r.consumed.toDouble() / total
        var score = 0.25 + coverage * 0.35
        if (r.fields.any { it.value is JceLite.Value.Struct }) score += 0.2
        if (r.fields.all { it.tag in 0..255 }) score += 0.1
        return score.coerceAtMost(1.0)
    }

    private fun isPlainText(s: String): Boolean {
        var printable = 0
        for (c in s) {
            if (c.isLetterOrDigit() || c in " \t\r\n.,:;!?-_/@#%&*()[]{}<>+=|\\\"'`~^\$" ||
                c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F) printable++
        }
        return printable.toDouble() / s.length >= 0.95
    }
}
