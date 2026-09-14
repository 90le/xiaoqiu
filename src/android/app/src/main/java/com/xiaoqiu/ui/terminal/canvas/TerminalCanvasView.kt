package com.xiaoqiu.ui.terminal.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.xiaoqiu.ui.terminal.emulator.CursorShape
import com.xiaoqiu.ui.terminal.emulator.TerminalCell
import com.xiaoqiu.ui.terminal.emulator.TerminalColor
import com.xiaoqiu.ui.terminal.emulator.TerminalEmulator
import com.xiaoqiu.ui.terminal.emulator.TerminalPalette
import com.xiaoqiu.ui.terminal.emulator.TextAttributes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compose Canvas–based terminal renderer. Ports iOS TerminalCanvasView (the
 * rendering half — UITextView selection is not replicated). Emulator state is
 * read via its [TerminalEmulator.version] State so recomposition happens on
 * every feed() call.
 *
 * Cell dimensions are computed from a monospace Paint; the view reports
 * (cols, rows) back via [onResize] whenever its size changes so the emulator
 * and the PTY winsize stay in sync.
 */
@Composable
fun TerminalCanvasView(
    emulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    onResize: (cols: Int, rows: Int) -> Unit,
    onTap: () -> Unit = {},
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }

    // T138: bundle JetBrains Mono so glyph widths and baselines look the
    // same on every device. `Typeface.MONOSPACE` aliases to whatever the
    // OEM ships (Droid Sans Mono / Roboto Mono / vendor custom), and
    // those each measure characters slightly differently.
    val terminalTypeface = com.xiaoqiu.ui.terminal.rememberJetBrainsMonoTypeface()
    val paint = remember(fontSizePx, terminalTypeface) {
        Paint().apply {
            typeface = terminalTypeface
            textSize = fontSizePx
            isAntiAlias = true
            isSubpixelText = true
        }
    }

    val cellWidth = remember(fontSizePx) { paint.measureText("M") }
    val fm = remember(fontSizePx) { paint.fontMetrics }
    val cellHeight = remember(fontSizePx) { fm.bottom - fm.top }
    val baselineOffset = remember(fontSizePx) { -fm.top }

    var cols by remember { mutableIntStateOf(emulator.cols) }
    var rows by remember { mutableIntStateOf(emulator.rows) }

    // Cursor blink — flips every 500ms
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            cursorVisible = !cursorVisible
        }
    }

    // Redraw trigger: bump when emulator version changes
    val version by emulator.version

    // Scroll state with built-in fling. Drag delta in px is accumulated into a
    // float; whole rows are committed to emulator.scrollOffset. Drag down (+px)
    // moves back through scrollback, drag up (-px) returns toward live tail.
    // Return *consumed* delta (= delta minus residual at edge) so Compose's
    // fling animation keeps running until we genuinely run out of scrollback.
    // Apply pixel delta to emulator.scrollOffset, accumulating fractional rows.
    // Returns true if any row was actually scrolled (false → hit edge).
    val scrollAccumPx = remember { mutableStateOf(0f) }
    val applyScroll: (Float) -> Boolean = { delta ->
        scrollAccumPx.value += delta
        val rowDelta = (scrollAccumPx.value / cellHeight).toInt()
        if (rowDelta == 0) {
            true
        } else {
            val before = emulator.scrollOffset
            emulator.scrollOffset = (before + rowDelta).coerceAtLeast(0)
            val applied = emulator.scrollOffset - before
            scrollAccumPx.value -= applied * cellHeight
            if (applied != rowDelta) {
                scrollAccumPx.value = 0f
                false  // hit edge
            } else true
        }
    }
    val scope = rememberCoroutineScope()

    // ── [小丘] v1 选区体系状态 ──
    // 柄拖动模式：0=无 1=起点柄 2=终点柄（Canvas 手势统一分发用）
    var dragHandle by remember { mutableStateOf(0) }
    var selectionDragging by remember { mutableStateOf(false) }
    // 复制浮层开关（选区存在时显示）
    var showCopyBar by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current

    /** 长按选词（v1 wordExpand：词字符边界扩展） */
    fun startSelectionAt(px: Float, py: Float) {
        if (cellWidth <= 0f || cellHeight <= 0f) return
        val col = (px / cellWidth).toInt().coerceIn(0, cols - 1)
        val row = (py / cellHeight).toInt().coerceIn(0, rows - 1)
        val lines = emulator.visibleLines()
        fun isWordChar(c: Int): Boolean {
            val ch = c.toChar()
            return ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' ||
                ch == '/' || ch == ':' || ch == '?' || ch == '&' || ch == '=' ||
                ch == '+' || ch == '%' || ch == '#' || ch == '~' || ch == '@'
        }
        var sx = col; var ex = col
        val line = lines.getOrNull(row)
        if (line != null && col in line.indices && isWordChar(line[col].char)) {
            while (sx > 0 && isWordChar(line[sx - 1].char)) sx--
            while (ex < line.size - 1 && isWordChar(line[ex + 1].char)) ex++
        }
        emulator.setSelectionRect(sx, row, ex, row)
        showCopyBar = true
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** 规范化选区端点（start 在前） */
    fun selectionEnds(): IntArray? {
        val sel = emulator.selectionRect.value ?: return null
        return if (sel[1] < sel[3] || (sel[1] == sel[3] && sel[0] <= sel[2]))
            intArrayOf(sel[0], sel[1], sel[2], sel[3])
        else intArrayOf(sel[2], sel[3], sel[0], sel[1])
    }

    /** 柄命中测试（起点/终点 ±30px） */
    fun hitHandle(x: Float, y: Float): Int {
        val e = selectionEnds() ?: return 0
        val sx = e[0] * cellWidth; val sy = (e[1] + 1) * cellHeight
        val ex = (e[2] + 1) * cellWidth; val ey = (e[3] + 1) * cellHeight
        val dS = kotlin.math.hypot((x - sx).toDouble(), (y - sy).toDouble())
        val dE = kotlin.math.hypot((x - ex).toDouble(), (y - ey).toDouble())
        return when { dS < 60 && dS <= dE -> 1; dE < 60 -> 2; else -> 0 }
    }

    /** 拖柄落点 → 更新对应端 */
    fun moveHandleTo(x: Float, y: Float) {
        val e = selectionEnds() ?: return
        val col = (x / cellWidth).toInt().coerceIn(0, cols - 1)
        val row = (y / cellHeight).toInt().coerceIn(0, rows - 1)
        if (dragHandle == 1) emulator.setSelectionRect(col, row, e[2], e[3])
        else emulator.setSelectionRect(e[0], e[1], col, row)
    }

    var handleY by remember { mutableStateOf(0f) }

    // 边缘自动滚（柄拖到上下边缘带时循环滚+选区跟随）
    LaunchedEffect(dragHandle) {
        if (dragHandle == 0) return@LaunchedEffect
        while (true) {
            val edgeTop = cellHeight * 2f
            val edgeBottom = rows * cellHeight - cellHeight * 2f
            // 柄当前位置未知——用最后触摸 y（存在 handleY）
            val hy = handleY
            val dir = when {
                hy in 0f..edgeTop -> -1
                hy >= edgeBottom -> 1
                else -> 0
            }
            if (dir != 0) {
                emulator.scrollOffset = (emulator.scrollOffset + dir).coerceAtLeast(0)
                val e = selectionEnds()
                if (e != null) {
                    if (dragHandle == 1) emulator.setSelectionRect(e[0], e[1] + dir, e[2], e[3])
                    else emulator.setSelectionRect(e[0], e[1], e[2], e[3] + dir)
                }
            }
            delay(40)
        }
    }

    // 复制到剪贴板
    fun copySelection() {
        val e = selectionEnds() ?: return
        val text = emulator.getSelectedText(e[0], e[1], e[2], e[3])
        if (text.isNotEmpty()) {
            val cb = emulator.let {
                // context 通过 LocalContext 在组合侧获取后传入不便，此处用 view
                view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            }
            cb.setPrimaryClip(android.content.ClipData.newPlainText("xiaoqiu", text))
        }
        emulator.clearSelectionRect()
        showCopyBar = false
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalPalette.defaultBackground)
            // [小丘] 统一手势分发：柄拖动 > 滚动/fling；长按选词；点按聚焦
            .pointerInput(cellHeight) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking(); scrollAccumPx.value = 0f
                        // 起点柄判定需要起点坐标——用 coroutineScope 延迟判断不可行；
                        // 简化：onDragStart 记为未定，首个 MOVE 帧判定（下方 change.position 可用）
                    },
                    onDragEnd = {
                        if (dragHandle != 0) { dragHandle = 0 }
                        else {
                            val velocity = tracker.calculateVelocity().y
                            scope.launch {
                                var lastValue = 0f
                                AnimationState(initialValue = 0f, initialVelocity = velocity)
                                    .animateDecay(exponentialDecay(frictionMultiplier = 0.15f)) {
                                        val frameDelta = value - lastValue
                                        lastValue = value
                                        if (!applyScroll(frameDelta)) cancelAnimation()
                                    }
                            }
                        }
                    },
                    onDragCancel = { dragHandle = 0; tracker.resetTracking() },
                ) { change, dragAmount ->
                    change.consume()
                    if (dragHandle == 0 && !selectionDragging) {
                        // 首帧判定：起点命中柄→进入柄拖动；否则滚动
                        dragHandle = hitHandle(change.position.x, change.position.y)
                        selectionDragging = dragHandle != 0
                    }
                    if (dragHandle != 0) {
                        handleY = change.position.y
                        moveHandleTo(change.position.x, change.position.y)
                    } else {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        applyScroll(dragAmount.y)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        if (emulator.selectionRect.value != null) {
                            emulator.clearSelectionRect()
                            showCopyBar = false
                        } else onTap()
                    },
                    onLongPress = { offset -> startSelectionAt(offset.x, offset.y) },
                )
            },
    ) {
        // Recompute cols/rows from actual draw size.
        val newCols = maxOf(1, (size.width / cellWidth).toInt())
        val newRows = maxOf(1, (size.height / cellHeight).toInt())
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            onResize(newCols, newRows)
        }

        @Suppress("UNUSED_EXPRESSION") version  // read so Compose tracks it

        val lines = emulator.visibleLines()
        val defaultBg = TerminalPalette.defaultBackground

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // Paint each cell row-by-row
            for (r in lines.indices) {
                val row = lines[r]
                val y = r * cellHeight
                for (c in row.indices) {
                    val cell = row[c]
                    if (cell.isWideTrailer) continue  // drawn together with leader
                    drawCell(nc, paint, cell, c * cellWidth, y, cellWidth, cellHeight, baselineOffset)
                }
            }
            // T194: selection highlight (drawn between cells and cursor so it
            // overlays the glyphs but doesn't obscure the blinking caret).
            // Coordinates are viewport-relative cells; the gesture handler
            // that sets the rect lives in the new TerminalView-style wrapper.
            val sel = emulator.selectionRect.value
            if (sel != null) {
                val (sx, sy, ex, ey) = if (sel[1] < sel[3] || (sel[1] == sel[3] && sel[0] <= sel[2])) {
                    intArrayOf(sel[0], sel[1], sel[2], sel[3])
                } else {
                    intArrayOf(sel[2], sel[3], sel[0], sel[1])
                }.let { listOf(it[0], it[1], it[2], it[3]) }
                val selPaint = Paint().apply {
                    // [小丘] 选区墨绿（山野风，替代 Material 蓝）
                    color = android.graphics.Color.argb(0x88, 0x2E, 0x4A, 0x38)
                    style = Paint.Style.FILL
                }
                for (r in sy..ey) {
                    if (r !in 0 until rows) continue
                    val cStart = if (r == sy) sx else 0
                    val cEnd = if (r == ey) ex + 1 else cols
                    if (cEnd <= cStart) continue
                    nc.drawRect(
                        cStart * cellWidth, r * cellHeight,
                        cEnd * cellWidth, (r + 1) * cellHeight,
                        selPaint,
                    )
                }

                // [小丘] 双拖柄（v1：圆头竖杆，起点/终点）
                val handlePaint = Paint().apply {
                    color = android.graphics.Color.argb(0xFF, 0x8F, 0xE0, 0xAC)
                    style = Paint.Style.FILL
                }
                fun drawHandlePx(px: Float, py: Float) {
                    val rodTop = py - cellHeight * 1.6f
                    nc.drawRoundRect(
                        android.graphics.RectF(px - 5f, rodTop, px + 5f, py),
                        5f, 5f, handlePaint,
                    )
                    nc.drawCircle(px, rodTop, 11f, handlePaint)
                }
                drawHandlePx(sx * cellWidth, (sy + 1) * cellHeight)
                drawHandlePx((ex + 1) * cellWidth, (ey + 1) * cellHeight)
            }

            // Cursor
            if (emulator.cursorVisible && cursorVisible) {
                val (cc, cr) = emulator.cursorPos()
                // Only draw cursor when viewing live (not scrolled back)
                if (emulator.scrollOffset == 0 && cr in 0 until rows && cc in 0 until cols) {
                    val cx = cc * cellWidth
                    val cy = cr * cellHeight
                    val cursorPaint = Paint().apply {
                        color = TerminalPalette.cursorColor.toArgb()
                        style = Paint.Style.FILL
                    }
                    when (emulator.cursorShape) {
                        CursorShape.BLOCK -> {
                            nc.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint)
                            // Redraw glyph in bg color over cursor
                            val cell = lines.getOrNull(cr)?.getOrNull(cc)
                            if (cell != null && cell.char != ' '.code) {
                                val textPaint = Paint(paint).apply {
                                    color = TerminalPalette.defaultBackground.toArgb()
                                }
                                nc.drawText(
                                    String(intArrayOf(cell.char), 0, 1),
                                    cx, cy + baselineOffset, textPaint,
                                )
                            }
                        }
                        CursorShape.UNDERLINE -> {
                            nc.drawRect(
                                cx, cy + cellHeight - 2f,
                                cx + cellWidth, cy + cellHeight, cursorPaint,
                            )
                        }
                        CursorShape.BAR -> {
                            nc.drawRect(cx, cy, cx + 2f, cy + cellHeight, cursorPaint)
                        }
                    }
                }
            }
        }


        // [小丘] 复制浮层：选区存在时显示（v1 工具条：复制/全选/✕）
        val selNow = emulator.selectionRect.value
        if (showCopyBar && selNow != null) {
            val e = if (selNow[1] < selNow[3] || (selNow[1] == selNow[3] && selNow[0] <= selNow[2]))
                intArrayOf(selNow[0], selNow[1], selNow[2], selNow[3])
            else intArrayOf(selNow[2], selNow[3], selNow[0], selNow[1])
            val densityNow = density
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .padding(start = with(densityNow) { (e[0] * cellWidth).toDp() })
                    .padding(top = with(densityNow) { ((e[1] * cellHeight - 60f * densityNow.density).coerceAtLeast(8f)).toDp() })
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(androidx.compose.ui.graphics.Color(0xE6, 0x2E, 0x4A, 0x38))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.material3.Text("复制", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp,
                    modifier = Modifier.clickable { copySelection() })
                androidx.compose.material3.Text("全选", color = androidx.compose.ui.graphics.Color(0xFFCFE0D5), fontSize = 13.sp,
                    modifier = Modifier.clickable { emulator.setSelectionRect(0, 0, cols - 1, rows - 1) })
                androidx.compose.material3.Text("✕", color = androidx.compose.ui.graphics.Color(0xFFBBBBBB), fontSize = 13.sp,
                    modifier = Modifier.clickable { emulator.clearSelectionRect(); showCopyBar = false })
            }
        }
    } // Canvas content
    } // Box

}

