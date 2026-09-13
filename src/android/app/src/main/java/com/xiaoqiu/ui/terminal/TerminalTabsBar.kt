package com.xiaoqiu.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoqiu.ui.terminal.TerminalSessionManager.Tab

/**
 * [小丘] 终端多标签栏 —— v1 Terminal.vue 标签条复刻：
 * 横滚标签 · 状态点（活绿/死灰/选中山绿）· 长按菜单（重命名/重启/关闭）· ＋新建。
 * 重命名走内联输入框（v1 同款：点「重命名」原地变输入框，回车确认）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalTabsBar(
    manager: TerminalSessionManager,
    modifier: Modifier = Modifier,
) {
    val active = manager.active
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1C))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        manager.tabs.forEach { tab ->
            TabChip(
                tab = tab,
                isActive = tab.id == active?.id,
                isAlive = manager.isAlive(tab),
                onActivate = { manager.activate(tab.id) },
                onRename = { manager.rename(tab.id, it) },
                onRestart = { manager.restartTab(tab.id) },
                onClose = { manager.killTab(tab.id) },
            )
        }
        IconButton(onClick = { manager.createTab() }) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "新建终端",
                tint = Color(0xFF9CBFA8),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(2.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    tab: Tab,
    isActive: Boolean,
    isAlive: Boolean,
    onActivate: () -> Unit,
    onRename: (String) -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    Box {
        Row(
            modifier = Modifier
                .padding(vertical = 5.dp, horizontal = 2.dp)
                .background(
                    color = if (isActive) Color(0xFF2E4A38) else Color(0xFF2A2A2E),
                    shape = RoundedCornerShape(8.dp),
                )
                .combinedClickable(
                    onClick = { if (!renaming) onActivate() },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 状态点：活=山绿亮 / 死=灰
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = when {
                            isAlive -> Color(0xFF6FBF8A)
                            else -> Color(0xFF666666)
                        },
                        shape = CircleShape,
                    ),
            )
            if (renaming) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            onRename(renameText)
                            renaming = false
                        },
                    ),
                    modifier = Modifier
                        .width(96.dp)
                        .heightIn(min = 36.dp),
                )
                androidx.compose.runtime.LaunchedEffect(renaming) {
                    if (renaming && renameText.isEmpty()) renameText = tab.title
                }
            } else {
                Text(
                    text = tab.title,
                    color = if (isActive) Color(0xFFE7F0EA) else Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 88.dp),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)) },
                onClick = {
                    menuOpen = false
                    renameText = tab.title
                    renaming = true
                },
            )
            DropdownMenuItem(
                text = { Text("重启（清屏重开）") },
                leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)) },
                onClick = {
                    menuOpen = false
                    onRestart()
                },
            )
            DropdownMenuItem(
                text = { Text("关闭") },
                leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) },
                onClick = {
                    menuOpen = false
                    onClose()
                },
            )
        }
    }
}
