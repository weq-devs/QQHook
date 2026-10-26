package dev.weq.qqhook.helper.parser

/**
 * 包分析统一入口：自动判断 protobuf / JCE / 纯文本，并给出一份可展示的结构。
 *
 * SSO 包的布局通常有两种：
 * - `[长度前缀][protobuf/JCE body]`
 * - 直接是 body
 *
 * 长度前缀不硬编码宽度：枚举 4 / 2 / 1 字节 × 大端/小端，只有读出的数值恰好
 * 等于整包长度或负载长度才认，且**必须解析成功**才接受该候选。
 */
object PacketAnalyzer {
    enum class Kind { PROTOBUF, JCE, TRPC, TEXT, HEX, EMPTY }

    data class Analysis(
        val kind: Kind,
        /** 跳过的长度前缀字节数（0 / 1 / 2 / 4）。 */
        val prefixLen: Int,
        val bodySize: Int,
        /** protobuf / JCE 的结构化结果，其它类型为 null。 */
        val nodes: List<RvNode>?,
        /** 直接可展示的文本（TEXT / TRPC 时非空）。 */
        val text: String?,
        /** 置信度 0..1，用于 UI 提示「猜得准不准」。 */
        val confidence: Double,
        /** 是否经过 gzip 解压。 */
        val gzipped: Boolean = false,
    ) {
        /** 结构部分的标准 JSON；没有结构化结果时返回 null。 */
        fun dataJson(pretty: Boolean = true): String? =
            nodes?.let { RvJson.encode(RvJson.nodesToJson(it), pretty) }
    }

    fun analyze(buffer: ByteArray): Analysis {
        if (buffer.isEmpty()) {
            return Analysis(Kind.EMPTY, 0, 0, null, null, 1.0)
        }

        // 0. gzip 压缩体（配置拉取类接口常见）：先解压再分析
        if (buffer.size > 2 && buffer[0] == 0x1f.toByte() && buffer[1] == 0x8b.toByte()) {
            val unzipped = tryGunzip(buffer)
            if (unzipped != null) {
                return analyze(unzipped).copy(gzipped = true)
            }
        }

        // 1. 纯文本直接返回
        val asText = tryUtf8(buffer)
        if (asText != null && asText.length == buffer.size && buffer.size >= 3 && isPlainText(asText)) {
            return Analysis(Kind.TEXT, 0, buffer.size, null, asText, 0.9)
        }

        // 1.5 TRPC 信封：含大段可打印服务名/方法名，不是 protobuf 也不是 JCE。
        val trpcName = detectTrpcServiceName(buffer)
        if (trpcName != null) {
            return Analysis(Kind.TRPC, 0, buffer.size, null, trpcName, 0.9)
        }

        // 2. 先按不剥前缀的常规路径解析
        decodeDirect(buffer)?.let { return it }

        // 3. 失败后再自动识别长度前缀重试（宽度 / 字节序由数据自证）
        for (prefix in detectLengthPrefixes(buffer)) {
            val decoded = decodeDirect(prefix.body, prefix.width) ?: continue
            return decoded
        }

        return Analysis(Kind.HEX, 0, buffer.size, null, null, 0.2)
    }

    // ---------------------------------------------------------------- 解码

    private class Decoded(val kind: Kind, val nodes: List<RvNode>, val confidence: Double)

    /** protobuf 优先，JCE 兜底；两者都能解时按结构特征判定。 */
    private fun decodeDirect(body: ByteArray, prefixLen: Int = 0): Analysis? {
        val pb = ProtoDecoder.tryDecode(body)
        val jce = JceDecoder.tryDecode(body)

        val pick = when {
            pb != null && jce != null -> if (looksLikeJce(jce)) {
                Decoded(Kind.JCE, jce.nodes, scoreJce(jce))
            } else {
                Decoded(Kind.PROTOBUF, pb.nodes, scoreProtobuf(pb))
            }

            jce != null -> Decoded(Kind.JCE, jce.nodes, scoreJce(jce))
            pb != null -> Decoded(Kind.PROTOBUF, pb.nodes, scoreProtobuf(pb))
            else -> return null
        }

        return Analysis(
            kind = pick.kind,
            prefixLen = prefixLen,
            bodySize = body.size,
            nodes = pick.nodes,
            text = null,
            confidence = pick.confidence,
        )
    }

