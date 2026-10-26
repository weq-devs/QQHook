package dev.weq.qqhook.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.weq.qqhook.app.ui.theme.CaptureColors
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors
import dev.weq.qqhook.app.ui.theme.MonoStyle
import dev.weq.qqhook.helper.parser.RvJson
import dev.weq.qqhook.helper.parser.RvJsonValue
import dev.weq.qqhook.helper.parser.RvNode
import dev.weq.qqhook.helper.parser.RvValue

/**
 * `{tag: value}` 折叠树。
 *
 * 展示形态对齐 WeQ 的「简洁」模式：只显示 tag 与值，嵌套默认展开，重复 tag
 * 合并为数组，bytes 按「嵌套 > 文本 > 0x hex」展示。
 *
 * 树先**扁平化成行列表**再交给 LazyColumn —— 递归 Composable 只能塞进单个
 * item，会失去虚拟化；而且折叠状态在列表回收时容易错乱。
 */
sealed interface TreeRow {
    /** LazyColumn key，同时也是折叠状态的 key。 */
    val path: String
    val depth: Int

    /** 单行字段：`"1": 42,` */
    data class Field(
        override val path: String,
        override val depth: Int,
        val key: String?,
        val value: RvJsonValue,
        val comma: Boolean,
    ) : TreeRow

    /** 容器的开始行：`"1": {` 或折叠态 `"1": {…}`。 */
    data class Open(
        override val path: String,
        override val depth: Int,
        val key: String?,
        val bracket: String,
        val summary: String,
        val comma: Boolean,
        val collapsed: Boolean,
    ) : TreeRow

    /** 容器的结束行：`},` */
    data class Close(
        override val path: String,
        override val depth: Int,
        val bracket: String,
        val comma: Boolean,
    ) : TreeRow
}

/** 行数上限：防止超大包把主线程卡死。 */
const val MAX_TREE_ROWS = 20_000
private const val MAX_ROWS = MAX_TREE_ROWS

/**
 * 把 AST 扁平化成行列表。
 *
 * @param collapsed 折叠的容器路径集合；不在其中即为展开（默认全展开）
 */
fun buildTreeRows(nodes: List<RvNode>, collapsed: Set<String>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    flattenObject(nodes, "", 0, out, collapsed)
    return out
}

private fun flattenObject(
    nodes: List<RvNode>,
    parentPath: String,
    depth: Int,
    out: MutableList<TreeRow>,
    collapsed: Set<String>,
) {
    if (out.size > MAX_ROWS) return
    val groups = RvJson.group(nodes)

    groups.forEachIndexed { i, group ->
        val path = if (parentPath.isEmpty()) group.tag.toString() else "$parentPath.${group.tag}"
        val key = "\"${group.tag}\":"
        val comma = i < groups.lastIndex

        if (group.values.size > 1) {
            // 重复 tag → 数组
            emitContainer(
                path = path,
                depth = depth,
                key = key,
                bracket = "[",
                count = group.values.size,
                comma = comma,
                out = out,
                collapsed = collapsed,
                emitChildren = { childDepth ->
                    group.values.forEachIndexed { j, v ->
                        emitValue(
                            path = "$path[$j]",
                            depth = childDepth,
                            key = null,
                            value = v,
                            comma = j < group.values.lastIndex,
                            out = out,
                            collapsed = collapsed,
                        )
                    }
                },
            )
        } else {
            emitValue(
                path = path,
                depth = depth,
                key = key,
                value = group.values[0],
                comma = comma,
                out = out,
                collapsed = collapsed,
            )
        }
    }
}

private fun emitValue(
    path: String,
    depth: Int,
    key: String?,
    value: RvValue,
    comma: Boolean,
    out: MutableList<TreeRow>,
    collapsed: Set<String>,
) {
    if (out.size > MAX_ROWS) return
    when (value) {
        is RvValue.Obj -> emitObjectLike(path, depth, key, value.fields, comma, out, collapsed)
        is RvValue.MapValue -> emitMap(path, depth, key, value, comma, out, collapsed)
        is RvValue.ListValue -> emitContainer(
            path = path,
            depth = depth,
            key = key,
            bracket = "[",
            count = value.items.size,
            comma = comma,
            out = out,
            collapsed = collapsed,
            emitChildren = { childDepth ->
                value.items.forEachIndexed { j, item ->
                    emitValue(
                        path = "$path[$j]",
                        depth = childDepth,
                        key = null,
                        value = item.value,
                        comma = j < value.items.lastIndex,
                        out = out,
                        collapsed = collapsed,
                    )
                }
            },
        )

        is RvValue.Bytes -> if (value.nested != null) {
            emitObjectLike(path, depth, key, value.nested, comma, out, collapsed)
        } else {
            out += TreeRow.Field(path, depth, key, RvJson.valueToJson(value), comma)
        }

        else -> out += TreeRow.Field(path, depth, key, RvJson.valueToJson(value), comma)
    }
}

private fun emitObjectLike(
    path: String,
    depth: Int,
    key: String?,
    fields: List<RvNode>,
    comma: Boolean,
    out: MutableList<TreeRow>,
    collapsed: Set<String>,
) {
    emitContainer(
        path = path,
        depth = depth,
        key = key,
        bracket = "{",
        count = RvJson.group(fields).size,
        comma = comma,
        out = out,
        collapsed = collapsed,
        emitChildren = { childDepth -> flattenObject(fields, path, childDepth, out, collapsed) },
    )
}

