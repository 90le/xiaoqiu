package com.xiaoqiu.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TerminalTabsBar(
    manager: TerminalSessionManager,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    val active = manager.active
    var drawerOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Color(0xFF16161B))
            .drawBehind {
                // 底部分隔线：标签条与终端画布的硬边界（v1 tabs 底线）
                drawRect(
                    color = Color(0xFF4A4A55),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, 2f),
                )
            },
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ☰ 会话管理抽屉（v1 同款入口）
        Box(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A3A44))
                .border(1.dp, Color(0xFF55555F), RoundedCornerShape(10.dp))
                .clickable { drawerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("☰", color = Color(0xFF9CBFA8), fontSize = 18.sp)
        }
        // ⌘ 命令面板（v1 commands 复用：▶跑/✎预填/编辑/二次确认删）
        Box(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 2.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A3A44))
                .border(1.dp, Color(0xFF55555F), RoundedCornerShape(10.dp))
                .clickable { paletteOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("⌘", color = Color(0xFF9CBFA8), fontSize = 18.sp)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
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
        }
        // ＋ 新建（v1 常驻右缘）
        Box(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 2.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF4CAF7D))
                .border(1.dp, Color(0xFF6FCC9A), RoundedCornerShape(10.dp))
                .clickable { manager.createTab() },
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", color = Color.White, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        // 🧹 清屏
        Box(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 2.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A3A44))
                .border(1.dp, Color(0xFF55555F), RoundedCornerShape(10.dp))
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Text("🧹", fontSize = 16.sp)
        }
        // ✕ 退出终端
        Box(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 6.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A3A44))
                .border(1.dp, Color(0xFF55555F), RoundedCornerShape(10.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color(0xFFE0E0E0), fontSize = 16.sp)
        }
    }

    // ── 会话管理抽屉（ModalBottomSheet：全部会话/新建/关闭）──
    } // Column 结束（分隔线容器）
    if (drawerOpen) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { drawerOpen = false },
            containerColor = Color(0xFF232326),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "终端会话管理",
                    color = Color(0xFFE7F0EA),
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                manager.tabs.forEach { tab ->
                    val alive = manager.isAlive(tab)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (tab.id == active?.id) Color(0xFF2E4A38)
                                else Color(0xFF2A2A2E),
                            )
                            .clickable {
                                manager.activate(tab.id)
                                drawerOpen = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (alive) Color(0xFF6FBF8A) else Color(0xFF666666),
                                    CircleShape,
                                ),
                        )
                        Text(
                            "  " + tab.title,
                            color = if (tab.id == active?.id) Color(0xFFE7F0EA) else Color(0xFFBBBBBB),
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (alive) "运行中" else "已停止",
                            color = if (alive) Color(0xFF6FBF8A) else Color(0xFF888888),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "重启",
                            color = Color(0xFF9CBFA8),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF33333A))
                                .clickable {
                                    manager.restartTab(tab.id)
                                    drawerOpen = false
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "关闭",
                            color = Color(0xFFC24B3C),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF33333A))
                                .clickable {
                                    manager.killTab(tab.id)
                                    if (manager.tabs.isEmpty()) manager.createTab()
                                    drawerOpen = false
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 新建 + 关闭全部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2E4A38))
                            .clickable {
                                manager.createTab()
                                drawerOpen = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋ 新建会话", color = Color(0xFFE7F0EA), fontSize = 14.sp) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF33333A))
                            .clickable {
                                manager.tabs.toList().forEach { manager.killTab(it.id) }
                                manager.createTab()
                                drawerOpen = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("全部关闭并新建", color = Color(0xFFBBBBBB), fontSize = 14.sp) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }


    // ── ⌘ 命令面板 ──
    if (paletteOpen) {
        CommandPaletteSheet(
            manager = manager,
            onDismiss = { paletteOpen = false },
        )
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .padding(top = 6.dp, bottom = 2.dp)
                .background(
                    color = if (isActive) Color(0xFF4CAF7D) else Color(0xFF3A3A44),
                    shape = RoundedCornerShape(10.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (isActive) Color(0xFF8FE0AC) else Color(0xFF55555F),
                    shape = RoundedCornerShape(10.dp),
                )
                .combinedClickable(
                    onClick = { if (!renaming) onActivate() },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // 状态点：活=山绿亮 / 死=灰（v1 tdot）
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when {
                            isAlive -> Color(0xFF8FE0AC)
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
                    color = if (isActive) Color.White else Color(0xFFE0E0E0),
                    fontSize = 15.sp,
                    fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
            }
        }
        // v1 风激活底条（4dp 山绿，仅激活标签）
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(4.dp)
                .background(
                    color = if (isActive) Color(0xFF8FE0AC) else Color.Transparent,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
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