    /**
     * JCE 强特征：STRUCT 包裹、MAP、LIST、STRING、FLOAT。
     * protobuf 的字段名（tag）是数字，不会产生这些结构；反过来 protobuf 的
     * 嵌套消息在 JCE 侧通常解不通（要求完整消费 + STRUCT_END）。
     */
    private fun looksLikeJce(r: RvDecoded): Boolean =
        RvFeature.WRAPPED_IN_STRUCT in r.features ||
            RvFeature.HAS_MAP in r.features ||
            RvFeature.HAS_LIST in r.features ||
            RvFeature.HAS_STR in r.features ||
            RvFeature.HAS_FLOAT in r.features

    private fun scoreProtobuf(r: RvDecoded): Double {
        var score = 0.6
        if (RvFeature.HAS_NESTED in r.features) score += 0.2
        if (r.nodes.all { it.tag in 1..1000 }) score += 0.1
        if (r.nodes.size >= 2) score += 0.05
        return score.coerceAtMost(1.0)
    }

    private fun scoreJce(r: RvDecoded): Double {
        var score = 0.55
        if (RvFeature.WRAPPED_IN_STRUCT in r.features) score += 0.15
        if (RvFeature.HAS_MAP in r.features || RvFeature.HAS_LIST in r.features) score += 0.15
        if (RvFeature.HAS_STR in r.features || RvFeature.HAS_FLOAT in r.features) score += 0.1
        if (RvFeature.HAS_ZERO_TAG in r.features) score += 0.05
        return score.coerceAtMost(1.0)
    }

    // ---------------------------------------------------------------- 长度前缀

    private class LengthPrefix(val width: Int, val body: ByteArray)

    /**
     * 枚举「数值自洽」的长度前缀候选，按可信度排序：
     * 4 字节大端 → 4 字节小端 → 2 字节大端 → 2 字节小端 → 1 字节。
     *
     * 「自洽」= 读出的值 == 整包长度，或 == 负载长度（整包长度 − 前缀宽度）。
     */
    private fun detectLengthPrefixes(buf: ByteArray): List<LengthPrefix> {
        val out = mutableListOf<LengthPrefix>()
        for (width in intArrayOf(4, 2, 1)) {
            if (buf.size <= width) continue
            val endians = if (width == 1) intArrayOf(0) else intArrayOf(0, 1) // 0=大端 1=小端
            for (endian in endians) {
                val value = readUInt(buf, width, bigEndian = endian == 0)
                val declaredTotal = value == buf.size.toLong()
                val declaredPayload = value == (buf.size - width).toLong()
                if (value == 0L || (!declaredTotal && !declaredPayload)) continue
                out += LengthPrefix(width, buf.copyOfRange(width, buf.size))
            }
        }
        return out
    }

    private fun readUInt(buf: ByteArray, width: Int, bigEndian: Boolean): Long {
        var value = 0L
        if (bigEndian) {
            for (i in 0 until width) value = value * 256 + (buf[i].toLong() and 0xFF)
        } else {
            for (i in width - 1 downTo 0) value = value * 256 + (buf[i].toLong() and 0xFF)
        }
        return value
    }

    // ---------------------------------------------------------------- 其它识别

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

    private fun isPlainText(s: String): Boolean {
        var printable = 0
        for (c in s) {
            if (c.isLetterOrDigit() || c in " \t\r\n.,:;!?-_/@#%&*()[]{}<>+=|\\\"'`~^\$" ||
                c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F) printable++
        }
        return printable.toDouble() / s.length >= 0.95
    }
}
