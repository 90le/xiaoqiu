import { reactive, watch } from 'vue'
import { chat, api } from './useChat.js'

/**
 * 统一语音会话引擎（页面侧唯一状态机权威）。
 * 两个入口（🎙按钮 / 喊"小丘"）只是点火器，行为完全一致。
 * 纪律：零 setTimeout——推进只靠 ws 消息 watch / fetch 回调 / native 注入回调
 * （后台 WebView 节流 timer，事件不节流）。
 */
export const vs = reactive({
  state: 'off',      // off|listening|thinking|replying|executing|conclusion
  from: '',          // wake|mic
  lastHeard: '',
  turnN: 0,
})

let speakToken = 0
let speakResolver = null
let streamWatchStop = null

const bus = (payload) => {
  try {
    return fetch('/api/voice_bus', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
  } catch { return Promise.resolve() }
}
const glow = (mode) => { bus({ action: 'glow', mode }) }

/** 统一发声称：文本送到 :kws 播（它持有打断麦克风），完成经 __ttsDone 注入解锁 */
function speak(text) {
  const t = String(text || '').slice(0, 400)
  if (!t) return Promise.resolve()
  const token = 'tk' + (++speakToken)
  return new Promise((resolve) => {
    speakResolver = { token, resolve }
    bus({ action: 'speak', text: t, token })
  })
}
export function vsTtsDone(token) {
  if (speakResolver && speakResolver.token === token) { const r = speakResolver; speakResolver = null; r.resolve() }
}

/* ── 入口 ── */
export function vsIgnite(from) {
  if (vs.state !== 'off') { vsStop(); return } // 再点=结束（切换语义）
  vs.from = from; vs.turnN = 0; vs.state = 'listening'
  glow('listen')
  bus({ action: 'session', cmd: 'start', from })
}
export function vsStop() {
  streamWatchStop?.(); streamWatchStop = null
  vs.state = 'off'; glow('off')
  bus({ action: 'session', cmd: 'stop' })
}
/** :kws 收尾（超时/退出词）→ SESSION_END → 注入 */
export function vsEnd() {
  streamWatchStop?.(); streamWatchStop = null
  speakResolver?.resolve(); speakResolver = null
  vs.state = 'off'; glow('off')
}

/* ── 一轮 ── */
export async function vsTurn(text, from) {
  vs.lastHeard = text; vs.turnN++
  vs.state = 'thinking'; glow('think')
  let data = null
  try {
    const r = await fetch('/api/chat_fast', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ q: text, context: recentCtx() }) })
    const d = (await r.json())?.structuredContent
    if (d?.ok) data = d.data
  } catch {}
  if (data && data.type === 'chat') { await reply(data.answer); return }
  await exec(data, (data && data.prompt) ? data.prompt : text)
}

async function reply(answer) {
  vs.state = 'replying'; glow('speak')
  await speak(await humanize(answer, 'reply'))
  done()
}
async function exec(data, prompt) {
  vs.state = 'executing'; glow('speak')
  await speak((data && data.reply) || '好嘞，这就办') // 快脑动态确认（"我来查天气"）
  glow('exec')
  const ok = api.prompt(prompt) // 优化后指令 → 当前活动会话
  if (!ok) { await speak('连接断了，打开小丘再试一次'); done(); return }
  await streamEnd()
  vs.state = 'conclusion'; glow('speak')
  const text = lastAssistantText()
  if (text) await speak(await humanize(text, 'reply')) // 结论式：不是朗诵
  done()
}
function done() {
  vs.state = 'listening'; glow('listen')
  bus({ action: 'done' }) // :kws 续听
}

/** 流结束：事件驱动（ws 消息推进 watch），发送即完成则立即解 */
function streamEnd() {
  return new Promise((resolve) => {
    if (!chat.state?.streamingMessage) { resolve(); return }
    const stop = watch(() => !!chat.state?.streamingMessage, (v, ov) => {
      if (!v && ov) { stop(); streamWatchStop = null; resolve() }
    })
    streamWatchStop = stop
  })
}

/* ── 工具 ── */
function lastAssistantText() {
  const msgs = chat.state?.messages || []
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i]?.role === 'assistant') {
      return (msgs[i].content || []).map(b => (b.type === 'text' ? b.text : '')).join('').trim()
    }
  }
  return ''
}
async function humanize(text, kind) {
  const t = String(text || '').trim()
  if (!t || t.length <= 90) return t || '好了'
  try {
    const r = await fetch('/api/ai_humanize', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kind: kind || 'reply', text: t.slice(0, 4000) }) })
    const d = (await r.json())?.structuredContent
    if (d?.ok && d?.data) return String(d.data)
  } catch {}
  return t.slice(0, 120) + '……'
}
function recentCtx() {
  const msgs = (chat.state?.messages || []).slice(-6)
  return msgs.map(m => (m.role === 'user' ? '用户:' : '小丘:') +
    (m.content || []).map(b => (b.type === 'text' ? b.text : '')).join('').slice(0, 80)).join('\n')
}
