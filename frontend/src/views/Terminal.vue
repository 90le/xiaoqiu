<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { chat, wsSend, connect, termRegister } from '../useChat.js'

const stage = ref(null)
const activeId = ref('')
const terms = reactive({})
const keysOpen = ref(false)
let seq = parseInt(localStorage.getItem('xq_tseq') || '0')

function openShellDrawer() { window.dispatchEvent(new Event('xq-open-drawer')) }

function mkTerm(title) {
  const id = 'xq-t' + (++seq)
  localStorage.setItem('xq_tseq', String(seq))
  const el = document.createElement('div')
  el.className = 'tpane'
  stage.value.appendChild(el)
  const term = new Terminal({
    fontSize: 13,
    fontFamily: 'ui-monospace, "Cascadia Mono", Menlo, monospace',
    theme: { background: '#0d0e12', foreground: '#dcddde', cursor: '#a78bfa', selectionBackground: 'rgba(139,92,246,.3)' },
    cursorBlink: true, scrollback: 2000, convertEol: true,
  })
  const fit = new FitAddon()
  term.loadAddon(fit)
  term.open(el)
  const unreg = termRegister(id, {
    write: (d) => term.write(d),
    onExit: () => { if (terms[id]) terms[id].alive = false },
  })
  term.onData((d) => wsSend({ type: 'terminal_input', terminalId: id, data: d }))
  terms[id] = { title, el, term, fit, unreg, alive: true }
  const ro = new ResizeObserver(() => {
    if (el.offsetWidth > 0 && el.classList.contains('act')) { try { fit.fit() } catch {} }
  })
  ro.observe(el)
  terms[id].ro = ro
  const cwd = chat.state?.cwd || '/data/data/com.pihost/files/home'
  wsSend({ type: 'terminal_create', terminalId: id, title, locale: 'zh', cwd, cols: term.cols || 80, rows: term.rows || 24 })
  activate(id)
  setTimeout(() => { try { fit.fit(); term.focus() } catch {} }, 200)
}

function activate(id) {
  if (!terms[id]) return
  activeId.value = id
  for (const [tid, t] of Object.entries(terms)) t.el.classList.toggle('act', tid === id)
  // 先可见再 fit（display:none 下 fit 会量出 0 尺寸）
  requestAnimationFrame(() => setTimeout(() => {
    try { terms[id].fit.fit() } catch {}
    terms[id].term.focus()
  }, 80))
}

function closeTerm(id) {
  wsSend({ type: 'terminal_kill', terminalId: id })
  const t = terms[id]
  if (!t) return
  t.ro?.disconnect(); t.unreg?.(); try { t.term.dispose() } catch {}
  t.el.remove()
  delete terms[id]
  const rest = Object.keys(terms)
  if (rest.length) activate(rest[rest.length - 1])
}

function restart(id) {
  wsSend({ type: 'terminal_kill', terminalId: id })
  if (terms[id]) { terms[id].term.reset(); terms[id].alive = true }
  setTimeout(() => {
    const cwd = chat.state?.cwd || '/data/data/com.pihost/files/home'
    ws.send && wsSend({ type: 'terminal_create', terminalId: id, title: terms[id]?.title || '终端', locale: 'zh', cwd, cols: terms[id]?.term.cols || 80, rows: terms[id]?.term.rows || 24 })
  }, 600)
}

