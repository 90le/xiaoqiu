package com.xiaoqiu.ui.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

/**
 * [小丘] ⌘ 命令面板 —— v1 commands.json 任务复用复刻：
 * 常用命令（名称+命令+工作目录）保存 → 一键新标签执行/预填。
 *
 * 存储：SharedPreferences JSON（轻量，命令清单场景不需要 Room）。
 * v1 语义：▶=新标签立即执行；✎=预填到提示符待确认（安全）。
 */
object CommandStore {
    private const val SP = "xiaoqiu_commands"
    private const val KEY = "commands"

    data class Cmd(val name: String, val command: String, val cwd: String = "")

    fun list(context: Context): MutableList<Cmd> {
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Cmd(o.optString("name"), o.optString("command"), o.optString("cwd"))
            }
        } catch (_: Exception) { mutableListOf() }
    }

    fun save(context: Context, cmds: List<Cmd>) {
        val arr = JSONArray()
        cmds.forEach { c ->
            arr.put(org.json.JSONObject().put("name", c.name).put("command", c.command).put("cwd", c.cwd))
        }
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    manager: TerminalSessionManager,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cmds by remember { mutableStateOf(CommandStore.list(context)) }
    var editing by remember { mutableStateOf<Pair<Int, CommandStore.Cmd>?>(null) } // idx(-1=新建) to cmd
    var deleteArmed by remember { mutableStateOf<String?>(null) } // 二次确认删除（v1 同款）

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF232326),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "命令面板",
                color = Color(0xFFE7F0EA),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            if (editing != null) {
                CommandEditForm(
                    initial = editing!!.second,
                    isNew = editing!!.first < 0,
                    onSave = { cmd ->
                        val idx = editing!!.first
                        val next = cmds.toMutableList()
                        if (idx >= 0) next[idx] = cmd else next.add(cmd)
                        cmds = next
                        CommandStore.save(context, next)
                        editing = null
                    },
                    onCancel = { editing = null },
                )
            } else {
                if (cmds.isEmpty()) {
                    Text(
                        "还没有保存的命令\n用下面的「＋ 新建命令」添加常用操作",
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                cmds.forEachIndexed { idx, cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2E))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cmd.name, color = Color(0xFFE7F0EA), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                cmd.command,
                                color = Color(0xFF7FBF9A),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        // ▶ 立即执行（新标签）
                        SheetAction("▶", Color(0xFF6FBF8A)) {
                            manager.createTab(sessionId = null, initCommand = null)
                            // 新标签启动后发送命令+回车
                            val tab = manager.active
                            if (tab != null) {
                                tab.session.sendText(cmd.command + "\r")
                            }
                            onDismiss()
                        }
                        Spacer(Modifier.width(6.dp))
                        // ✎ 预填（不执行，待确认）
                        SheetAction("✎", Color(0xFF9CBFA8)) {
                            manager.createTab(initCommand = cmd.command)
                            onDismiss()
                        }
                        Spacer(Modifier.width(6.dp))
                        SheetAction("⌨", Color(0xFF9CBFA8)) { editing = Pair(idx, cmd) }
                        Spacer(Modifier.width(6.dp))
                        val armed = deleteArmed == cmd.name
                        SheetAction(
                            if (armed) "确认删?" else "✕",
                            if (armed) Color(0xFFE8853D) else Color(0xFF777777),
                        ) {
                            if (armed) {
                                val next = cmds.toMutableList().also { it.removeAt(idx) }
                                cmds = next
                                CommandStore.save(context, next)
                                deleteArmed = null
                            } else {
                                deleteArmed = cmd.name
                            }
                        }
                    }
                }
                // 新建按钮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2E4A38))
                        .clickable { editing = Pair(-1, CommandStore.Cmd("", "")) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("＋ 新建命令", color = Color(0xFFE7F0EA), fontSize = 14.sp) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SheetAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFF33333A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun CommandEditForm(
    initial: CommandStore.Cmd,
    isNew: Boolean,
    onSave: (CommandStore.Cmd) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var cwd by remember { mutableStateOf(initial.cwd) }

    Column {
        Text(
            if (isNew) "新建命令" else "编辑命令",
            color = Color(0xFFBBBBBB), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Field("名称", name) { name = it }
        Field("命令", command, mono = true) { command = it }
        Field("工作目录（可选，如 /var/xiaoqiu）", cwd) { cwd = it }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (name.isNotBlank() && command.isNotBlank()) Color(0xFF2E4A38) else Color(0xFF33333A))
                    .clickable(enabled = name.isNotBlank() && command.isNotBlank()) {
                        onSave(CommandStore.Cmd(name.trim(), command.trim(), cwd.trim()))
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("保存", color = Color(0xFFE7F0EA), fontSize = 14.sp) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF33333A))
                    .clickable(onClick = onCancel)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("取消", color = Color(0xFFBBBBBB), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false, onChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, color = Color(0xFF888888), fontSize = 12.sp)
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFE7F0EA),
                fontSize = 14.sp,
                fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}
