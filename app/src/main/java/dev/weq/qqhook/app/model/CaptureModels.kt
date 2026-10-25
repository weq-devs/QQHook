package dev.weq.qqhook.app.model

import android.os.Parcel
import android.os.Parcelable
import dev.weq.qqhook.helper.EMPTY_BYTE_ARRAY

/** 一条 SSO 抓包记录。收 / 发共用一种结构，靠 [from] 区分方向。 */
data class CapturePacket(
    /** true = 接收（服务器 → 客户端），false = 发送 */
    var from: Boolean = false,
    var cmd: String = "",
    var seq: Int = 0,
    var buffer: ByteArray = EMPTY_BYTE_ARRAY,
    var time: Long = 0L,
    var uin: Long = 0L,
    var msgCookie: ByteArray = EMPTY_BYTE_ARRAY,
    var type: String = "",
    var source: Int = SourceApp.MQQ,
    var merge: Boolean = false,
    var hash: Int = 0,
    var uid: Long = 0L,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.createByteArray() ?: EMPTY_BYTE_ARRAY,
        parcel.readLong(),
        parcel.readLong(),
        parcel.createByteArray() ?: EMPTY_BYTE_ARRAY,
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readInt(),
        parcel.readLong(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (from) 1 else 0)
        parcel.writeString(cmd)
        parcel.writeInt(seq)
        parcel.writeByteArray(buffer)
        parcel.writeLong(time)
        parcel.writeLong(uin)
        parcel.writeByteArray(msgCookie)
        parcel.writeString(type)
        parcel.writeInt(source)
        parcel.writeByte(if (merge) 1 else 0)
        parcel.writeInt(hash)
        parcel.writeLong(uid)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CapturePacket> {
        override fun createFromParcel(parcel: Parcel): CapturePacket = CapturePacket(parcel)
        override fun newArray(size: Int): Array<CapturePacket?> = arrayOfNulls(size)
    }
}

/**
 * 同一个 seq 的收 / 发两条包合并成的「一次交互」。
 *
 * QQ 的 SSO 用 seq 关联请求与响应，同 seq 的发包和回包天然是一对，
 * 列表里合并成一张卡片比两条独立记录好读得多。
 */
data class CaptureExchange(
    val seq: Int,
    val cmd: String,
    val request: CapturePacket?,
    val response: CapturePacket?,
) {
    val latestTime: Long = maxOf(request?.time ?: 0L, response?.time ?: 0L)
    val uid: Long = request?.uid ?: response?.uid ?: seq.toLong()
    val requestSize: Int = request?.buffer?.size ?: 0
    val responseSize: Int = response?.buffer?.size ?: 0
    val uin: Long = request?.uin ?: response?.uin ?: 0L
    val source: Int = request?.source ?: response?.source ?: SourceApp.MQQ

    /** 响应耗时（毫秒）；缺一侧时为 null。 */
    val costMs: Long? =
        if (request != null && response != null) (response.time - request.time).coerceAtLeast(0)
        else null

    val hasError: Boolean = response == null && request != null
}

object SourceApp {
    const val MQQ = 0
    const val TIM = 1
    const val QQLITE = 2
    const val QIDIAN = 3
}
