package dev.weq.qqhook.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import dev.weq.qqhook.app.ui.components.formatSize
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors
import dev.weq.qqhook.app.ui.theme.MonoStyle
import dev.weq.qqhook.app.util.ExportHelper
import dev.weq.qqhook.helper.parser.AutoParser
import dev.weq.qqhook.helper.parser.JceLite
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
                        val text = current?.let { CaptureStoreText.single(it) }.orEmpty()
                        ExportHelper.copyToClipboard(context, text, cmd)
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = {
                        val text = current?.let { CaptureStoreText.single(it) }.orEmpty()
                        ExportHelper.shareText(context, text, cmd)
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "分享")
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mode == 0 && analysis != null) {
                    when {
                        analysis.protobuf != null && analysis.kind == PacketAnalyzer.Kind.PROTOBUF ->
                            protobufFields(analysis.protobuf.fields)

                        analysis.jce != null && analysis.kind == PacketAnalyzer.Kind.JCE ->
                            jceFields(analysis.jce.fields)

                        analysis.text != null ->
                            item {
                                TextCard(analysis.text, c.codeBackground, c.onCodeBackground)
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
                } else {
                    item {
                        HexCard(current.buffer)
                    }
                }
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

// ---------------------------------------------------------------- 结构化渲染

private fun androidx.compose.foundation.lazy.LazyListScope.protobufFields(fields: List<AutoParser.Field>) {
    fields.forEach { f ->
        item(key = "pb-${f.offset}") {
            FieldRow(
                tag = f.number.toString(),
                type = f.typeName,
                value = f.value,
                offset = f.offset,
                length = f.length,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.jceFields(fields: List<JceLite.Field>) {
    fields.forEach { f ->
        item(key = "jce-${f.offset}") {
            FieldRow(
                tag = f.tag.toString(),
                type = f.typeName,
                value = f.value,
                offset = f.offset,
                length = f.length,
            )
        }
    }
}

/**
 * 一个字段：左边 tag + 类型，右边值。嵌套结构可点开递归展开。
 */
@Composable
private fun FieldRow(
    tag: String,
    type: String,
    value: Any,
    offset: Int,
    length: Int,
) {
    val c = LocalCaptureColors.current
    var expanded by remember { mutableStateOf(false) }

    val nested = when (value) {
        is AutoParser.Value.Message -> value.fields
        is JceLite.Value.Struct -> value.fields
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(enabled = nested != null) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(34.dp),
            )
            Text(
                type,
                style = MaterialTheme.typography.labelSmall,
                color = typeColor(type, c),
                modifier = Modifier.width(72.dp),
            )

            when (value) {
                is AutoParser.Value.Message, is JceLite.Value.Struct -> {
                    Text(
                        "{ ${nested?.size ?: 0} 字段 }",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is AutoParser.Value.Text -> Text(
                    value.text.ellipsize(),
                    style = MonoStyle,
                    color = c.text,
                )

                is JceLite.Value.Text -> Text(
                    value.text.ellipsize(),
                    style = MonoStyle,
                    color = c.text,
                )

                is AutoParser.Value.Varint -> Column {
                    Text("${value.value}", style = MonoStyle)
                    if (value.zigzag != value.value) {
                        Text(
                            "zigzag ${value.zigzag}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is AutoParser.Value.Fixed -> Text(value.hex, style = MonoStyle)
                is AutoParser.Value.Bytes -> Text(
                    "${value.size} B ${value.hex.take(48)}${if (value.hex.length > 48) "…" else ""}",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is JceLite.Value.Num -> Text("${value.value}", style = MonoStyle)
                is JceLite.Value.Real -> Text("${value.value}", style = MonoStyle)
                is JceLite.Value.Bytes -> Text(
                    "${value.size} B ${value.hex.take(48)}",
                    style = MonoStyle,
                )

                is JceLite.Value.ListValue -> Text(
                    "[${value.items.size}] ${value.elementType}",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is JceLite.Value.MapValue -> Text(
                    "{${value.entries.size} 项}",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Text(value.toString(), style = MonoStyle)
            }

            if (nested != null) {
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (nested != null) {
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 14.dp, top = 6.dp)) {
                    when (val n = value) {
                        is AutoParser.Value.Message -> n.fields.forEach {
                            FieldRow(it.number.toString(), it.typeName, it.value, it.offset, it.length)
                        }

                        is JceLite.Value.Struct -> n.fields.forEach {
                            FieldRow(it.tag.toString(), it.typeName, it.value, it.offset, it.length)
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}

private fun typeColor(type: String, c: dev.weq.qqhook.app.ui.theme.CaptureColors) = when (type) {
    "string" -> c.text
    "message", "struct" -> c.proto
    "bytes" -> c.jce
    else -> c.readContent
}

private fun String.ellipsize(max: Int = 120): String =
    if (length <= max) this else take(max) + "…"

@Composable
private fun TextCard(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MonoStyle.copy(fontSize = 12.sp),
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}

@Composable
private fun HexCard(buffer: ByteArray) {
    val c = LocalCaptureColors.current
    val hex = remember(buffer) { buffer.toHexDump() }
    Text(
        text = hex,
        style = MonoStyle,
        color = c.onCodeBackground,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.codeBackground, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}

/** 16 字节一行的 hex dump，带 offset 和 ASCII。 */
private fun ByteArray.toHexDump(): String {
    val sb = StringBuilder()
    var i = 0
    while (i < size) {
        sb.append("%08X  ".format(i))
        val end = minOf(i + 16, size)
        for (j in i until end) {
            sb.append("%02X ".format(this[j]))
            if (j == i + 7) sb.append(' ')
        }
        for (j in end until i + 16) {
            sb.append("   ")
            if (j == i + 7) sb.append(' ')
        }
        sb.append(" |")
        for (j in i until end) {
            val b = this[j].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        sb.append("|\n")
        i += 16
    }
    return sb.toString()
}

/** 单条包的可分享文本。 */
private object CaptureStoreText {
    fun single(p: CapturePacket): String = buildString {
        appendLine(if (p.from) "RECV" else "SEND")
        appendLine("cmd: ${p.cmd}")
        appendLine("seq: ${p.seq}")
        appendLine("uin: ${p.uin}")
        appendLine("time: ${p.time}")
        appendLine("size: ${p.buffer.size} B")
        appendLine()
        append(p.buffer.joinToString("") { "%02x".format(it) })
    }
}
