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
    enum class Kind { PROTOBUF, JCE, TRPC, TEXT, HEX, EMPTY }

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
        /** 是否经过 gzip 解压。 */
        val gzipped: Boolean = false,
    )

    fun analyze(buffer: ByteArray): Analysis {
        if (buffer.isEmpty()) {
            return Analysis(Kind.EMPTY, 0, 0, null, null, null, 1.0)
        }

        val prefix = detectPrefix(buffer)
        var body = if (prefix > 0) buffer.copyOfRange(prefix, buffer.size) else buffer

        // 0. gzip 压缩体（配置拉取类接口常见）：先解压再分析
        if (body.size > 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) {
            val unzipped = tryGunzip(body)
            if (unzipped != null) {
                val inner = analyze(unzipped)
                // 保留外层 prefix 信息，内容用解压后的
                return inner.copy(prefixLen = prefix, bodySize = unzipped.size, gzipped = true)
            }
        }

        // 1. 纯文本直接返回
        val asText = AutoParser.tryDecodeUtf8(body)
        if (asText != null && asText.length == body.size && body.size >= 3 && isPlainText(asText)) {
            return Analysis(Kind.TEXT, prefix, body.size, null, null, asText, 0.9)
        }

        // 1.5 TRPC 信封：含大段可打印服务名/方法名，不是 protobuf 也不是 JCE。
        //     典型形态：<长度><版本><trace><"2VIP.XXXServer.XXXObj"><方法名><二进制尾部>
        val trpcName = detectTrpcServiceName(body)
        if (trpcName != null) {
            return Analysis(Kind.TRPC, prefix, body.size, null, null, trpcName, 0.9)
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

    /**
     * 检测 4 字节大端长度前缀。
     *
     * QQ 的 SSO 帧头长度是**整包长度**（含 4 字节头本身），
     * 不是剩余长度——实测 `00 00 00 e9` + 229 字节 body 共 233 字节。
     * 老代码按「剩余长度」判断会漏掉所有包。
     */
    private fun detectPrefix(buffer: ByteArray): Int {
        if (buffer.size < 5) return 0
        val declared = ((buffer[0].toInt() and 0xFF) shl 24) or
            ((buffer[1].toInt() and 0xFF) shl 16) or
            ((buffer[2].toInt() and 0xFF) shl 8) or
            (buffer[3].toInt() and 0xFF)
        return when {
            declared == buffer.size -> 4          // 整包长度
            declared == buffer.size - 4 -> 4      // 兼容「剩余长度」写法
            else -> 0
        }
    }

    /**
     * 检测 TRPC 信封里的服务名。
     *
     * QQ 的 trpc 类命令会把 `<服务名>.<方法名>` 以明文放在包体里，
     * 前后是二进制元数据。识别出这种形态后就不该再当 protobuf / JCE 硬解。
     *
     * @return 提取到的「服务名.方法名」；不是 TRPC 信封时返回 null
     */
    private fun detectTrpcServiceName(body: ByteArray): String? {
        // 服务名/方法名会被长度字节切成多段（如 "ConfigServantObj" + 0x0e + "GetResourceReq"），
        // 所以不能只看最长连续段，要把所有可打印段拼起来判断。
        val segments = mutableListOf<String>()
        var start = -1
        for (i in 0..body.size) {
            val c = if (i < body.size) body[i].toInt() and 0xFF else 0
            if (c in 0x20..0x7E) {
                if (start < 0) start = i
            } else {
                if (start >= 0) {
                    segments += String(body, start, i - start, Charsets.US_ASCII)
                    start = -1
                }
            }
        }

        // 前 8 段里找带 Servant/Service/Server/Obj 的段（服务名特征）
        val head = segments.take(8)
        val nameSeg = head.firstOrNull {
            it.length >= 8 && (it.contains("Servant") || it.contains("Service") ||
                it.contains("Server") || it.contains("Obj"))
        } ?: return null

        // 方法名通常紧跟其后，形如 GetXxx / ReqXxx / SsoXxx
        val methodSeg = segments.dropWhile { it != nameSeg }.drop(1)
            .firstOrNull { it.length >= 4 && it[0].isUpperCase() }

        return if (methodSeg != null) "$nameSeg.$methodSeg" else nameSeg
    }

    /** 尝试 gzip 解压；失败返回 null。 */
    private fun tryGunzip(data: ByteArray): ByteArray? = try {
        java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
    } catch (_: Exception) {
        null
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
