package dev.weq.qqhook.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.weq.qqhook.app.model.CaptureExchange
import dev.weq.qqhook.app.model.CaptureStore
import java.io.File

/**
 * 导出抓包数据：写入 Downloads 目录，并通过系统分享面板发送。
 *
 * 不需要额外权限：Android 10+ 用 MediaStore 之外的 app 专属目录 + FileProvider；
 * 为了兼容低版本（minSdk 27）直接写公共 Downloads 目录。
 */
object ExportHelper {
    private const val TAG = "ExportHelper"

    enum class Format { TEXT, JSON }

    /** 导出并弹分享面板。 */
    fun export(context: Context, exchanges: List<CaptureExchange>, format: Format = Format.TEXT) {
        if (exchanges.isEmpty()) {
            Toast.makeText(context, "没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val packets = exchanges.flatMap { listOfNotNull(it.request, it.response) }
            .sortedBy { it.time }

        val stamp = CaptureStore.exportStamp()
        val (name, content) = when (format) {
            Format.TEXT -> "qqhook-$stamp.txt" to CaptureStore.exportText(packets)
            Format.JSON -> "qqhook-$stamp.json" to CaptureStore.exportJson(packets)
        }

        val file = runCatching { writeFile(context, name, content) }
            .onFailure { Log.w(TAG, "写入导出文件失败", it) }
            .getOrNull()

        if (file == null) {
            // 写文件失败就退回复制到剪贴板
            copyToClipboard(context, content)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            return
        }

        share(context, file)
    }

    private fun writeFile(context: Context, name: String, content: String): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "QQHook",
        ).apply { if (!exists()) mkdirs() }

        val target = File(dir, name)
        // 先在 app 内部写，再尝试落到 Downloads；失败则留在内部目录
        return runCatching {
            target.writeText(content)
            target
        }.getOrElse {
            File(context.cacheDir, name).apply { writeText(content) }
        }
    }

    private fun share(context: Context, file: File) {
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            // 没有 FileProvider（或路径不在配置里）就退回复制
            copyToClipboard(context, file.readText())
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "导出抓包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure {
                copyToClipboard(context, file.readText())
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
    }

    /** 复制单条包的 hex / 文本。 */
    fun copyToClipboard(context: Context, text: String, label: String = "抓包数据") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** 分享纯文本（长按菜单用）。 */
    fun shareText(context: Context, text: String, title: String = "分享") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
