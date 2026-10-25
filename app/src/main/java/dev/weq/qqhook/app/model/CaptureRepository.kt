@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.weq.qqhook.app.model

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.weq.qqhook.app.CatchProvider
import moe.ore.script.Consist
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 抓包数据的单一来源。
 *
 * 只处理 SSO 包（收 / 发），不再有 TEA / TLV / MD5 之类的 action。
 * 所有可变状态都是 Compose 的 snapshot state，UI 直接读即可。
 */
object CaptureRepository {
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 当前会话抓到的包，按到达顺序（旧 → 新）。 */
    val packets = mutableStateListOf<CapturePacket>()

    /** 每次 [packets] 有结构性变化都 +1，供 UI 做增量计算。 */
    var packetGeneration by mutableIntStateOf(0)
        private set

    var isCatchEnabled by mutableStateOf(Consist.isCatch)
        private set

    /** 当前会话开始时间；未开始抓包时为 0。 */
    var sessionStartedAt by mutableStateOf(0L)
        private set

    /** 已读的 seq 集合（查看过的卡片变灰）。 */
    val readSeqs = mutableStateListOf<Int>()

    private var attached = false
    private val _nextItemId = AtomicLong(1)
    private val _seqCounter = AtomicInteger(0)

    fun nextItemId(): Long = _nextItemId.getAndIncrement()

    // ---------------------------------------------------------------- 抓包开关

    fun attachCatchProvider() {
        if (attached) return
        attached = true
        CatchProvider.catchHandler = object : CatchProvider.Companion.CatchHandler() {
            override fun handlePacket(time: Long, packet: CapturePacket) {
                addPacket(packet)
            }
        }
    }

    fun updateCatchEnabled(enabled: Boolean) {
        if (enabled && !isCatchEnabled) {
            // 开始新一轮抓包：开一个新会话
            sessionStartedAt = System.currentTimeMillis()
        }
        isCatchEnabled = enabled
        Consist.isCatch = enabled
        if (!enabled) {
            flushSession()
        }
    }

    /** 把当前会话写到历史。停止抓包 / 切到历史 / 退出时调用。 */
    fun flushSession() {
        val start = sessionStartedAt
        if (start == 0L || packets.isEmpty()) return
        CaptureStore.saveSessionAsync(
            startedAt = start,
            endedAt = System.currentTimeMillis(),
            packets = packets,
            readSeqSet = readSeqs.toSet(),
        )
    }

    private fun addPacket(packet: CapturePacket) {
        val id = nextItemId()
        uiHandler.post {
            if (isCatchEnabled) {
                packet.uid = id
                packets.add(packet)
                packetGeneration++
            }
        }
    }

    fun clearPackets() {
        packets.clear()
        readSeqs.clear()
        packetGeneration++
        sessionStartedAt = if (isCatchEnabled) System.currentTimeMillis() else 0L
    }

    // ---------------------------------------------------------------- 已读状态

    fun isRead(seq: Int): Boolean = readSeqs.contains(seq)

    fun markRead(seq: Int) {
        if (!readSeqs.contains(seq)) {
            readSeqs.add(seq)
            val start = sessionStartedAt
            if (start != 0L) CaptureStore.updateReadSeqs(start, readSeqs.toSet())
        }
    }

    fun markAllRead() {
        packets.forEach { if (!readSeqs.contains(it.seq)) readSeqs.add(it.seq) }
        val start = sessionStartedAt
        if (start != 0L) CaptureStore.updateReadSeqs(start, readSeqs.toSet())
    }

    /** 删除（隐藏）单条已读记录，但不动历史文件。 */
    fun removeFromCurrent(packet: CapturePacket) {
        packets.remove(packet)
        packetGeneration++
    }

    fun removeAll(predicate: (CapturePacket) -> Boolean) {
        packets.removeAll(predicate)
        packetGeneration++
    }

    // ---------------------------------------------------------------- 历史会话

    /** 载入历史会话到当前列表（只读浏览模式）。 */
    fun loadSession(id: Long): Boolean {
        val loaded = CaptureStore.loadSession(id) ?: return false
        packets.clear()
        packets.addAll(loaded)
        readSeqs.clear()
        readSeqs.addAll(CaptureStore.loadReadSeqs(id))
        sessionStartedAt = id
        packetGeneration++
        return true
    }

    fun listSessions(): List<CaptureStore.SessionMeta> = CaptureStore.listSessions()

    fun deleteSession(id: Long) = CaptureStore.deleteSession(id)

    fun clearHistory() = CaptureStore.clearAll()
}
