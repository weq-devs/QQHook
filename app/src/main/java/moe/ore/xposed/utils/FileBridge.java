package moe.ore.xposed.utils;

import android.content.ContentValues;

import dev.weq.qqhook.helper.HexUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文件投递通道。
 *
 * 为什么不用 ContentProvider：MIUI 的「自启动」拦截会拒绝跨进程 provider 唤醒
 * （WakePathChecker: Service/Provider/Broadcast Reject），导致 MSF 进程里
 * contentResolver.getType() 返回 null，数据全部丢失。
 *
 * 方案：hook 端写 QQ 自己的私有目录（同进程写自己的 files/，无任何权限问题），
 * App 端用 root 权限轮询读取。SELinux 只禁止跨应用写 app_data_file，不限制
 * 应用写自己的目录。
 *
 * 写入格式：JSON Lines，一行一条，追加写。App 端读完后按行解析并删除文件。
 * 为避免每条包都开文件，做 200ms / 64 条的批量缓冲。
 */
public final class FileBridge {

    private static final String TAG = "TXHook-FILE";
    /** 相对 QQ 私有 files 目录。 */
    private static final String INBOX_DIR = "qqhook_inbox";
    /** 缓冲区最多攒多少条，超过就立刻刷盘。 */
    private static final int MAX_BUFFERED_RECORDS = 512;
    /** 缓冲刷新间隔。 */
    private static final long FLUSH_INTERVAL_MS = 200L;

    private static final Object LOCK = new Object();
    private static final StringBuilder BUFFER = new StringBuilder();
    private static int bufferedCount = 0;
    private static int fileSeq = 0;
    private static long lastFlush = 0L;
    private static File inboxDir = null;
    private static Thread flusher = null;

    private FileBridge() {
    }

    /**
     * 初始化投递目录。
     *
     * @param appFilesDir 宿主（QQ）的 files 目录
     */
    public static void init(File appFilesDir) {
        synchronized (LOCK) {
            File dir = new File(appFilesDir, INBOX_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                de.robv.android.xposed.XposedBridge.log(TAG + " 创建目录失败: " + dir);
                return;
            }
            inboxDir = dir;
            de.robv.android.xposed.XposedBridge.log(TAG + " 投递目录: " + dir);
            startFlusher();
        }
    }

    /**
     * 定时刷盘。
     *
     * append() 只在「下一条记录到来时」检查是否该刷，所以暂停抓包后
     * 缓冲区里最后那批记录永远等不到下一次 append —— 必须有个后台线程兜底。
     */
    private static void startFlusher() {
        if (flusher != null) return;
        flusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                    flush();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    de.robv.android.xposed.XposedBridge.log(TAG + " 刷盘异常: " + t);
                }
            }
        }, "txhook-filebridge-flush");
        flusher.setDaemon(true);
        flusher.start();
    }

    public static boolean isReady() {
        return inboxDir != null;
    }

    /**
     * 追加一条记录。
     *
     * @param mode  send / receive
     * @param values 与 ContentValues 一致的数据
     */
    public static void append(String mode, ContentValues values) {
        if (inboxDir == null) return;

        String line = toJsonLine(mode, values);
        boolean needFlush;
        synchronized (LOCK) {
            BUFFER.append(line).append('\n');
            bufferedCount++;
            long now = System.currentTimeMillis();
            needFlush = bufferedCount >= MAX_BUFFERED_RECORDS
                    || (bufferedCount > 0 && now - lastFlush >= FLUSH_INTERVAL_MS);
        }
        if (needFlush) flush();
    }

    /** 把缓冲写入磁盘。 */
    public static void flush() {
        String payload;
        int count;
        synchronized (LOCK) {
            if (BUFFER.length() == 0) return;
            payload = BUFFER.toString();
            count = bufferedCount;
            BUFFER.setLength(0);
            bufferedCount = 0;
            lastFlush = System.currentTimeMillis();
        }

        File target = nextFile();
        if (target == null) return;

        try (FileOutputStream fos = new FileOutputStream(target, true)) {
            fos.write(payload.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (IOException e) {
            de.robv.android.xposed.XposedBridge.log(TAG + " 写入失败: " + e);
        }
    }

    /** 轮转文件，避免单文件无限增长。 */
    private static File nextFile() {
        synchronized (LOCK) {
            if (inboxDir == null) return null;
            // 找一个还没达到上限的文件
            for (int i = 0; i < 100; i++) {
                File f = new File(inboxDir, "batch-" + ((fileSeq + i) % 100) + ".jsonl");
                if (!f.exists() || f.length() < 4 * 1024 * 1024) {
                    fileSeq = (fileSeq + i) % 100;
                    return f;
                }
            }
            fileSeq = (fileSeq + 1) % 100;
            return new File(inboxDir, "batch-" + fileSeq + ".jsonl");
        }
    }

    private static String toJsonLine(String mode, ContentValues values) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"mode\":\"").append(escape(mode)).append('"');
        for (String key : values.keySet()) {
            if ("mode".equals(key)) continue;
            Object v = values.get(key);
            if (v == null) continue;
            sb.append(",\"").append(escape(key)).append("\":");
            if (v instanceof Number) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof byte[]) {
                sb.append('"').append(HexUtil.Bin2Hex((byte[]) v)).append('"');
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