// ── 虚拟按键 v2：常驻精简行 + 展开面板 ──
const ctrlOn = ref(false), altOn = ref(false)
function send2(id, data) { wsSend({ type: 'terminal_input', terminalId: id, data }) }
function raw(data) { send2(activeId.value, data); terms[activeId.value]?.term.focus() }
function combo(c) { // 常用组合键一键发
  raw(c)
  ctrlOn.value = false; altOn.value = false
}
const CTRL = (ch) => String.fromCharCode(ch.toUpperCase().charCodeAt(0) - 64)
// 精简行（常驻）：修饰键 + 导航 + 高频组合
// 展开行1：控制组合键全家 + 编辑
// 展开行2：符号全家
const expandRow1 = [
  { label: 'CTRL+C', run: () => raw('\x03') }, { label: 'CTRL+D', run: () => raw('\x04') },
  { label: 'CTRL+Z', run: () => raw('\x1a') }, { label: 'CTRL+L', run: () => raw('\x0c') },
  { label: 'CTRL+U', run: () => raw('\x15') }, { label: 'CTRL+W', run: () => raw('\x17') },
  { label: 'CTRL+R', run: () => raw('\x12') }, { label: 'CTRL+A', run: () => raw('\x01') },
  { label: 'CTRL+E', run: () => raw('\x05') }, { label: 'CTRL+K', run: () => raw('\x0b') },
  { label: 'CTRL+Y', run: () => raw('\x19') }, { label: 'CTRL+P/N', run: () => raw('\x10') },
  { label: 'ALT+B', run: () => raw('\x1bb') }, { label: 'ALT+F', run: () => raw('\x1bf') },
  { label: 'ALT+D', run: () => raw('\x1bd') }, { label: 'ALT+.', run: () => raw('\x1b.') },
  { label: 'SHIFT+PGUP', run: () => {} }, // 占位（翻屏走滚动）
]
const expandRow2 = ['|','-','/','\\','~','`','$','#','%','^','&','*','(',')','[',']','{','}','<','>','=','+','.',',',';',':','\'','"','!','?','_','@']

function press(k) {
  if (k === 'CTRL') { ctrlOn.value = !ctrlOn.value; altOn.value = false; return }
  if (k === 'ALT') { altOn.value = !altOn.value; ctrlOn.value = false; return }
  let data = k
  if (ctrlOn.value && data.length === 1) {
    const c = data.toUpperCase().charCodeAt(0)
    if (c >= 64 && c <= 95) data = String.fromCharCode(c - 64)
  } else if (altOn.value && data.length === 1) data = '\x1b' + data
  ctrlOn.value = false; altOn.value = false
  raw(data)
}
const slim = ['ESC', 'TAB', '↑', '↓', '←', '→', 'DEL', 'ENTER']
const slimSeq = { ESC: '\x1b', TAB: '\t', '↑': '\x1b[A', '↓': '\x1b[B', '←': '\x1b[D', '→': '\x1b[C', DEL: '\x7f', ENTER: '\r' }

onMounted(() => {
  if (chat.status !== 'open') connect()
  nextTick(() => mkTerm('终端 1'))
})
onUnmounted(() => {
  for (const [id, t] of Object.entries(terms)) {
    wsSend({ type: 'terminal_kill', terminalId: id })
    t.ro?.disconnect(); t.unreg?.(); try { t.term.dispose() } catch {}
  }
})
</script>

