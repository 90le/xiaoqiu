// 小丘工作台 ↔ pi-web-ui 引擎 的原生 WS 客户端（协议见 recon/pi-web-ui/server/protocol.ts）
import { reactive } from 'vue'

const WSI = 'http://127.0.0.1:8182'
let ws = null
let clientId = localStorage.getItem('xq_cid') || ('xq-' + Math.random().toString(36).slice(2, 10))
localStorage.setItem('xq_cid', clientId)

export const chat = reactive({
  status: 'connecting', ready: false, retries: 0, retryIn: 0,
  state: null,            // UiState: messages/streamingMessage/model/thinkingLevel/stats...
  models: [],             // ModelInfo[]
  sessions: [],           // SessionSummary[]
  conversations: [], activeConvId: '',
  slashCommands: [],
  terminals: [],
  notices: [],
  liveTools: {},          // toolCallId -> {name, text, done, isError, exitCode}
})

const wsQueue = [] // 未连接期间的发送缓冲（防 terminal_create 等被静默丢弃）
export function wsSend(obj) {
  if (ws && ws.readyState === 1) { try { ws.send(JSON.stringify(obj)) } catch {} }
  else wsQueue.push(obj)
}

// ── 终端桥：terminalId -> {write, onExit, onList}（Terminal.vue 注册）──
const termWriters = {}
export function termRegister(id, w) { termWriters[id] = w; return () => delete termWriters[id] }

function applyDelta(st, msg) {
  // message_delta: 打进 streamingMessage（文本/思考增量）
  const e = msg.assistantMessageEvent
  if (!st.streamingMessage || !e) return
  const blocks = st.streamingMessage.content || (st.streamingMessage.content = [])
  if (e.type === 'text' || e.type === 'thinking') {
    let b = blocks[e.contentIndex ?? blocks.length - 1]
    if (!b || b.type !== e.type) { b = e.type === 'text' ? { type: 'text', text: '' } : { type: 'thinking', thinking: '' }; blocks.push(b) }
    const key = e.type === 'text' ? 'text' : 'thinking'
    b[key] = (b[key] || '') + (e.delta || '')
  } else if (e.type === 'toolCall') {
    blocks.push({ type: 'toolCall', id: e.id || ('t-' + Math.random()), name: e.name || 'tool', argumentsText: e.delta || '' })
  }
}

export function connect() {
  try { if (ws) { ws.onclose = null; ws.close() } } catch {}
  chat.status = 'connecting'
  ws = new WebSocket(WSI.replace('http', 'ws') + '/ws')
  ws.onopen = () => {
    chat.status = 'open'
    wsSend({ type: 'hello', clientId })
    // hello 是第一个包；排队消息在其后送达
    const q = wsQueue.splice(0)
    for (const m of q) wsSend(m)
  }
  ws.onmessage = (ev) => {
    let m
    try { m = JSON.parse(ev.data) } catch { return }
    switch (m.type) {
      case 'ready':
        chat.ready = true
        chat.retries = 0
        // 对齐 webui：ready 后主动拉全量状态 + 模型清单
        wsSend({ type: 'get_state' })
        wsSend({ type: 'list_models' })
        wsSend({ type: 'get_commands' })
        break
      case 'snapshot':
        chat.state = m.state
        if (m.state) m.state.messages?.forEach(() => {})
        break
      case 'snapshot_delta':
        if (!chat.state || !m.state) break
        if (chat.state.conversationId !== m.conversationId || chat.state.rev !== m.baseRev) { wsSend({ type: 'get_state' }); break }
        chat.state = { ...m.state, messages: [...(chat.state.messages || []), ...(m.appended || [])] }
        break
      case 'message_delta':
        if (!chat.state) break
        applyDelta(chat.state, m)
        chat.state = { ...chat.state } // 触发响应式
        break
      case 'tool_delta': {
        const t = chat.liveTools[m.toolCallId] || (chat.liveTools[m.toolCallId] = { name: m.toolName, text: '', done: false })
        t.name = m.toolName; t.text += m.delta || ''
        break
      }
      case 'tool_status': {
        const t = chat.liveTools[m.toolCallId] || (chat.liveTools[m.toolCallId] = { name: m.toolName, text: '' })
        t.done = true; t.isError = m.isError; t.exitCode = m.exitCode; t.durationMs = m.durationMs
        break
      }
      case 'models': chat.models = m.models || []; break
      case 'sessions': chat.sessions = m.sessions || []; break
      case 'conversations': chat.conversations = m.conversations || []; chat.activeConvId = m.activeId; break
      case 'slash_commands': chat.slashCommands = m.commands || []; break
      case 'notice': chat.notices.push({ level: m.level, text: m.text, id: Date.now() }); if (chat.notices.length > 5) chat.notices.shift(); break
      case 'terminal_output': {
        const w = termWriters[m.terminalId]
        if (w) w.write(m.data)
        break
      }
      case 'terminal_exit': {
        const w = termWriters[m.terminalId]
        if (w && w.onExit) w.onExit(m.exitCode)
        break
      }
      case 'terminal_list':
        chat.terminals = m.terminals || []
        break
      default: break
    }
  }
  ws.onclose = () => {
    chat.status = 'closed'; chat.ready = false
    chat.retries = (chat.retries || 0) + 1
    const delay = Math.min(1000 * Math.pow(2, Math.min(chat.retries, 4)), 12000) + Math.random() * 500
    chat.retryIn = Math.round(delay / 1000)
    setTimeout(connect, delay)
  }
  ws.onerror = () => { try { ws.close() } catch {} }
}

// ── 高层操作 ──
export const api = {
  prompt(text, attachments) { wsSend({ type: 'prompt', text, attachments: attachments || undefined }) },
  abort() { wsSend({ type: 'abort' }) },
  newChat() { wsSend({ type: 'new_chat' }) },
  setModel(modelId) { wsSend({ type: 'set_model', modelId }) },
  setThinking(level) { wsSend({ type: 'set_thinking', level }) },
  listSessions() { wsSend({ type: 'list_sessions' }) },
  switchSession(path) { wsSend({ type: 'switch_session', path }) },
  deleteSession(path) { wsSend({ type: 'delete_session', path }) },
  switchConversation(id) { wsSend({ type: 'switch_conversation', id }) },
  getState() { wsSend({ type: 'get_state' }) },
}
