package dev.weq.qqhook.app.model

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.weq.qqhook.helper.EMPTY_BYTE_ARRAY
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抓包历史落盘。
 *
 * 一个「会话」（session）= 一次抓包从开始到停止的全部记录，存成一个文件：
 * `files/captures/<id>.json`，id 是开始时间戳。会话列表靠扫描目录得出，
 * 不需要额外索引文件。
 *
 * 写盘是异步的（单线程串行），抓包热路径上不能做 IO。
 */
object CaptureStore {
    private const val TAG = "CaptureStore"
    private const val DIR = "captures"
    /** 单条记录最大落盘体积，超过的 buffer 截断（避免几 MB 的图片包把历史撑爆）。 */
    private const val MAX_BUFFER_BYTES = 2 * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "capture-store-io").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(): File? =
        appContext?.let { File(it.filesDir, DIR).apply { if (!exists()) mkdirs() } }

    // ---------------------------------------------------------------- 会话元信息

    data class SessionMeta(
        val id: Long,
        val packetCount: Int,
        val startedAt: Long,
        val endedAt: Long,
        val cmdCount: Int,
    )

    /** 扫描所有历史会话，按开始时间倒序。 */
    fun listSessions(): List<SessionMeta> {
        val d = dir() ?: return emptyList()
        return d.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { parseMeta(it) }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()
    }

    private fun parseMeta(file: File): SessionMeta? = runCatching {
        val root = JSONObject(file.readText())
        SessionMeta(
            id = root.optLong("id"),
            packetCount = root.optJSONArray("packets")?.length() ?: 0,
            startedAt = root.optLong("startedAt"),
            endedAt = root.optLong("endedAt"),
            cmdCount = root.optJSONArray("packets")?.let { arr ->
                buildSet { for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("cmd")) }.size
            } ?: 0,
        )
    }.getOrNull()

    // ---------------------------------------------------------------- 读写会话

    /** 同步保存一个会话（调用方通常在后台线程）。 */
    fun saveSession(
        startedAt: Long,
        endedAt: Long,
        packets: List<CapturePacket>,
        readSeqSet: Set<Int> = emptySet(),
    ) {
        val d = dir() ?: return
        val file = File(d, "$startedAt.json")
        val root = JSONObject().apply {
            put("version", 1)
            put("id", startedAt)
            put("startedAt", startedAt)
            put("endedAt", endedAt)
            put("readSeqs", JSONArray().apply { readSeqSet.forEach { put(it) } })
            put("packets", JSONArray().apply { packets.forEach { put(it.toJson()) } })
        }
        runCatching {
            AtomicFile(file).startWrite().use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                (out as? java.io.FileOutputStream)?.fd?.sync()
            }
        }.onFailure { Log.w(TAG, "保存会话失败: ${file.name}", it) }
    }

    /** 异步保存，不阻塞调用方。 */
    fun saveSessionAsync(
        startedAt: Long,
        endedAt: Long,
        packets: List<CapturePacket>,
        readSeqSet: Set<Int> = emptySet(),
    ) {
        // 快照一下，避免保存过程中列表被并发修改
        val snapshot = packets.toList()
        io.execute { saveSession(startedAt, endedAt, snapshot, readSeqSet) }
    }

    fun loadSession(id: Long): List<CapturePacket>? {
        val d = dir() ?: return null
        val file = File(d, "$id.json")
        if (!file.exists()) return null
        return runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("packets")
            (0 until arr.length()).map { arr.getJSONObject(it).toPacket() }
        }.onFailure { Log.w(TAG, "读取会话失败: $id", it) }.getOrNull()
    }

    fun loadReadSeqs(id: Long): Set<Int> {
        val d = dir() ?: return emptySet()
        val file = File(d, "$id.json")
        if (!file.exists()) return emptySet()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("readSeqs") ?: return emptySet()
            buildSet { for (i in 0 until arr.length()) add(arr.getInt(i)) }
        }.getOrDefault(emptySet())
    }

    /** 更新某个会话的已读 seq 集合（只改 readSeqs，不动 packets）。 */
    fun updateReadSeqs(id: Long, seqs: Set<Int>) {
        io.execute {
            val d = dir() ?: return@execute
            val file = File(d, "$id.json")
            if (!file.exists()) return@execute
            runCatching {
                val root = JSONObject(file.readText())
                root.put("readSeqs", JSONArray().apply { seqs.forEach { put(it) } })
                AtomicFile(file).startWrite().use { out ->
                    out.write(root.toString().toByteArray(Charsets.UTF_8))
                }
            }.onFailure { Log.w(TAG, "更新已读状态失败: $id", it) }
        }
    }

    fun deleteSession(id: Long) {
        io.execute {
            runCatching { dir()?.let { File(it, "$id.json").delete() } }
        }
    }

    fun clearAll() {
        io.execute {
            dir()?.listFiles()?.forEach { it.delete() }
        }
    }

    // ---------------------------------------------------------------- 导出

    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)

    fun exportStamp(time: Long = System.currentTimeMillis()): String = fileStamp.format(Date(time))

    /** 导出为可读文本，每行一条包，便于 grep。 */
    fun exportText(packets: List<CapturePacket>): String {
        val sb = StringBuilder()
        sb.append("# TXHook SSO capture export\n")
        sb.append("# format: <time ISO> <dir> <cmd> seq=<seq> uin=<uin> size=<bytes>\n")
        sb.append("#\n# --- payloads (hex) ---\n")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        for (p in packets) {
            val dirTag = if (p.from) "RECV" else "SEND"
            sb.append(iso.format(Date(p.time)))
                .append(' ').append(dirTag)
                .append(' ').append(p.cmd)
                .append(" seq=").append(p.seq)
                .append(" uin=").append(p.uin)
                .append(" size=").append(p.buffer.size)
                .append('\n')
        }
        sb.append('\n')
        for (p in packets) {
            sb.append("# ").append(if (p.from) "RECV" else "SEND")
                .append(' ').append(p.cmd)
                .append(" seq=").append(p.seq)
                .append(" uin=").append(p.uin).append('\n')
            sb.append(p.buffer.toHex()).append('\n')
        }
        return sb.toString()
    }

    /** 导出为 JSON，结构化、便于二次处理。 */
    fun exportJson(packets: List<CapturePacket>): String {
        val arr = JSONArray()
        packets.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("count", packets.size)
            put("packets", arr)
        }.toString(2)
    }

    // ---------------------------------------------------------------- 序列化

    private fun CapturePacket.toJson(): JSONObject = JSONObject().apply {
        put("from", from)
        put("cmd", cmd)
        put("seq", seq)
        put("time", time)
        put("uin", uin)
        put("type", type)
        put("source", source)
        put("merge", merge)
        put("buffer", buffer.takeIf { it.size <= MAX_BUFFER_BYTES }?.toHex() ?: "")
        put("truncated", buffer.size > MAX_BUFFER_BYTES)
    }

    private fun JSONObject.toPacket(): CapturePacket = CapturePacket(
        from = optBoolean("from"),
        cmd = optString("cmd"),
        seq = optInt("seq"),
        buffer = optString("buffer").hexToBytes(),
        time = optLong("time"),
        uin = optLong("uin"),
        type = optString("type"),
        source = optInt("source", SourceApp.MQQ),
        merge = optBoolean("merge"),
    )
}

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    if (isEmpty()) return ""
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

internal fun String.hexToBytes(): ByteArray {
    if (isEmpty() || length % 2 != 0) return EMPTY_BYTE_ARRAY
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return EMPTY_BYTE_ARRAY
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