<template>
  <div class="termwrap">
    <!-- 标签栏 -->
    <header class="tabs">
      <button class="tb tap" title="工作台" @click="openShellDrawer">☰</button>
      <div class="tabscroller">
        <div v-for="(t, id) in terms" :key="id" class="tab tap" :class="{ act: id === activeId, dead: !t.alive }" @click="activate(id)">
          <span class="tdot" :class="{ on: t.alive }"></span>
          <span class="tlab">{{ t.title }}</span>
          <span class="tx tap" @click.stop="closeTerm(id)">×</span>
        </div>
        <button class="newb tap" @click="mkTerm('终端 ' + (seq + 1))">＋</button>
      </div>
      <button class="rb tap" title="重启当前终端" @click="restart(activeId)">↻</button>
    </header>

    <!-- 终端舞台：所有 pane 绝对定位叠放，仅当前可见 -->
    <div ref="stage" class="stage"></div>

    <!-- 虚拟按键：精简行 + 可展开 -->
    <div class="vkeys">
      <div class="krow">
        <button class="vk mod tap" :class="{ on: ctrlOn }" @click="press('CTRL')">CTRL</button>
        <button class="vk mod tap" :class="{ on: altOn }" @click="press('ALT')">ALT</button>
        <button v-for="k in slim" :key="k" class="vk tap" @click="press(slimSeq[k] ?? k)">{{ k }}</button>
        <button class="vk cc tap" @click="raw('\x03')">^C</button>
        <button class="vk cc tap" @click="raw('\x0c')">^L</button>
        <button class="vk exp tap" :class="{ on: keysOpen }" @click="keysOpen = !keysOpen">{{ keysOpen ? '▾' : '▴' }} 键</button>
      </div>
      <div v-if="keysOpen" class="kpanel">
        <div class="krow">
          <button v-for="c in expandRow1" :key="c.label" class="vk cc tap" @click="c.run()">{{ c.label }}</button>
        </div>
        <div class="krow">
          <button v-for="s in expandRow2" :key="s" class="vk sym tap" @click="press(s)">{{ s }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<!-- 注意：tpane 是 JS 动态创建的元素，scoped 样式匹配不到 → 必须全局样式 -->
<style>
.tpane { position: absolute; inset: 0; display: none; padding: 4px 6px; }
.tpane.act { display: block; }
</style>

<style scoped>
.termwrap { position: fixed; inset: 0; background: #0d0e12; display: flex; flex-direction: column; z-index: 10; }
.tabs { display: flex; align-items: center; gap: 5px; padding: 6px 8px; background: #14161c; border-bottom: 1px solid #23262e; }
.tb { background: #1a1d26; color: #dcddde; border: 1px solid #23262e; border-radius: 9px; padding: 6px 10px; font-size: 14px; flex-shrink: 0; }
.tabscroller { display: flex; gap: 4px; overflow-x: auto; flex: 1; scrollbar-width: none; }
.tab { display: flex; align-items: center; gap: 5px; background: #1a1d26; border: 1px solid #23262e; border-radius: 9px; padding: 5px 8px; font-size: 12px; color: #8b8f98; flex-shrink: 0; }
.tab.act { border-color: #8b5cf6; color: #dcddde; background: rgba(139,92,246,.1); }
.tab.dead { opacity: .5; }
.tdot { width: 6px; height: 6px; border-radius: 50%; background: #4a4e58; flex-shrink: 0; }
.tdot.on { background: #3ecf72; }
.tlab { max-width: 84px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tx { padding: 0 3px; color: #666b76; font-size: 13px; }
.newb { border: 1px dashed #3a3e48; background: none; color: #8b8f98; border-radius: 9px; padding: 5px 10px; font-size: 13px; flex-shrink: 0; }
.rb { border: 1px solid #23262e; background: #1a1d26; color: #8b8f98; border-radius: 9px; padding: 6px 9px; font-size: 12px; flex-shrink: 0; }
.stage { flex: 1; position: relative; }
.stage :deep(.xterm) { height: 100%; }
.stage :deep(.xterm-viewport) { background: #0d0e12 !important; }
/* 虚拟按键 */
.vkeys { background: #14161c; border-top: 1px solid #23262e; }
.krow { display: flex; gap: 4px; padding: 5px 7px; overflow-x: auto; scrollbar-width: none; align-items: center; }
.vk { flex-shrink: 0; min-width: 36px; height: 34px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 8px; font-size: 12px; font-weight: 600; font-family: ui-monospace, monospace; }
.vk.mod { background: #232635; color: #a78bfa; border-color: #3d3560; }
.vk.mod.on { background: #8b5cf6; color: #fff; border-color: #8b5cf6; }
.vk.cc { color: #7dd3a8; background: #182227; border-color: #24402f; }
.vk.sym { min-width: 30px; }
.vk.exp { color: #a78bfa; }
.vk.exp.on { background: #8b5cf6; color: #fff; }
.kpanel { border-top: 1px dashed #23262e; background: #101219; }
</style>
