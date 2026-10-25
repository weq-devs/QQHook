package dev.weq.qqhook.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val RevealWidth = 96.dp
private val TriggerPx = 40.dp

/**
 * 左滑露出「删除」按钮。
 *
 * 交互：向左拖动超过一定距离松手 → 停在展开位；再向右拖或点内容区 → 收回。
 * 展开状态下点删除才真正删除，避免误触。
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { RevealWidth.toPx() }
    val triggerPx = with(density) { TriggerPx.toPx() }

    var offset by remember { mutableFloatStateOf(0f) }
    val animated by animateFloatAsState(offset, label = "swipe")
    val isRevealed = offset <= -revealPx / 2

    Box(modifier = modifier.fillMaxWidth()) {
        // 底层删除区
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable(enabled = isRevealed, onClick = onDelete)
                .padding(end = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "删除",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 5.dp),
            )
        }

        // 上层内容
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = animated }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            offset = if (offset <= -triggerPx) -revealPx else 0f
                        },
                        onDragCancel = {
                            offset = if (offset <= -triggerPx) -revealPx else 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        offset = (offset + dragAmount).coerceIn(-revealPx, 0f)
                    }
                },
        ) {
            content()
        }
    }
}
