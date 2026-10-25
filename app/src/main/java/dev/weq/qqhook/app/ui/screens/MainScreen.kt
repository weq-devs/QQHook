package dev.weq.qqhook.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.weq.qqhook.app.model.CaptureExchange
import dev.weq.qqhook.app.model.CaptureRepository
import dev.weq.qqhook.app.model.ExchangeGrouper
import dev.weq.qqhook.app.ui.components.ExchangeCard
import dev.weq.qqhook.app.ui.components.SwipeToDelete
import dev.weq.qqhook.app.ui.theme.LocalCaptureColors
import dev.weq.qqhook.app.util.ExportHelper

/**
 * 抓包主界面。
 *
 * 列表按 seq 合并收发；长按进入多选；左滑删除当前列表项（不影响历史）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenDetail: (Int) -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateSettings: () -> Unit,
) {
    val c = LocalCaptureColors.current
    val context = LocalContext.current

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    // 用 snapshot state 的 set，增删会触发重组
    val selectedSeqs = remember { mutableStateListOf<Int>() }

    val packets = CaptureRepository.packets
    val isCatching = CaptureRepository.isCatchEnabled
    val browsingHistory = CaptureRepository.isBrowsingHistory

    // 按 seq 合并 + 搜索过滤。
    // 直接读 snapshot list：列表增删会自动触发重组，不需要额外的 generation 依赖。
    val exchanges = remember(packets.size, packets.firstOrNull()?.uid, packets.lastOrNull()?.uid, searchText) {
        val grouped = ExchangeGrouper.group(packets)
        val q = searchText.trim()
        if (q.isBlank()) grouped
        else grouped.filter {
            it.cmd.contains(q, ignoreCase = true) ||
                it.seq.toString().contains(q) ||
                it.uin.toString().contains(q)
        }
    }

    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    var previousCount by remember { mutableIntStateOf(0) }

    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(isAtTop) { if (isAtTop) followLatest = true }
    LaunchedEffect(exchanges.size, searchText) {
        if (followLatest && exchanges.size > previousCount && searchText.isBlank()) {
            listState.animateScrollToItem(0)
        }
        previousCount = exchanges.size
    }

    // 列表是倒序展示（新在最上），所以先反转
    val displayList = remember(exchanges) { exchanges.asReversed() }

    fun toggleSelect(seq: Int) {
        if (!selectedSeqs.remove(seq)) selectedSeqs.add(seq)
        if (selectedSeqs.isEmpty()) selectionMode = false
    }

    fun exitSelection() {
        selectionMode = false
        selectedSeqs.clear()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selectedSeqs.size,
                    allSelected = selectedSeqs.size == displayList.size,
                    onSelectAll = {
                        if (selectedSeqs.size == displayList.size) selectedSeqs.clear()
                        else {
                            selectedSeqs.clear()
                            displayList.forEach { selectedSeqs.add(it.seq) }
                        }
                        if (selectedSeqs.isEmpty()) selectionMode = false
                    },
                    onExport = {
                        val chosen = displayList.filter { it.seq in selectedSeqs }
                        ExportHelper.export(context, chosen)
                        exitSelection()
                    },
                    onDelete = {
                        val toRemove = selectedSeqs.toSet()
                        CaptureRepository.removeAll { it.seq in toRemove }
                        exitSelection()
                    },
                    onClose = { exitSelection() },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "SSO 抓包",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                when {
                                    browsingHistory -> "历史记录 · ${exchanges.size} 次交互"
                                    isCatching -> "抓包中 · ${exchanges.size} 次交互"
                                    else -> "${exchanges.size} 次交互 · 已暂停"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    browsingHistory -> c.jce
                                    isCatching -> c.recv
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchText = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = onNavigateHistory) {
                            Icon(Icons.Rounded.History, contentDescription = "历史")
                        }
                        IconButton(onClick = onNavigateSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                if (browsingHistory) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            // 退出历史浏览，回到实时抓包
                            CaptureRepository.updateCatchEnabled(true)
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                        text = { Text("回到抓包") },
                    )
                } else ExtendedFloatingActionButton(
                    onClick = { CaptureRepository.updateCatchEnabled(!isCatching) },
                    containerColor = if (isCatching)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primary,
                    contentColor = if (isCatching)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onPrimary,
                    icon = {
                        Icon(
                            if (isCatching) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                    },
                    text = { Text(if (isCatching) "暂停" else "开始") },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(
                visible = searchOpen && !selectionMode,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    placeholder = { Text("搜索 CMD / seq / uin") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = c.cardBorder,
                    ),
                )
            }

            if (displayList.isEmpty()) {
                EmptyState(searching = searchText.isNotBlank(), isCatching = isCatching)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 88.dp),
                ) {
                    items(displayList, key = { it.seq }) { exchange ->
                        val isSelected = exchange.seq in selectedSeqs
                        SwipeToDelete(
                            onDelete = {
                                // 只从当前列表移除，历史文件不动
                                CaptureRepository.removeAll { it.seq == exchange.seq }
                            },
                            enabled = !selectionMode,
                        ) {
                            ExchangeCard(
                                exchange = exchange,
                                isRead = CaptureRepository.readSeqs.contains(exchange.seq),
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        toggleSelect(exchange.seq)
                                    } else {
                                        CaptureRepository.markRead(exchange.seq)
                                        onOpenDetail(exchange.seq)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedSeqs.clear()
                                        selectedSeqs.add(exchange.seq)
                                    } else {
                                        toggleSelect(exchange.seq)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "退出多选")
            }
            Text(
                text = "已选 $count",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Rounded.SelectAll,
                    contentDescription = if (allSelected) "取消全选" else "全选",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "导出所选",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, isCatching: Boolean) {
    val c = LocalCaptureColors.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (searching) Icons.Rounded.Search else Icons.Rounded.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    searching -> "没有匹配的包"
                    isCatching -> "等待数据…"
                    else -> "已暂停"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    searching -> "换个关键词试试"
                    isCatching -> "在 QQ 里操作一下就会产生 SSO 包"
                    else -> "点右下角「开始」继续抓包"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