private fun emitMap(
    path: String,
    depth: Int,
    key: String?,
    value: RvValue.MapValue,
    comma: Boolean,
    out: MutableList<TreeRow>,
    collapsed: Set<String>,
) {
    emitContainer(
        path = path,
        depth = depth,
        key = key,
        bracket = "{",
        count = value.entries.size,
        comma = comma,
        out = out,
        collapsed = collapsed,
        emitChildren = { childDepth ->
            value.entries.forEachIndexed { j, e ->
                emitValue(
                    path = "$path{$j}",
                    depth = childDepth,
                    key = "\"${RvJson.keyToString(e.key)}\":",
                    value = e.value.value,
                    comma = j < value.entries.lastIndex,
                    out = out,
                    collapsed = collapsed,
                )
            }
        },
    )
}

private fun emitContainer(
    path: String,
    depth: Int,
    key: String?,
    bracket: String,
    count: Int,
    comma: Boolean,
    out: MutableList<TreeRow>,
    collapsed: Set<String>,
    emitChildren: (childDepth: Int) -> Unit,
) {
    if (out.size > MAX_ROWS) return
    val closeBracket = if (bracket == "{") "}" else "]"
    val isCollapsed = path in collapsed

    if (isCollapsed) {
        // 折叠摘要自带括号，渲染时整段直接输出
        out += TreeRow.Open(
            path = path,
            depth = depth,
            key = key,
            bracket = bracket,
            summary = if (bracket == "[") "[…$count]" else "{…$count}",
            comma = comma,
            collapsed = true,
        )
        return
    }

    out += TreeRow.Open(
        path = path,
        depth = depth,
        key = key,
        bracket = bracket,
        summary = "",
        comma = false,
        collapsed = false,
    )
    emitChildren(depth + 1)
    out += TreeRow.Close("$path#close", depth, closeBracket, comma)
}

// ---------------------------------------------------------------- 渲染

/** 一行树节点。容器行可点击折叠 / 展开。 */
@Composable
fun TreeRowView(
    row: TreeRow,
    onToggle: (String) -> Unit,
) {
    val c = LocalCaptureColors.current
    val open = row as? TreeRow.Open

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.clickable { onToggle(row.path) } else Modifier)
            .padding(start = (row.depth * 14).dp, end = 12.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 折叠图标占位，保证同层文本左对齐
        if (open != null) {
            Icon(
                if (open.collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,
                contentDescription = if (open.collapsed) "展开" else "收起",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }

        Text(
            text = row.toAnnotated(c),
            style = MonoStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun TreeRow.toAnnotated(c: CaptureColors): AnnotatedString = buildAnnotatedString {
    when (this@toAnnotated) {
        is TreeRow.Field -> {
            key?.let { appendStyled(it, c.proto, bold = true) }
            if (key != null) append(" ")
            appendJsonValue(value, c)
            if (comma) appendStyled(",", c.readContent)
        }

        is TreeRow.Open -> {
            key?.let { appendStyled(it, c.proto, bold = true) }
            if (key != null) append(" ")
            if (collapsed) {
                // 折叠态整段用 jce 色，摘要自带括号
                appendStyled(summary, c.jce)
                if (comma) appendStyled(",", c.readContent)
            } else {
                appendStyled(bracket, c.readContent)
            }
        }

        is TreeRow.Close -> {
            appendStyled(bracket, c.readContent)
            if (comma) appendStyled(",", c.readContent)
        }
    }
}

private fun AnnotatedString.Builder.appendJsonValue(value: RvJsonValue, c: CaptureColors) {
    when (value) {
        is RvJsonValue.Num -> appendStyled(value.literal, c.send)
        is RvJsonValue.Bool -> appendStyled(if (value.value) "true" else "false", c.send)
        is RvJsonValue.Str -> {
            val text = value.text
            val color = when {
                text.startsWith("0x") -> c.jce
                else -> c.text
            }
            appendStyled("\"" + text.ellipsize(160) + "\"", color)
        }

        is RvJsonValue.Arr -> {
            // 只在重复 tag 内联、或全标量短数组时出现
            appendStyled("[", c.readContent)
            value.items.forEachIndexed { i, item ->
                if (i > 0) appendStyled(", ", c.readContent)
                appendJsonValue(item, c)
            }
            appendStyled("]", c.readContent)
        }

        is RvJsonValue.Obj -> {
            appendStyled("{", c.readContent)
            value.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) appendStyled(", ", c.readContent)
                appendStyled("\"$k\":", c.proto)
                append(" ")
                appendJsonValue(v, c)
            }
            appendStyled("}", c.readContent)
        }
    }
}

private fun AnnotatedString.Builder.appendStyled(
    text: String,
    color: Color,
    bold: Boolean = false,
) {
    pushStyle(
        SpanStyle(
            color = color,
            fontWeight = if (bold) FontWeight.SemiBold else null,
        ),
    )
    append(text)
    pop()
}

private fun String.ellipsize(max: Int): String =
    if (length <= max) this else take(max) + "…"

// ---------------------------------------------------------------- HEX

/** hex dump 的一行：`00000000  08 96 01 ...  |..|` */
data class HexLine(val offset: Int, val text: String)

/** 16 字节一行的 hex dump。 */
fun ByteArray.toHexLines(): List<HexLine> {
    if (isEmpty()) return emptyList()
    val lines = ArrayList<HexLine>(size / 16 + 1)
    var i = 0
    while (i < size) {
        val end = minOf(i + 16, size)
        val sb = StringBuilder(80)
        sb.append("%08X  ".format(i))
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
        sb.append('|')
        lines += HexLine(i, sb.toString())
        i += 16
    }
    return lines
}
