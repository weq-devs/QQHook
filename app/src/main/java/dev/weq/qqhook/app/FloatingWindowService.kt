package dev.weq.qqhook.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.weq.qqhook.app.model.CaptureRepository

/**
 * 悬浮控制条。
 *
 * 参考 reqable：一个小小的胶囊，显示当前状态 + 一键开始 / 暂停，
 * 可以拖动，点一下展开计数。它本身不抓包（抓包在 QQ 进程里由 hook 完成），
 * 只负责改 [CaptureRepository.isCatchEnabled] 这个开关。
 *
 * 注意：这是**另一个进程的 UI**，它读到的 Repository 是 App 自己的实例；
 * 真正生效依赖 CatchProvider 的 content provider 状态查询，这里只做本进程内的开关同步。
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var container: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var statusText: TextView? = null
    private var countText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        refreshUi()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        container?.let { runCatching { windowManager.removeView(it) } }
        container = null
    }

    // ---------------------------------------------------------------- 悬浮视图

    private fun addFloatingView() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        val root = FrameLayout(this)
        val pill = LayoutInflater.from(this).inflate(R.layout.floating_pill, root, false)
        root.addView(pill)
        container = root

        val status = pill.findViewById<TextView>(R.id.floating_status)
        val count = pill.findViewById<TextView>(R.id.floating_count)
        val toggle = pill.findViewById<ImageView>(R.id.floating_toggle)
        val close = pill.findViewById<ImageView>(R.id.floating_close)
        statusText = status
        countText = count

        toggle.setOnClickListener {
            CaptureRepository.updateCatchEnabled(!CaptureRepository.isCatchEnabled)
            refreshUi()
        }

        close.setOnClickListener {
            stopSelf()
        }

        // 拖动 + 点击
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        pill.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) pill.performClick()
                    true
                }

                else -> false
            }
        }

        runCatching { windowManager.addView(root, params) }
            .onFailure { stopSelf() }

        refreshUi()
    }

    private fun refreshUi() {
        val enabled = CaptureRepository.isCatchEnabled
        statusText?.text = if (enabled) "抓包中" else "已暂停"
        statusText?.setTextColor(
            if (enabled) 0xFF2ECC71.toInt() else 0xFFB0B6C0.toInt(),
        )
        countText?.text = "${CaptureRepository.packets.size}"
    }

    // ---------------------------------------------------------------- 前台通知

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "抓包控制", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "悬浮控制条运行中"
                    setShowBadge(false)
                },
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingWindowService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_catch)
            .setContentTitle("TXHook 抓包控制")
            .setContentText("悬浮条已启用")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, "关闭", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qqhook_floating"
        private const val NOTIFICATION_ID = 0x5150
        const val ACTION_STOP = "dev.weq.qqhook.STOP_FLOATING"

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
