<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { chat, wsSend, connect, termRegister } from '../useChat.js'

const wrap = ref(null)
const status = ref('启动中…')
const alive = ref(false)
let term = null, fit = null, unreg = null
const TID = 'xq-main-' + (localStorage.getItem('xq_tid') || (localStorage.setItem('xq_tid', String(Date.now()).slice(-6)), localStorage.getItem('xq_tid')))

function boot() {
  if (!wrap.value) return
  term = new Terminal({
    fontSize: 13,
    fontFamily: 'ui-monospace, "Cascadia Code", Menlo, monospace',
    theme: { background: '#0d0e12', foreground: '#dcddde', cursor: '#a78bfa', selectionBackground: 'rgba(139,92,246,.3)' },
    cursorBlink: true,
    scrollback: 2000,
    convertEol: true,
  })
  fit = new FitAddon()
  term.loadAddon(fit)
  term.open(wrap.value)
  try { fit.fit() } catch {}
  unreg = termRegister(TID, {
    write: (d) => term.write(d),
    onExit: (code) => { alive.value = false; status.value = `已退出（${code ?? '无码'}）` },
  })
  term.onData((d) => wsSend({ type: 'terminal_input', terminalId: TID, data: d }))
  const dims = () => ({ cols: term.cols, rows: term.rows })
  const sendDims = () => wsSend({ type: 'terminal_resize', terminalId: TID, ...dims() })
  const ro = new ResizeObserver(() => {
    if (wrap.value?.offsetWidth > 0) { try { fit.fit(); sendDims() } catch {} }
  })
  ro.observe(wrap.value)
  status.value = '已连接'
  alive.value = true
  const cwd = chat.state?.cwd || '/data/data/com.pihost/files/home'
  wsSend({ type: 'terminal_create', terminalId: TID, title: '小丘终端', locale: 'zh', cwd, cols: term.cols, rows: term.rows })
  term.focus()
}

function restart() {
  wsSend({ type: 'terminal_kill', terminalId: TID })
  setTimeout(() => {
    const cwd = chat.state?.cwd || '/data/data/com.pihost/files/home'
    wsSend({ type: 'terminal_create', terminalId: TID, title: '小丘终端', locale: 'zh', cwd, cols: term?.cols || 80, rows: term?.rows || 24 })
    alive.value = true; status.value = '已重启'
    term?.focus()
  }, 600)
}

onMounted(() => {
  if (chat.status !== 'open') connect()
  nextTick(boot)
})
onUnmounted(() => {
  if (unreg) unreg()
  try { term?.dispose() } catch {}
})
</script>

<template>
  <div class="termwrap">
    <header class="thead">
      <b class="tt">🖥 终端</b>
      <span class="tst muted">{{ status }}</span>
      <span class="sp"></span>
      <button class="tb tap" @click="restart">↻ 重启</button>
      <button class="tb tap stop" @click="wsSend({ type: 'terminal_kill', terminalId: TID }); alive = false; status = '已关闭'">⏹ 关闭</button>
    </header>
    <div ref="wrap" class="term"></div>
  </div>
</template>

<style scoped>
.termwrap { position: fixed; inset: 0; background: #0d0e12; display: flex; flex-direction: column; z-index: 10; }
.thead { display: flex; align-items: center; gap: 8px; padding: 8px 10px; background: #14161c; border-bottom: 1px solid #23262e; }
.tt { color: #dcddde; font-size: 14px; }
.tst { font-size: 11px; }
.sp { flex: 1; }
.tb { background: #1a1d26; color: #dcddde; border: 1px solid #23262e; border-radius: 16px; padding: 6px 12px; font-size: 12px; }
.tb.stop { background: #3b1f24; border-color: #5c2b30; color: #f2a4a4; }
.term { flex: 1; padding: 6px 8px; }
.term :deep(.xterm) { height: 100%; }
.term :deep(.xterm-viewport) { background: #0d0e12 !important; }
</style>
