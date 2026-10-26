package dev.weq.qqhook.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.weq.qqhook.app.model.CapturePacket
import dev.weq.qqhook.app.ui.components.MAX_TREE_ROWS
import dev.weq.qqhook.app.ui.components.TreeRow
import dev.weq.qqhook.app.ui.components.TreeRowView
import dev.weq.qqhook.app.ui.components.buildTreeRows
import dev.weq.qqhook.app.ui.components.formatSize
import dev.weq.qqhook.app.ui.components.toHexLines
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors
import dev.weq.qqhook.app.ui.theme.MonoStyle
import dev.weq.qqhook.app.util.ExportHelper
import dev.weq.qqhook.app.util.PacketJson
import dev.weq.qqhook.helper.parser.PacketAnalyzer

/**
 * 单次交互详情：发送 / 接收对照，结构化解析 + hex 原文。
 *
 * 解析是自动的：先判断协议类型（protobuf / JCE / 文本），再递归展开嵌套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    request: CapturePacket?,
    response: CapturePacket?,
    seq: Int,
    onBack: () -> Unit,
) {
    val c = LocalCaptureColors.current
    val context = LocalContext.current
    var side by rememberSaveable { mutableIntStateOf(if (request != null) 0 else 1) }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0=结构 1=HEX

    val current = if (side == 0) request else response
    val cmd = request?.cmd?.takeIf { it.isNotBlank() } ?: response?.cmd.orEmpty()

    val analysis = remember(current) {
        current?.let { PacketAnalyzer.analyze(it.buffer) }
    }

    // 折叠状态以路径为 key；换包时重置。默认全展开。
    var collapsed by remember(current) { mutableStateOf<Set<String>>(emptySet()) }
    val rows = remember(analysis, collapsed) {
        analysis?.nodes?.let { buildTreeRows(it, collapsed) }.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            cmd.ifBlank { "未知 CMD" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "seq $seq",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = current?.let { PacketJson.encode(it, analysis) }.orEmpty()
                        ExportHelper.copyToClipboard(context, text, cmd)
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制 JSON")
                    }
                    IconButton(onClick = {
                        val text = current?.let { PacketJson.encode(it, analysis) }.orEmpty()
                        ExportHelper.shareText(context, text, cmd)
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "分享 JSON")
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
                .padding(horizontal = 12.dp),
        ) {
            // 发送 / 接收切换
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = side == 0,
                    onClick = { side = 0 },
                    enabled = request != null,
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text("发送${if (request != null) " · ${formatSize(request.buffer.size)}" else ""}")
                }
                SegmentedButton(
                    selected = side == 1,
                    onClick = { side = 1 },
                    enabled = response != null,
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text("接收${if (response != null) " · ${formatSize(response.buffer.size)}" else ""}")
                }
            }

            Spacer(Modifier.height(10.dp))

            if (current == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (side == 0) "没有对应的发送包" else "没有对应的接收包",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // 协议信息条
            analysis?.let { a ->
                ProtocolBanner(
                    kind = a.kind.name,
                    confidence = a.confidence,
                    prefixLen = a.prefixLen,
                    bodySize = a.bodySize,
                )
                Spacer(Modifier.height(8.dp))
            }

            // 结构 / HEX 切换
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("结构") }
                SegmentedButton(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("HEX") }
            }

            Spacer(Modifier.height(8.dp))

            if (mode == 0 && analysis != null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(c.codeBackground, RoundedCornerShape(10.dp)),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    when {
                        analysis.nodes != null -> {
                            item(key = "json-header") {
                                JsonHeader(
                                    fieldCount = rows.count { it is TreeRow.Field },
                                    bodySize = analysis.bodySize,
                                    onCopy = {
                                        ExportHelper.copyToClipboard(
                                            context,
                                            PacketJson.encode(current, analysis),
                                            cmd,
                                        )
                                    },
                                )
                            }
                            items(rows, key = { it.path }) { row ->
                                TreeRowView(row) { path ->
                                    collapsed = if (path in collapsed) collapsed - path
                                    else collapsed + path
                                }
                            }
                            if (rows.size >= MAX_TREE_ROWS) item(key = "json-truncated") {
                                Text(
                                    "内容过大，已截断显示；完整内容请复制 JSON",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        // TRPC 信封：只展示服务名，剩余二进制体提示看 HEX
                        analysis.kind == PacketAnalyzer.Kind.TRPC -> item {
                            Text(
                                "TRPC 信封\n\n${analysis.text ?: ""}\n\n" +
                                    "包体前段是协议元数据与二进制参数，完整内容请看 HEX 视图。",
                                style = MonoStyle,
                                color = c.onCodeBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            )
                        }

                        analysis.text != null -> item {
                            Text(
                                analysis.text,
                                style = MonoStyle.copy(fontSize = 12.sp),
                                color = c.onCodeBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            )
                        }

                        else -> item {
                            Text(
                                "无法解析为结构化数据，请查看 HEX",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            } else {
                HexList(current.buffer)
            }
        }
    }
}

@Composable
private fun ProtocolBanner(kind: String, confidence: Double, prefixLen: Int, bodySize: Int) {
    val c = LocalCaptureColors.current
    val color = when (kind) {
        "PROTOBUF" -> c.proto
        "JCE" -> c.jce
        "TRPC" -> c.send
        "TEXT" -> c.text
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            kind,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "置信度 ${(confidence * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (prefixLen > 0) "长度前缀 $prefixLen B · 体 $bodySize B" else "体 $bodySize B",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** 结构视图头部：字段数 / 体积 + 复制 JSON。 */
@Composable
private fun JsonHeader(fieldCount: Int, bodySize: Int, onCopy: () -> Unit) {
    val c = LocalCaptureColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$fieldCount 个字段 · 体 $bodySize B",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Rounded.DataObject,
                contentDescription = "复制 JSON",
                tint = c.proto,
            )
        }
    }
}

/**
 * HEX 视图。
 *
 * 每行一个独立 `Text`，整列共享同一个横向 `ScrollState` —— 之前整段塞进一个
 * `Text`，窄屏会自动折行，把 16 字节一行的对齐彻底打乱。
 * 用 `items()` 而不是单个 item，保证大包也能按需组合。
 */
@Composable
private fun HexList(buffer: ByteArray) {
    val c = LocalCaptureColors.current
    val lines = remember(buffer) { buffer.toHexLines() }
    val scroll = rememberScrollState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.codeBackground, RoundedCornerShape(10.dp)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        items(lines, key = { it.offset }) { line ->
            Text(
                text = line.text,
                style = MonoStyle,
                color = c.onCodeBackground,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(scroll)
                    .padding(horizontal = 12.dp),
            )
        }
    }
}
