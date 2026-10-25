package dev.weq.qqhook.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.weq.qqhook.BuildConfig
import dev.weq.qqhook.app.FloatingWindowService
import dev.weq.qqhook.app.model.CaptureRepository
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors
import moe.ore.xposed.common.ModeleStatus

/** 设置页：模块状态、抓包统计、关于。推送功能已移除，导出走列表多选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalCaptureColors.current
    val moduleActive = ModeleStatus.isModuleActivated()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(
                icon = Icons.Rounded.Layers,
                title = "模块状态",
                rows = listOf(
                    "激活状态" to if (moduleActive) "已激活" else "未激活",
                    "抓包状态" to if (CaptureRepository.isCatchEnabled) "运行中" else "已暂停",
                    "当前记录" to "${CaptureRepository.packets.size} 个包",
                ),
                accent = moduleActive,
            )

            SettingsCard(
                icon = Icons.Rounded.Code,
                title = "解析",
                rows = listOf(
                    "协议识别" to "自动（protobuf / JCE）",
                    "嵌套展开" to "自动递归",
                    "长度前缀" to "自动检测",
                ),
            )

            FloatingWindowCard()

            SettingsCard(
                icon = Icons.Rounded.Info,
                title = "关于",
                rows = listOf(
                    "版本" to BuildConfig.VERSION_NAME,
                    "抓包范围" to "仅 SSO 收发",
                    "导出" to "列表长按多选 → 下载图标",
                ),
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 悬浮窗开关：需要 SYSTEM_ALERT_WINDOW 权限，未授权时引导去设置页。 */
@Composable
private fun FloatingWindowCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val c = LocalCaptureColors.current

    // 真实运行状态由 Service 维护
    var running by remember { mutableStateOf(FloatingWindowService.isRunning) }
    // 悬浮窗权限：从设置页返回时要重新读
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                running = FloatingWindowService.isRunning
                hasPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.cardBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PictureInPictureAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "悬浮控制条",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (hasPermission) "在 QQ 上方显示开始 / 暂停按钮"
                    else "需要「显示在其他应用上层」权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasPermission) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            Switch(
                checked = running,
                onCheckedChange = { want ->
                    if (!want) {
                        FloatingWindowService.stop(context)
                        running = false
                        return@Switch
                    }
                    if (Settings.canDrawOverlays(context)) {
                        FloatingWindowService.start(context)
                        running = true
                    } else {
                        // 跳去授权页；回来后 onResume 会刷新 hasPermission
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val ok = runCatching { context.startActivity(intent) }.isSuccess
                        if (!ok) {
                            Toast.makeText(context, "无法打开权限设置页", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    rows: List<Pair<String, String>>,
    accent: Boolean? = null,
) {
    val c = LocalCaptureColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.cardBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            rows.forEachIndexed { index, (k, v) ->
                if (index > 0) Spacer(Modifier.height(7.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        v,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            accent == true && k == "激活状态" -> c.recv
                            accent == false && k == "激活状态" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
