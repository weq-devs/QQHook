package dev.weq.qqhook.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors

/** 首次启动 / 模块未激活时的检查页。 */
@Composable
fun EntryCheckScreen(
    isModuleActivated: Boolean,
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    val c = LocalCaptureColors.current

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        if (isModuleActivated) c.recvContainer else MaterialTheme.colorScheme.errorContainer,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isModuleActivated) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (isModuleActivated) c.recv else MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "TXHook",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "QQ 协议抓包分析工具",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(26.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CheckRow(
                    ok = isModuleActivated,
                    title = "Xposed 模块",
                    detail = if (isModuleActivated) "已激活" else "未激活，请在 LSPosed 中启用并重启 QQ",
                )
                CheckRow(
                    ok = true,
                    title = "抓包范围",
                    detail = "仅 SSO 收发包，不含 TEA / TLV / MD5",
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "本工具仅供学习研究，请遵守当地法律法规。抓包数据可能包含个人隐私，" +
                    "请勿传播或用于非法用途。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))

            if (!isModuleActivated) {
                Button(
                    onClick = onDisagree,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("退出") }
            } else {
                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("同意并继续") }
                TextButton(onClick = onDisagree) { Text("退出") }
            }
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, detail: String) {
    val c = LocalCaptureColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (ok) c.recv else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
