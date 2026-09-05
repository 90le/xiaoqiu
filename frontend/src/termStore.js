// 终端会话池：模块级生存（切页面不杀会话），DOM 挂到 body 隐藏池
import { reactive } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { wsSend, termRegister } from './useChat.js'

export const tstore = reactive({ sessions: {}, order: [] }) // id -> session

// 隐藏池：视图卸载时 pane 移进来（xterm 实例活着，输出持续写入不丢）
const pool = typeof document !== 'undefined' ? document.createElement('div') : null
if (pool) {
  pool.className = 'xq-term-pool'
  document.body.appendChild(pool)
}

export function poolAttach(stage) {
  for (const id of tstore.order) {
    const s = tstore.sessions[id]
    if (s && s.el.parentNode !== stage) stage.appendChild(s.el)
  }
}
export function poolDetach() {
  for (const id of tstore.order) {
    const s = tstore.sessions[id]
    if (s && s.el.parentNode !== pool) pool.appendChild(s.el)
  }
}

/** 最小可用编号（终端 1 关了再新建还是 1，不会涨到 47） */
export function nextTitle() {
  const used = new Set()
  for (const s of Object.values(tstore.sessions)) {
    const n = parseInt(String(s.title).replace(/\D/g, ''), 10)
    if (n) used.add(n)
  }
  let n = 1
  while (used.has(n)) n++
  return '终端 ' + n
}

export function createSession(cwd) {
  const id = 'xq-t' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5)
  const title = nextTitle()
  const el = document.createElement('div')
  el.className = 'tpane'
  pool.appendChild(el)
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
    write: (d) => { term.write(d); if (tstore.sessions[id]) tstore.sessions[id].lastOut = Date.now() },
    onExit: (code) => { if (tstore.sessions[id]) { tstore.sessions[id].alive = false; tstore.sessions[id].exitCode = code } },
  })
  term.onData((d) => wsSend({ type: 'terminal_input', terminalId: id, data: d }))
  tstore.sessions[id] = { id, title, cwd: cwd || '/data/data/com.pihost/files/home', el, term, fit, unreg, alive: true, lastOut: Date.now(), exitCode: null }
  tstore.order.push(id)
  const ro = new ResizeObserver(() => {
    if (el.offsetWidth > 0 && el.classList.contains('act')) { try { fit.fit() } catch {} }
  })
  ro.observe(el)
  wsSend({ type: 'terminal_create', terminalId: id, title, locale: 'zh', cwd: cwd || '/data/data/com.pihost/files/home', cols: term.cols || 80, rows: term.rows || 24 })
  return tstore.sessions[id]
}

export function killSession(id) {
  const s = tstore.sessions[id]
  if (!s) return
  try { wsSend({ type: 'terminal_kill', terminalId: id }) } catch {}
  s.ro?.disconnect?.(); s.unreg?.()
  try { s.term.dispose() } catch {}
  s.el.remove()
  delete tstore.sessions[id]
  const i = tstore.order.indexOf(id)
  if (i >= 0) tstore.order.splice(i, 1)
}

export function restartSession(id) {
  const s = tstore.sessions[id]
  if (!s) return
  wsSend({ type: 'terminal_kill', terminalId: id })
  s.term.reset(); s.alive = true; s.exitCode = null
  setTimeout(() => wsSend({ type: 'terminal_create', terminalId: id, title: s.title, locale: 'zh', cwd: '/data/data/com.pihost/files/home', cols: s.term.cols || 80, rows: s.term.rows || 24 }), 600)
}

export function renameSession(id, title) {
  const s = tstore.sessions[id]
  if (!s || !title?.trim()) return
  s.title = title.trim()
  wsSend({ type: 'rename_terminal', terminalId: id, title: s.title })
}

/** 全部关闭（服务退出兜底时用） */
export function killAll() { for (const id of [...tstore.order]) killSession(id) }
