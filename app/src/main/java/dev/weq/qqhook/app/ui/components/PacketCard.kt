package dev.weq.qqhook.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.weq.qqhook.app.model.CaptureExchange
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次交互（同 seq 的收发合并）的卡片。
 *
 * 视觉层次：左侧方向条 → CMD 主标题 → seq / 耗时 / 大小 → 状态点。
 * 已读的卡片整体降透明度并变灰，和未读形成明确对比。
 */
@Composable
fun ExchangeCard(
    exchange: CaptureExchange,
    isRead: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalCaptureColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

    val hasRequest = exchange.request != null
    val hasResponse = exchange.response != null
    val accentColor = when {
        hasRequest && hasResponse -> c.send
        hasRequest -> c.send
        else -> c.recv
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (isRead) 0.55f else 1f,
        label = "readAlpha",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            else -> c.cardBorder
        },
        label = "cardBorder",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (isSelected)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        else
            MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧方向条
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accentColor.copy(alpha = if (isRead) 0.4f else 1f)),
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }

                Column(modifier = Modifier.weight(1f).alpha(contentAlpha)) {
                    // 第一行：CMD
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isRead) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(c.unreadDot),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = exchange.cmd.ifBlank { "(unknown cmd)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (isRead) c.readContent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // 第二行：seq / 时间 / 耗时 / 大小
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetaText("seq ${exchange.seq}", c)
                        MetaText(timeFormat.format(Date(exchange.latestTime)), c)

                        val cost = exchange.costMs
                        if (cost != null) {
                            MetaText("${cost}ms", c)
                        }

                        if (hasRequest && hasResponse) {
                            MetaText("↑${formatSize(exchange.requestSize)} ↓${formatSize(exchange.responseSize)}", c)
                        } else if (hasRequest) {
                            MetaText("↑${formatSize(exchange.requestSize)}", c)
                        } else {
                            MetaText("↓${formatSize(exchange.responseSize)}", c)
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // 右侧方向标识
                DirectionBadge(
                    hasRequest = hasRequest,
                    hasResponse = hasResponse,
                    alpha = contentAlpha,
                )
            }
        }
    }
}

@Composable
private fun MetaText(text: String, c: dev.weq.qqhook.app.ui.theme.CaptureColors) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = c.readContent,
        maxLines = 1,
    )
}

/** 收发方向小徽标：双向箭头 / 单向上 / 单向下。 */
@Composable
private fun DirectionBadge(hasRequest: Boolean, hasResponse: Boolean, alpha: Float) {
    val c = LocalCaptureColors.current
    Row(
        modifier = Modifier.alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (hasRequest) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "发送",
                tint = c.send,
                modifier = Modifier.size(14.dp),
            )
        }
        if (hasResponse) {
            Icon(
                Icons.Rounded.ArrowDownward,
                contentDescription = "接收",
                tint = c.recv,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1fK", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1fM", bytes / (1024.0 * 1024.0))
}
