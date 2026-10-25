package dev.weq.qqhook.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.weq.qqhook.R
import dev.weq.qqhook.app.model.CaptureRepository
import dev.weq.qqhook.app.model.CaptureStore
import dev.weq.qqhook.app.ui.screens.HistoryScreen
import dev.weq.qqhook.app.ui.screens.MainScreen
import dev.weq.qqhook.app.ui.screens.SettingsScreen
import dev.weq.qqhook.app.ui.theme.TxHookTheme
import moe.ore.android.AndroKtx
import moe.ore.android.EasyActivity
import moe.ore.android.toast.Toast.toast
import moe.ore.xposed.common.ModeleStatus
import moe.ore.xposed.utils.PrefsManager

class MainActivity : EasyActivity() {
    private var isExit = 0
    private val exitHandler: Handler by lazy {
        object : Handler(mainLooper) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                isExit--
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        PrefsManager.initialize(applicationContext)
        CaptureStore.init(applicationContext)

        val hasAgreedToTerms = PrefsManager.getBoolean(PrefsManager.KEY_AGREED_TO_TERMS)
        val isModuleActivated = ModeleStatus.isModuleActivated()

        CaptureRepository.attachCatchProvider()

        setContent {
            var agreed by rememberSaveable { mutableStateOf(hasAgreedToTerms) }

            TxHookTheme {
                if (!isModuleActivated || !agreed) {
                    dev.weq.qqhook.app.ui.screens.EntryCheckScreen(
                        isModuleActivated = isModuleActivated,
                        onAgree = {
                            PrefsManager.setBoolean(PrefsManager.KEY_AGREED_TO_TERMS, true)
                            AndroKtx.isInit = true
                            agreed = true
                        },
                        onDisagree = { finish() },
                    )
                } else {
                    AndroKtx.isInit = true
                    AppRoot(
                        onOpenDetail = { seq ->
                            startActivity(
                                Intent(this, DetailActivity::class.java).putExtra("seq", seq),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 退到后台时把当前会话落盘，避免被系统杀掉丢数据
        CaptureRepository.flushSession()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            isExit++
            if (isExit < 2) {
                toast(msg = getString(R.string.press_again_exit))
                exitHandler.sendEmptyMessageDelayed(0, 2000)
            } else {
                finish()
                super.onBackPressed()
            }
            return false
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** 顶部导航：抓包 / 历史 / 设置。 */
private enum class RootTab { CAPTURE, HISTORY, SETTINGS }

@androidx.compose.runtime.Composable
private fun AppRoot(onOpenDetail: (Int) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(RootTab.CAPTURE) }

    AnimatedContent(targetState = tab, label = "rootTab") { current ->
        when (current) {
            RootTab.CAPTURE -> MainScreen(
                onOpenDetail = onOpenDetail,
                onNavigateHistory = { tab = RootTab.HISTORY },
                onNavigateSettings = { tab = RootTab.SETTINGS },
            )

            RootTab.HISTORY -> HistoryScreen(
                onBack = { tab = RootTab.CAPTURE },
                onOpenSession = { id ->
                    if (CaptureRepository.loadSession(id)) {
                        tab = RootTab.CAPTURE
                    }
                },
            )

            RootTab.SETTINGS -> SettingsScreen(
                onBack = { tab = RootTab.CAPTURE },
            )
        }
    }
}
