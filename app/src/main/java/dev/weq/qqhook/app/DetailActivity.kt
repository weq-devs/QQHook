package dev.weq.qqhook.app

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.weq.qqhook.app.model.CaptureExchange
import dev.weq.qqhook.app.model.CaptureRepository
import dev.weq.qqhook.app.model.ExchangeGrouper
import dev.weq.qqhook.app.ui.screens.DetailScreen
import dev.weq.qqhook.app.ui.theme.TxHookTheme
import moe.ore.android.EasyActivity

/**
 * 单次交互详情页。
 *
 * 传 seq 而不是整个包，因为同一个 seq 可能有多条收发记录，
 * 详情页要能展示全部。
 */
class DetailActivity : EasyActivity() {
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

        val seq = intent.getIntExtra("seq", Int.MIN_VALUE)
        if (seq == Int.MIN_VALUE) {
            finish()
            return
        }

        val packets = CaptureRepository.packets.toList()
        val exchange: CaptureExchange? = ExchangeGrouper.group(packets)
            .firstOrNull { it.seq == seq }
            ?: ExchangeGrouper.group(packets.filter { it.seq == seq }).firstOrNull()

        if (exchange == null) {
            finish()
            return
        }

        setContent {
            TxHookTheme {
                DetailScreen(
                    request = exchange.request,
                    response = exchange.response,
                    seq = seq,
                    onBack = { finish() },
                )
            }
        }
    }
}
