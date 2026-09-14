package com.xiaoqiu.ui.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * [小丘] 悬浮方向键 D-pad —— v1 Terminal.vue 悬浮 D-pad v3 复刻：
 *
 *  - 球态（✥）：拖=移位 / 点=展开
 *  - 展开态：⠿⠿ 拖柄（拖=移位）+ ⌄ 收起 + ↑←↓→ 方向键（即按即发）
 *  - 位置持久化（SharedPreferences，跨启动保留）
 *  - 出界自愈（clamp 回屏幕内）
 *
 * @param onArrow 'A'↑ 'B'↓ 'C'→ 'D'←（与 TerminalScreen 的 onArrow 协议一致）
 */
@Composable
fun FloatingDpad(
    onArrow: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 全部用像素：屏幕实际尺寸（dp×density），拖动增量原生 px，offset 原生 px
    val screenWpx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val screenHpx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val screenW = screenWpx.toInt()
    val screenH = screenHpx.toInt()

    var expanded by remember { mutableStateOf(false) }
    var pos by remember { androidx.compose.runtime.mutableStateOf(loadDpadPos(context)) }

    // 出界自愈：屏幕旋转/尺寸变化后 clamp 回来
    LaunchedEffect(screenW, screenH) {
        val fixed = clampDpad(pos, screenW, screenH)
        if (fixed != pos) { pos = fixed; saveDpadPos(context, fixed) }
    }

    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val next = clampDpad(
                DpadPos(pos.x + dragAmount.x, pos.y + dragAmount.y), screenW, screenH)
            if (next != pos) { pos = next; saveDpadPos(context, next) }
        }
    }

    if (!expanded) {
        // ── 球态：拖=移 / 点=展开 ──
        Box(
            modifier = modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(46.dp)
                .background(Color(0xCC2E4A38), CircleShape)
                .then(drag)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("✥", color = Color(0xFFE7F0EA), fontSize = 20.sp)
        }
    } else {
        // ── 展开态 ──
        Column(
            modifier = modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .background(Color(0xE626262A), RoundedCornerShape(14.dp))
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // [小丘] 顶行：拖柄胶囊（左）+ 收起凸钮（右，独立醒目）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .then(drag)
                        .background(Color(0xFF33333A), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF4A5A50), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("⠿⠿ 拖移", color = Color(0xFF8FE0AC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .shadow(4.dp, androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF1E2B23), androidx.compose.foundation.shape.CircleShape)
                        .border(1.5.dp, Color(0xFF8FE0AC), androidx.compose.foundation.shape.CircleShape)
                        .clickable { expanded = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⌄", color = Color(0xFF8FE0AC), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            // 方向键 ↑ / ← ↓ →
            DpadKey("↑", Modifier.clickable { onArrow('A') })
            Row {
                DpadKey("←", Modifier.clickable { onArrow('D') })
                DpadKey("↓", Modifier.clickable { onArrow('B') })
                DpadKey("→", Modifier.clickable { onArrow('C') })
            }
        }
    }
}

@Composable
private fun DpadKey(label: String, clickModifier: Modifier) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .size(36.dp)
            .background(Color(0xFF2E4A38), RoundedCornerShape(8.dp))
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFFE7F0EA), fontSize = 17.sp)
    }
}

// ── 位置持久化 ──

data class DpadPos(val x: Float, val y: Float)

private fun clampDpad(p: DpadPos, screenW: Int, screenH: Int): DpadPos {
    // 余量 px：球 46dp+边距≈180px；展开态最宽≈260px
    val maxX = (screenW - 200).coerceAtLeast(0).toFloat()
    val maxY = (screenH - 300).coerceAtLeast(0).toFloat()
    // MAX_VALUE = 未初始化标记 → 右缘中部
    if (p.x == Float.MAX_VALUE) return DpadPos(maxX, (maxY * 0.45f))
    return DpadPos(p.x.coerceIn(0f, maxX), p.y.coerceIn(0f, maxY))
}

private fun loadDpadPos(context: Context): DpadPos {
    val sp = context.getSharedPreferences("xiaoqiu_dpad", Context.MODE_PRIVATE)
    val x = sp.getFloat("x", -1f)
    val y = sp.getFloat("y", -1f)
    // 首次：屏幕右侧中部（dp 单位；-1 标记由首帧 clamp 归位）
    return if (x >= 0 && y >= 0) DpadPos(x, y) else DpadPos(Float.MAX_VALUE, 400f)
}

private fun saveDpadPos(context: Context, p: DpadPos) {
    if (p.x < 0) return
    context.getSharedPreferences("xiaoqiu_dpad", Context.MODE_PRIVATE)
        .edit().putFloat("x", p.x).putFloat("y", p.y).apply()
}
