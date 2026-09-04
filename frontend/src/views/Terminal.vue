<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { chat, wsSend, connect, termRegister } from '../useChat.js'

const stage = ref(null)
const activeId = ref('')
const terms = reactive({})   // id -> {title, el, term, fit, unreg, alive}
let seq = parseInt(localStorage.getItem('xq_tseq') || '0')

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
    onExit: (code) => { terms[id].alive = false },
  })
  term.onData((d) => wsSend({ type: 'terminal_input', terminalId: id, data: d }))
  terms[id] = { title, el, term, fit, unreg, alive: true }
  const ro = new ResizeObserver(() => {
    if (el.offsetWidth > 0) { try { fit.fit() } catch {} }
  })
  ro.observe(el)
  terms[id].ro = ro
  const cwd = chat.state?.cwd || '/data/data/com.pihost/files/home'
  wsSend({ type: 'terminal_create', terminalId: id, title, locale: 'zh', cwd, cols: term.cols, rows: term.rows })
  activate(id)
  setTimeout(() => term.focus(), 150)
  return id
}

function activate(id) {
  activeId.value = id
  for (const [tid, t] of Object.entries(terms)) t.el.classList.toggle('act', tid === id)
  setTimeout(() => { try { terms[id]?.fit.fit(); terms[id]?.term.focus() } catch {} }, 60)
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
  const t = terms[id]
  if (t) { t.term.reset(); t.alive = true }
  setTimeout(() => {
    const cwd = chat.state?.cwd || '/data/data/com.pihost/files/home'
    wsSend({ type: 'terminal_create', terminalId: id, title: terms[id]?.title || '终端', locale: 'zh', cwd, cols: terms[id]?.term.cols || 80, rows: terms[id]?.term.rows || 24 })
  }, 600)
}

// ── Termux 风格虚拟按键 ──
const ctrlOn = ref(false)
const altOn = ref(false)
function key(ch, label) { return { ch, label: label || ch } }
const vkeys = [
  key('\x1b', 'ESC'), key('\t', 'TAB'), key('\r', 'ENTER'), key('\x7f', 'DEL'),
  key('\x1b[A', '↑'), key('\x1b[B', '↓'), key('\x1b[D', '←'), key('\x1b[C', '→'),
  key('\x1b[5~', 'PGUP'), key('\x1b[6~', 'PGDN'), key('\x1b[H', 'HOME'), key('\x1b[F', 'END'),
  key('|'), key('-'), key('/'), key('\\'), key('~'), key('`'), key('$'), key('#'),
]
function press(k) {
  const id = activeId.value
  if (!id || !terms[id]) return
  let data = k.ch
  if (k.label === 'CTRL') { ctrlOn.value = !ctrlOn.value; return }
  if (k.label === 'ALT') { altOn.value = !altOn.value; return }
  if (k.label === 'ENTER') { data = '\r' }
  if (ctrlOn.value && k.label === 'TAB') { data = '\t' }
  if (ctrlOn.value && data.length === 1) {
    const c = data.toUpperCase().charCodeAt(0)
    if (c >= 64 && c <= 95) data = String.fromCharCode(c - 64) // ctrl+字母
  }
  if (altOn.value && data.length === 1) data = '\x1b' + data
  wsSend({ type: 'terminal_input', terminalId: id, data })
  ctrlOn.value = false; altOn.value = false
  terms[id].term.focus()
}

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
      <div v-for="(t, id) in terms" :key="id" class="tab tap" :class="{ act: id === activeId, dead: !t.alive }" @click="activate(id)">
        <span class="tdot" :class="t.alive ? 'on' : ''"></span>
        <span class="tlab" @click.stop="restart(id)" title="长按标题重启">{{ t.title }}</span>
        <span class="tx tap" @click.stop="closeTerm(id)">×</span>
      </div>
      <button class="newb tap" @click="mkTerm('终端 ' + (seq + 1))">＋</button>
      <span class="sp"></span>
      <button class="rb tap" title="重启当前" @click="restart(activeId)">↻</button>
    </header>

    <!-- 终端舞台（多实例共存，切换显示） -->
    <div ref="stage" class="stage"></div>

    <!-- 虚拟按键条 -->
    <div class="vkeys">
      <button class="vk mod tap" :class="{ on: ctrlOn }" @click="press({ label: 'CTRL' })">CTRL</button>
      <button class="vk mod tap" :class="{ on: altOn }" @click="press({ label: 'ALT' })">ALT</button>
      <button v-for="k in vkeys" :key="k.label" class="vk tap" @click="press(k)">{{ k.label }}</button>
    </div>
  </div>
</template>

<style scoped>
.termwrap { position: fixed; inset: 0; background: #0d0e12; display: flex; flex-direction: column; z-index: 10; }
.tabs { display: flex; align-items: center; gap: 4px; padding: 6px 8px; background: #14161c; border-bottom: 1px solid #23262e; overflow-x: auto; }
.tab { display: flex; align-items: center; gap: 5px; background: #1a1d26; border: 1px solid #23262e; border-radius: 9px; padding: 5px 8px; font-size: 12px; color: #8b8f98; flex-shrink: 0; }
.tab.act { border-color: #8b5cf6; color: #dcddde; background: rgba(139,92,246,.1); }
.tab.dead { opacity: .5; }
.tdot { width: 6px; height: 6px; border-radius: 50%; background: #4a4e58; }
.tdot.on { background: #3ecf72; }
.tlab { max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tx { padding: 0 3px; color: #666b76; font-size: 13px; }
.newb { border: 1px dashed #3a3e48; background: none; color: #8b8f98; border-radius: 9px; padding: 5px 10px; font-size: 13px; flex-shrink: 0; }
.rb { border: 1px solid #23262e; background: #1a1d26; color: #8b8f98; border-radius: 9px; padding: 5px 9px; font-size: 12px; flex-shrink: 0; }
.sp { flex: 1; min-width: 6px; }
.stage { flex: 1; position: relative; }
.tpane { position: absolute; inset: 0; display: none; padding: 4px 6px; }
.tpane.act { display: block; }
.stage :deep(.xterm) { height: 100%; }
.stage :deep(.xterm-viewport) { background: #0d0e12 !important; }
/* 虚拟按键条 */
.vkeys { display: flex; gap: 5px; padding: 7px 8px calc(7px + env(safe-area-inset-bottom)); background: #14161c; border-top: 1px solid #23262e; overflow-x: auto; scrollbar-width: none; }
.vk { flex-shrink: 0; min-width: 38px; height: 36px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 8px; font-size: 12px; font-weight: 600; font-family: ui-monospace, monospace; }
.vk.mod { background: #232635; color: #a78bfa; border-color: #3d3560; }
.vk.mod.on { background: #8b5cf6; color: #fff; border-color: #8b5cf6; }
</style>