private fun drawCell(
    canvas: android.graphics.Canvas,
    basePaint: Paint,
    cell: TerminalCell,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    baselineOffset: Float,
) {
    val attrs = cell.attributes
    val inverse = attrs.has(TextAttributes.INVERSE)
    val bold = attrs.has(TextAttributes.BOLD)
    val fgColor = TerminalPalette.resolve(
        if (inverse) cell.background else cell.foreground,
        isForeground = true,
        bold = bold,
    )
    val bgColor = TerminalPalette.resolve(
        if (inverse) cell.foreground else cell.background,
        isForeground = false,
    )
    val cellW = if (cell.width == 2) w * 2 else w

    // Background fill (skip default bg — Canvas is already black)
    if (bgColor != TerminalPalette.defaultBackground) {
        val bgPaint = Paint().apply {
            color = bgColor.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawRect(x, y, x + cellW, y + h, bgPaint)
    }

    // Skip drawing printable glyph if blank + default style (faster path)
    val ch = cell.char
    if (ch == ' '.code && !attrs.has(TextAttributes.UNDERLINE) && !attrs.has(TextAttributes.STRIKETHROUGH)) {
        return
    }

    val glyphPaint = Paint(basePaint).apply {
        color = fgColor.toArgb()
        isFakeBoldText = bold
        val italic = attrs.has(TextAttributes.ITALIC)
        // T138: derive italic/bold from the *base* typeface (JetBrains
        // Mono) so we don't fall back to the OEM monospace for styled
        // runs. Typeface.create(<jbmono>, BOLD/ITALIC) keeps the family
        // and synthesizes the style — JetBrains Mono ships a real bold
        // file too, but Typeface.create handles both cases transparently.
        val base = basePaint.typeface ?: Typeface.MONOSPACE
        typeface = when {
            bold && italic -> Typeface.create(base, Typeface.BOLD_ITALIC)
            bold -> Typeface.create(base, Typeface.BOLD)
            italic -> Typeface.create(base, Typeface.ITALIC)
            else -> base
        }
        if (attrs.has(TextAttributes.UNDERLINE)) isUnderlineText = true
        if (attrs.has(TextAttributes.STRIKETHROUGH)) isStrikeThruText = true
        if (attrs.has(TextAttributes.DIM)) alpha = 128
        if (attrs.has(TextAttributes.HIDDEN)) alpha = 0
    }

    val s = String(intArrayOf(ch), 0, 1)
    canvas.drawText(s, x, y + baselineOffset, glyphPaint)
}

