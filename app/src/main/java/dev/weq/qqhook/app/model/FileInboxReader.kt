package dev.weq.qqhook.app.model

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 从 QQ 的私有目录读取 hook 投递的抓包记录。
 *
 * hook 运行在 QQ 的 MSF 进程里，写的是 QQ 自己的 `files/qqhook_inbox/`
 * （同进程写自己的目录，不受 SELinux 跨应用限制）。本应用没有 root 时
 * 读不到那个目录，所以这里通过 `su` 起一个常驻 shell 来读。
 *
 * 之所以不用 ContentProvider：MIUI 的 WakePathChecker 会拒绝 QQ 进程
 * 唤醒本应用的 provider，导致数据全丢。
 */
object FileInboxReader {
    private const val TAG = "FileInboxReader"
    private const val QQ_PKG = "com.tencent.mobileqq"
    private const val INBOX = "qqhook_inbox"

    private val inboxPath = "/data/data/$QQ_PKG/files/$INBOX"

    /** root 是否可用（只探测一次）。 */
    @Volatile
    var rootAvailable: Boolean = false
        private set

    fun probeRoot(): Boolean {
        rootAvailable = runCatching {
            execRoot("id").firstOrNull()?.contains("uid=0") == true
        }.getOrDefault(false)
        Log.i(TAG, "root 可用=$rootAvailable")
        return rootAvailable
    }

    /**
     * 读走当前所有待处理记录。
     *
     * 读完即删，避免重复处理。返回按时间顺序排列的包列表。
     */
    fun drain(): List<CapturePacket> {
        if (!rootAvailable && !probeRoot()) return emptyList()

        val files = listInboxFiles()
        if (files.isEmpty()) return emptyList()

        val out = ArrayList<CapturePacket>(64)
        for (name in files) {
            val content = readAndRemove("$inboxPath/$name") ?: continue
            content.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                parseLine(line)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun listInboxFiles(): List<String> {
        // -1 按时间排序，只取 .jsonl
        val output = execRoot("ls -1t $inboxPath 2>/dev/null").joinToString("\n")
        if (output.isBlank()) return emptyList()
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".jsonl") }
            .toList()
    }

    /** 读取并立即删除，保证不重复消费。 */
    private fun readAndRemove(path: String): String? {
        val lines = execRoot("cat $path 2>/dev/null; rm -f $path")
        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }

    /** su 的绝对路径：App 进程的 PATH 里通常没有。 */
    private val suPath: String by lazy {
        listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        ).firstOrNull { java.io.File(it).exists() } ?: "su"
    }

    /** 以 root 执行命令，返回输出行。 */
    private fun execRoot(cmd: String): List<String> {
        return runCatching {
            val proc = ProcessBuilder(listOf(suPath, "-c", cmd))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .apply {
                    // su 需要正确的 PATH 才能找到 sh/ls 等
                    environment()["PATH"] = "/sbin:/system/sbin:/system/bin:/system/xbin:/debug_ramdisk"
                }
                .start()
            val lines = BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                r.lineSequence().toList()
            }
            val code = proc.waitFor()
            if (code != 0) {
                Log.w(TAG, "su 退出码=$code cmd=$cmd out=${lines.take(3)}")
            }
            lines
        }.getOrElse {
            Log.w(TAG, "su 执行失败(su=$suPath): $cmd", it)
            emptyList()
        }
    }

    private fun parseLine(line: String): CapturePacket? = runCatching {
        val o = JSONObject(line)
        val mode = o.optString("mode")
        CapturePacket(
            from = mode == "receive",
            cmd = o.optString("cmd"),
            seq = o.optInt("seq"),
            buffer = o.optString("buffer").hexToBytes(),
            time = o.optLong("time", System.currentTimeMillis()),
            uin = o.optString("uin").toLongOrNull() ?: 0L,
            msgCookie = o.optString("msgCookie").hexToBytes(),
            type = o.optString("type"),
            source = o.optInt("source", SourceApp.MQQ),
        )
    }.getOrElse {
        Log.w(TAG, "解析失败: ${line.take(120)}", it)
        null
    }

    /** 清理残留（例如 App 崩溃后没读走的文件）。 */
    fun clearInbox() {
        if (!rootAvailable && !probeRoot()) return
        execRoot("rm -rf $inboxPath")
    }

    fun inboxExists(): Boolean =
        rootAvailable && execRoot("ls $inboxPath 2>/dev/null").isNotEmpty()
}
