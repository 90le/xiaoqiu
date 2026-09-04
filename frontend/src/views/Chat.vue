<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { marked } from 'marked'
import { chat, api, connect } from '../useChat.js'

marked.setOptions({ breaks: true, gfm: true })

const input = ref('')
const listEl = ref(null)
const menu = ref('')          // '' | model | think | sessions
const ttsOn = ref(localStorage.getItem('xq_tts2') !== 'false')
const recording = ref(false)
const voiceState = ref('')
const attachments = ref([])
const fileEl = ref(null)
const openTools = ref({})     // toolCallId -> bool（默认折叠）
const openThink = ref({})     // msgId:blockIdx -> bool（默认折叠）

const SRC = {
  builtin: { label: '内置', c: '#7db3e8' },
  extension: { label: '扩展', c: '#a78bfa' },
  prompt: { label: '模板', c: '#e8b268' },
  skill: { label: '技能', c: '#7dd3a8' },
  plugin: { label: '插件', c: '#e08585' },
}
const slashHint = computed(() => {
  const v = input.value
  if (!v.startsWith('/') || v.includes('\n') || v.length > 30) return []
  const q = v.slice(1).toLowerCase()
  const all = chat.slashCommands || []
  const hit = q ? all.filter(x => (x.name + ' ' + (x.description || '')).toLowerCase().includes(q)) : all
  return hit.slice(0, 30)
})
function pickSlash(c2) { input.value = '/' + c2.name + ' ' }

const ctxCls = computed(() => {
  const p = chat.state?.stats?.contextUsage?.percent || 0
  return p >= 85 ? 'hot' : p >= 60 ? 'warm' : ''
})
function fT(n) { n = n || 0; return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n) }
function fmtCost(c) { c = c || 0; return c >= 1 ? c.toFixed(2) : c >= 0.01 ? c.toFixed(3) : c.toFixed(4) }
const ctxTxt = computed(() => {
  const cu = chat.state?.stats?.contextUsage
  if (!cu || cu.tokens == null) return (cu?.percent ?? 0) + '%'
  return fT(cu.tokens) + '/' + fT(cu.contextWindow) + ' ' + Math.round(cu.percent || 0) + '%'
})
const cachePct = computed(() => {
  const t = chat.state?.stats?.tokens
  if (!t || !t.cacheRead) return 0
  const total = (t.cacheRead || 0) + (t.input || 0)
  return total ? Math.round(t.cacheRead * 100 / total) : 0
})
const cacheCls = computed(() => cachePct.value >= 80 ? 'hi' : cachePct.value >= 50 ? 'mid' : 'lo')
const qTotal = computed(() => {
  const q = chat.state?.queue
  return (q?.steering?.length || 0) + (q?.followUp?.length || 0)
})
const st = computed(() => chat.state)
const msgs = computed(() => st.value?.messages || [])
const streaming = computed(() => st.value?.isStreaming ? (st.value?.streamingMessage || { role: 'assistant', content: [] }) : null)
const levels = computed(() => st.value?.availableThinkingLevels?.length ? st.value.availableThinkingLevels : ['off', 'low', 'medium', 'high'])

function toolResultOf(id) { return msgs.value.find(x => x.role === 'toolResult' && x.toolCallId === id) }
function thinkKey(m, i) { return (m.id || '') + ':' + i }
function thinkOpen(m, i) { return !!openThink.value[thinkKey(m, i)] }
function toggleThink(m, i) { openThink.value[thinkKey(m, i)] = !thinkOpen(m, i) }
function toolOpen(id) { return !!openTools.value[id] }
function toggleTool(id) { openTools.value[id] = !openTools.value[id] }
function thinkPreview(t, live) { const s = (t || '').trim(); return live ? s.slice(-80) : (s.split('\n')[0] || '').slice(0, 80) }

function md(txt) {
  const html = marked.parse(txt || '')
  return html.replace(/<script[\s\S]*?<\/script>/gi, '')
}
// 代码块复制钮：每次消息渲染后注入（事件委托，轻量）
function injectCopyBtns() {
  nextTick(() => {
    document.querySelectorAll('.md pre').forEach(pre => {
      if (pre.querySelector('.ccopy')) return
      pre.style.position = 'relative'
      const b = document.createElement('button')
      b.className = 'ccopy'
      b.textContent = '复制'
      b.onclick = () => {
        navigator.clipboard?.writeText(pre.innerText.replace(/^复制\n?/, ''))
        b.textContent = '✓'
        setTimeout(() => b.textContent = '复制', 1200)
      }
      pre.appendChild(b)
    })
  })
}
function fmtTs(t) { return t ? new Date(t).toLocaleTimeString('zh', { hour: '2-digit', minute: '2-digit' }) : '' }
function scroll() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }
watch(() => [msgs.value.length, st.value?.streamingMessage?.content?.length], () => { scroll(); injectCopyBtns() })
let copyTimer = null
watch(() => st.value?.streamingMessage?.content?.map(b => b.text || b.thinking || '').join('').length, () => {
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(injectCopyBtns, 600) // 流式中节流注入
})
watch(() => st.value?.isStreaming, (b, old) => {
  if (b === false && old === true) {
    if (ttsOn.value) speakLast()
  }
})

const sesQ = ref(''), newCwd = ref('')
let sesT = null
function sesDeb() {
  if (sesT) clearTimeout(sesT)
  sesT = setTimeout(() => {
    if (sesQ.value.trim()) api.searchSessions(sesQ.value.trim())
    else chat.sessionSearch = null
  }, 500)
}
let cwdT = null
function cwdDeb() { if (cwdT) clearTimeout(cwdT); cwdT = setTimeout(() => newCwd.value && api.completePath(newCwd.value), 450) }
const editId = ref('')
function startEdit(m) {
  editId.value = m.id
  input.value = (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n')
  // 携带原附件（仅视觉模型带图；非视觉自动剥离，防发送被静默拦截）
  const vision = !!chat.state?.model?.vision
  const imgs = (m.content || []).filter(b => b.type === 'image' && b.dataUrl)
  if (imgs.length && !vision) {
    attachments.value = []
    setTimeout(() => warn('原消息含 ' + imgs.length + ' 张图，当前模型不支持图片，编辑将只保留文字'), 350)
  } else {
    attachments.value = imgs.map((b, i) => ({ name: '原图' + (i + 1), dataUrl: b.dataUrl, mime: b.mimeType || 'image/png', base64: String(b.dataUrl).split(',')[1] }))
  }
  autoGrow()
  setTimeout(() => { if (taEl.value) { taEl.value.focus(); taEl.value.setSelectionRange(input.value.length, input.value.length) } }, 150)
}
function cancelEdit() { editId.value = ''; input.value = ''; attachments.value = []; autoGrow() }
let submitLock = 0
function submitOnce() {
  const now = Date.now()
  if (now - submitLock < 400) return
  submitLock = now
  if (!editId.value) return
  if (attachments.value.some(a => a.base64) && !chat.state?.model?.vision) {
    warn('含图片但当前模型不支持：已自动去掉图片发送文字')
    attachments.value = []
  }
  submitEdit()
}
function submitEdit() {
  const text = input.value.trim()
  if (!text || !editId.value) return
  const atts = buildAtts()
  if (atts === false) return
  // webui 同款：edit_message = fork（退回该消息/丢弃其后/重发）；
  // 服务端 fork 后经 conversations+snapshot 自动切换视图，客户端无条件退出编辑态
  api.editMessage(editId.value, text, atts && atts.length ? atts : undefined)
  cancelEdit()
}
// 快脑=语音专用：悬浮临时气泡+打字机流式+语音同步；文字直发慢脑
function recentCtx() {
  const ms = (chat.state?.messages || []).slice(-8)
  return ms.map(m => (m.role === 'user' ? '用户:' : '小丘:') +
    (m.content || []).filter(b => b.type === 'text').map(b => b.text).join(' ').slice(0, 80)).join('\n')
}
const vb = reactive({ show: false, state: '', text: '', full: '' })
let typeTimer = null, vbHide = null
function typewrite(full) {
  vb.full = full; vb.text = ''
  let i = 0
  if (typeTimer) clearInterval(typeTimer)
  typeTimer = setInterval(() => {
    vb.text = full.slice(0, ++i)
    if (i >= full.length) clearInterval(typeTimer)
  }, 45)
}
function vbDismiss(ms) { if (vbHide) clearTimeout(vbHide); vbHide = setTimeout(() => vb.show = false, ms) }
async function voiceFlow(t) {
  vb.show = true; vb.state = '理解中…'; vb.text = ''; vb.full = ''
  const atts = buildAtts()
  if (atts === false) { vb.show = false; return }
  try {
    const r = await fetch('/api/chat_fast', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ q: t, context: recentCtx() }) })
    const d = (await r.json()).structuredContent
    const data = d.ok ? d.data : null
    if (data && data.type === 'chat') {
      vb.state = ''
      speakText(data.answer) // 语音同步开始
      typewrite(data.answer) // 打字机流式
      vbDismiss(data.answer.length * 45 + 4500)
    } else {
      const opt = (data && data.prompt) ? data.prompt : t
      vb.state = ''
      if (data && data.reply) { speakText(data.reply); typewrite('🛠 ' + data.reply) }
      api.prompt(opt, atts) // 慢脑接管（主消息流可见）
      vbDismiss(3000)
    }
  } catch { vb.show = false; api.prompt(t, atts) }
}
async function speakText(t) {
  if (!t) return
  let say = t.length > 200 ? t.slice(0, 200) + '……' : t
  try { // 拟人化：像朋友告诉你结果，不复读机
    const r = await fetch('/api/ai_humanize', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ app: 'com.pihost', title: '回复', text: t, kind: 'reply' }) })
    const d = (await r.json()).structuredContent
    if (d.ok && d.data && d.data.say && d.data.say.indexOf('ERR') !== 0) say = d.data.say
  } catch {}
  try { await fetch('/api/tts_speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: say }) }) } catch {}
}
function doAbort() { api.abort() } // webui 同款：只发 abort；后续 prompt 是普通追加
const toasts = ref([])
watch(() => chat.notices.length, () => {
  const list = chat.notices.splice(0) // 消费掉
  for (const n of list) {
    const t = { id: n.id || Date.now() + Math.random(), level: n.level, text: n.text || n.textEn || '' }
    toasts.value.push(t)
    if (toasts.value.length > 3) toasts.value.shift()
    setTimeout(() => { toasts.value = toasts.value.filter(x => x.id !== t.id) }, 4600)
  }
})
const errN = ref('')
let errT = null
function warn(msg) { errN.value = msg; if (errT) clearTimeout(errT); errT = setTimeout(() => errN.value = '', 4500) }
function buildAtts() {
  if (!attachments.value.length) return undefined
  const hasImg = attachments.value.some(a => a.base64)
  if (hasImg && !chat.state?.model?.vision) {
    warn('当前模型不支持图片，请先切换视觉模型（🤖下拉选 👁 标记的）')
    return false
  }
  return attachments.value.map(a => a.path
    ? { path: a.path, mode: 'inline' }
    : { data: a.base64, mimeType: a.mime })
}
// webui submit(queue) 同款：queue=false → steer（插队：当前回合结束立即处理，跳过剩余工具调用）
//                        queue=true  → followUp（排队：整个回复运行结束后才发送）
function submit(queue = false) {
  if (editId.value) { submitEdit(); return } // 编辑态优先：绝不落入普通发送
  const text = input.value.trim()
  const hasAtt = attachments.value.some(a => a.base64 || a.fileB64)
  if (chat.status !== 'open' || (!text && !hasAtt)) return // webui：断线/空内容不发
  const atts = buildAtts()
  if (atts === false) return
  // 发送成功才清空输入（失败保留文本重试）
  if (api.prompt(text, atts, queue || undefined)) {
    input.value = ''; attachments.value = []
    autoGrow()
  }
}
function pickFile() { fileEl.value?.click() }
function onFile(e) {
  const f = e.target.files?.[0]
  if (!f) return
  if (f.type.startsWith('image/')) {
    const r = new FileReader()
    r.onload = () => attachments.value.push({ name: f.name, dataUrl: r.result, mime: f.type, base64: String(r.result).split(',')[1] })
    r.readAsDataURL(f)
  } else attachments.value.push({ name: f.name, path: (st.value?.cwd || '/sdcard') + '/' + f.name })
  e.target.value = ''
}
function rmAtt(i) { attachments.value.splice(i, 1) }
const taEl = ref(null)
function autoGrow() {
  const el = taEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 110) + 'px'
}
function onPaste(e) {
  const items = e.clipboardData?.items || []
  for (const it of items) {
    if (it.type.startsWith('image/')) {
      e.preventDefault()
      const f = it.getAsFile()
      if (!f) continue
      const r = new FileReader()
      r.onload = () => attachments.value.push({ name: '粘贴图片', dataUrl: r.result, mime: f.type, base64: String(r.result).split(',')[1] })
      r.readAsDataURL(f)
    }
  }
}

async function speakLast() {
  const last = [...msgs.value].reverse().find(m => m.role === 'assistant')
  if (!last) return
  const text = (last.content || []).filter(b => b.type === 'text').map(b => b.text).join(' ')
  if (!text) return
  const s = text.length > 220 ? text.slice(0, 220) + '……' : text
  try { await fetch('/api/tts_speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: s }) }) } catch {}
}

function openShellDrawer() { window.dispatchEvent(new Event('xq-open-drawer')) }
function micDown() {
  if (recording.value) return
  if (!window.XiaoqiuBridge) { voiceState.value = '桥未就绪'; return }
  recording.value = true; voiceState.value = '录音中…松手结束'
  window.XiaoqiuBridge.startVoice()
}
function micUp() { if (recording.value) { recording.value = false; window.XiaoqiuBridge?.stopVoice() } }
window.__voiceResult = (t) => { voiceState.value = ''; if (t) voiceFlow(t) }
window.__voiceStatus = (s) => {
  if (s === 'recording') { recording.value = true; voiceState.value = '🎙 录音中…' }
  else if (s === 'thinking') { recording.value = false; voiceState.value = '识别中…' }
  else if (s === 'empty') { voiceState.value = '没听清，再试一次'; setTimeout(() => voiceState.value = '', 1800) }
  else if (s.startsWith('error')) { voiceState.value = s; setTimeout(() => voiceState.value = '', 2500) }
  else voiceState.value = ''
}
window.__xiaoqiuTask = (t) => { if (t) api.prompt(t) }

onMounted(() => { connect(); startWatchdog(); scroll() })
onUnmounted(() => { delete window.__voiceResult; delete window.__voiceStatus; delete window.__xiaoqiuTask })
</script>

<template>
  <div class="chatwrap">
    <!-- ═══ 顶栏：历史 | 模型▾ | 思考▾ | 新对话 ═══ -->
    <header class="top">
      <button class="tb tap" title="工作台" @click="openShellDrawer">☰</button>
      <button class="tb tap" title="历史会话" @click="menu = 'sessions'; api.listSessions()">🗂</button>
      <button class="tb name tap" @click="menu = menu === 'model' ? '' : 'model'">
        {{ st?.model?.name || '模型' }} <span class="car">▾</span>
      </button>
      <button class="tb tap" @click="menu = menu === 'think' ? '' : 'think'">
        🧠 {{ st?.thinkingLevel || '—' }} <span class="car">▾</span>
      </button>
      <span class="sp"></span>
      <button v-if="chat.status !== 'open'" class="tb warn tap" @click="connect()">↻ {{ chat.status === 'connecting' ? '连接中…' : '重连(' + (chat.retryIn || 1) + 's)' }}</button>
      <button v-if="st?.isStreaming" class="tb stop tap" @click="doAbort">⏹ 停止</button>
      <button class="tb tap" title="新对话" @click="api.newChat()">✚</button>
    </header>

    <!-- 模型下拉 -->
    <div v-if="menu === 'model'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'model'" class="drop">
      <div class="dh">选择模型</div>
      <div v-for="m in chat.models" :key="m.id" class="di tap" :class="{ sel: m.id === st?.model?.id }" @click="api.setModel(m.id); menu = ''">
        <div class="din"><b>{{ m.name }}</b><span class="dim">{{ m.provider }}</span></div>
        <span class="tag">{{ m.vision ? '👁' : '' }}{{ m.reasoning ? '🧠' : '' }}</span>
      </div>
      <div v-if="!chat.models.length" class="dh muted">清单载入中…</div>
    </div>

    <!-- 思考级下拉 -->
    <div v-if="menu === 'think'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'think'" class="drop small">
      <div class="dh">思考深度</div>
      <div v-for="l in levels" :key="l" class="di tap" :class="{ sel: l === st?.thinkingLevel }" @click="api.setThinking(l); menu = ''">
        <b>{{ l }}</b>
      </div>
    </div>

    <!-- 服务端通知吐司 -->
    <div class="toasts">
      <transition-group name="tst">
        <div v-for="t in toasts" :key="t.id" class="toast" :class="t.level" @click="toasts = toasts.filter(x => x.id !== t.id)">
          {{ t.level === 'error' ? '⛔' : t.level === 'warning' ? '⚠' : 'ℹ' }} {{ t.text }}
        </div>
      </transition-group>
    </div>

    <!-- 消息流 -->
    <div class="flow" ref="listEl">
      <div v-if="!msgs.length && !streaming" class="empty">
        <div class="logo">🏔</div>
        <div class="et">和小丘说话，或打字</div>
        <div class="muted">操作手机 · 跑命令 · 写代码 · 查记忆</div>
      </div>

      <template v-for="m in msgs" :key="m.id">
        <!-- 用户 -->
        <div v-if="m.role === 'user'" class="mrow urow">
          <div class="ub">
            <img v-for="(b, bi) in (m.content||[]).filter(b => b.type === 'image' && b.dataUrl)" :key="bi" :src="b.dataUrl" class="uimg" />
            <div class="ut">{{ (m.content||[]).filter(b => b.type === 'text').map(b => b.text).join('\n') }}</div>
          </div>
          <div class="uact">
            <button v-if="!busy" class="ua tap" @click="startEdit(m)">✎ 编辑</button>
            <button class="ua tap" @click="navigator.clipboard?.writeText((m.content||[]).filter(b => b.type === 'text').map(b => b.text).join('\n'))">📋</button>
          </div>
        </div>

        <!-- 助手 -->
        <div v-else-if="m.role === 'assistant'" class="mrow arow">
          <div class="ameta">{{ fmtTs(m.timestamp) }}<template v-if="m.model"> · {{ m.model }}</template></div>
          <div class="ab">
            <template v-for="(b, bi) in (m.content || [])" :key="bi">
              <!-- 思考：折叠块 -->
              <div v-if="b.type === 'thinking'" class="think" :class="{ open: thinkOpen(m, bi) }">
                <button class="th-toggle tap" @click="toggleThink(m, bi)">
                  <span class="chev">{{ thinkOpen(m, bi) ? '▾' : '▸' }}</span> 💭 {{ thinkOpen(m, bi) ? '思考过程' : thinkPreview(b.thinking) || '思考过程' }}
                </button>
                <div v-if="thinkOpen(m, bi)" class="th-body">{{ b.thinking }}</div>
              </div>
              <!-- 工具调用：折叠卡 -->
              <div v-else-if="b.type === 'toolCall'" class="tc" :class="{ open: toolOpen(b.id) }">
                <button class="tc-toggle tap" @click="toggleTool(b.id)">
                  <span class="chev">{{ toolOpen(b.id) ? '▾' : '▸' }}</span>
                  <span class="tc-dot" :class="toolResultOf(b.id) ? (toolResultOf(b.id).isError ? 'err' : 'ok') : 'run'"></span>
                  <b>{{ b.name }}</b>
                  <span class="muted tc-sum">{{ (b.argumentsText || '').replace(/\s+/g, ' ').slice(0, 46) }}</span>
                </button>
                <div v-if="toolOpen(b.id)" class="tc-body">
                  <div class="tc-sec">参数</div>
                  <pre class="tc-pre">{{ b.argumentsText }}</pre>
                  <template v-if="toolResultOf(b.id)">
                    <div class="tc-sec">结果 <span :class="toolResultOf(b.id).isError ? 'errt' : ''">{{ toolResultOf(b.id).isError ? '· 出错' : '' }}</span></div>
                    <pre class="tc-pre">{{ String((toolResultOf(b.id).content?.[0]?.text ?? toolResultOf(b.id).content?.[0]) ?? '').slice(0, 3000) }}</pre>
                  </template>
                </div>
              </div>
              <!-- 正文：markdown -->
              <div v-else-if="b.type === 'text' && b.text" class="md" v-html="md(b.text)"></div>
            </template>
          </div>
        </div>
      </template>

      <!-- 排队/插队气泡 -->
      <div v-if="st?.queue">
        <div v-for="(q, i) in (st.queue.steering || [])" :key="'s' + i" class="mrow urow">
          <div class="ub queued"><span class="qtag steer">插队</span>{{ q }}</div>
        </div>
        <div v-for="(q, i) in (st.queue.followUp || [])" :key="'f' + i" class="mrow urow">
          <div class="ub queued"><span class="qtag follow">排队</span>{{ q }}</div>
        </div>
      </div>

      <!-- 流式 -->
      <div v-if="streaming" class="mrow arow">
        <div class="ab live">
          <template v-for="(b, bi) in (streaming.content || [])" :key="bi">
            <div v-if="b.type === 'thinking'" class="think">
              <button class="th-toggle tap" @click="toggleThink(streaming, bi)">
                <span class="chev">{{ thinkOpen(streaming, bi) ? '▾' : '▸' }}</span> 💭 <span class="th-live">思考中<span class="dots">…</span></span> <span class="muted">{{ thinkPreview(b.thinking, true) }}</span>
              </button>
              <div v-if="thinkOpen(streaming, bi)" class="th-body">{{ b.thinking }}</div>
            </div>
            <div v-else-if="b.type === 'toolCall'" class="tc">
              <button class="tc-toggle tap" @click="toggleTool(b.id)">
                <span class="chev">{{ toolOpen(b.id) ? '▾' : '▸' }}</span><span class="tc-dot run"></span><b>{{ b.name }}</b>
              </button>
              <div v-if="toolOpen(b.id)" class="tc-body"><pre class="tc-pre">{{ b.argumentsText }}</pre></div>
            </div>
            <div v-else-if="b.type === 'text' && b.text" class="md" v-html="md(b.text)"></div>
          </template>
          <span class="caret">▍</span>
        </div>
      </div>
    </div>

    <!-- 状态条 -->
    <div class="foot muted">
      <template v-if="st?.stats">
        <button class="ctx tap" :class="ctxCls" :title="'上下文 ' + ctxTxt + '，点击压缩'" @click="api.compact()">
          <span class="ctxbar"><i :style="{ width: Math.min(st.stats.contextUsage.percent || 0, 100) + '%' }"></i></span>
          <span class="ctxnum">{{ ctxTxt }}</span>
        </button>
        <span class="sep">·</span>
        <span title="累计成本">${{ fmtCost(st.stats.cost) }}</span>
        <span class="sep">·</span>
        <span :title="'缓存读 ' + fT(st.stats.tokens.cacheRead) + ' / 写 ' + fT(st.stats.tokens.cacheWrite)">缓存<b class="cacheP" :class="cacheCls">{{ cachePct }}%</b></span>
        <span class="sep">·</span>
        <span title="消息数">{{ st.stats.totalMessages }} 条</span>
        <span v-if="busy" class="working"><span class="wspin"></span>处理中<template v-if="qTotal"> ⏳{{ qTotal }}</template></span>
      </template>
      <span class="sp"></span>
      <button class="fb tap" :title="ttsOn ? '朗读开' : '朗读关'" @click="ttsOn = !ttsOn; localStorage.setItem('xq_tts2', ttsOn)">{{ ttsOn ? '🔊' : '🔇' }}</button>
    </div>

    <!-- 语音状态浮条 -->
    <div v-if="voiceState" class="vbar">{{ recording ? '⏺ ' : '' }}{{ voiceState }}</div>

    <!-- 输入区 -->
    <!-- 快脑悬浮临时气泡（语音专用） -->
    <transition name="vbf">
      <div v-if="vb.show" class="vbub tap" @click="vb.show = false">
        <span class="vbadge">⚡</span>
        <span v-if="vb.state" class="vstate">{{ vb.state }}<span class="dots2">…</span></span>
        <span v-else class="vtxt">{{ vb.text }}<span v-if="vb.text.length < vb.full.length" class="vcur">▍</span></span>
      </div>
    </transition>

    <div class="composer">
      <div v-if="errN" class="errbn">⚠ {{ errN }}</div>
      <div v-if="editId" class="editbn">
        <span class="ebtxt">✎ 编辑消息 · ✓ 发送后从这里重新生成</span>
        <button class="tap" @touchstart.prevent="cancelEdit">取消</button>
      </div>
      <div v-if="attachments.length" class="attrow">
        <div v-for="(a, i) in attachments" :key="i" class="attc">
          <img v-if="a.dataUrl" :src="a.dataUrl" class="attimg2" />
          <span v-else class="attfile2">📎<i>{{ a.name }}</i></span>
          <button class="attx tap" @click="rmAtt(i)">✕</button>
        </div>
        <button class="attadd tap" @click="pickFile">＋</button>
      </div>
      <div v-if="slashHint.length" class="slash">
        <div v-for="sc in slashHint" :key="sc.name" class="si2 tap" @mousedown.prevent="pickSlash(sc)">
          <b class="sname2">/{{ sc.name }}</b>
          <span class="stag" :class="sc.source">{{ SRC[sc.source]?.label || sc.source }}</span>
          <span class="sdesc2">{{ sc.description || '' }}<i v-if="sc.argumentHint" class="shint">{{ sc.argumentHint }}</i></span>
        </div>
      </div>
      <div class="crow">
        <button class="cb tap" @click="pickFile">📎</button>
        <input ref="fileEl" type="file" hidden @change="onFile" />
        <textarea ref="taEl" v-model="input" rows="1" placeholder="发消息…" @paste="onPaste" @input="autoGrow"></textarea>
        <button class="cb mic tap" :class="{ rec: recording }" title="按住说话"
          @touchstart.prevent="micDown" @touchend.prevent="micUp" @mousedown="micDown" @mouseup="micUp">
          <span v-if="recording" class="recdot"></span><template v-else>🎙</template>
        </button>
        <button v-if="busy && input.trim() && !editId" class="cb q tap" title="排队：整个回复结束后再发送" @touchstart.prevent="submit(true)">⏳</button>
        <button class="cb send tap" :class="{ edit: editId, dim: !input.trim() || chat.status !== 'open' }" @touchstart.prevent="input.trim() && (editId ? submitOnce() : submit())">
          <template v-if="editId">✓</template><template v-else-if="busy">⤴</template><template v-else>➤</template>
        </button>
      </div>
    </div>

    <!-- 历史会话抽屉 -->
    <div v-if="menu === 'sessions'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'sessions'" class="drawer">
      <div class="dh big">历史会话 <span class="muted">({{ (chat.sessionSearch ?? chat.sessions).length }})</span></div>
      <div class="ssearch">
        <input v-model="sesQ" placeholder="搜索会话内容（全文）…" @input="sesDeb" />
      </div>
      <div class="slist">
        <div v-for="s in (chat.sessionSearch ?? chat.sessions)" :key="s.path" class="si tap" @click="api.switchSession(s.path); menu = ''">
          <div class="sin"><b>{{ s.name || s.firstMessage?.slice(0, 26) || '会话' }}</b>
            <span v-if="s.source === 'tui'" class="tag">CLI</span></div>
          <div class="dim">{{ s.messageCount }} 条 · {{ new Date(s.modified).toLocaleString('zh', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}</div>
          <span class="sdel tap" @click.stop="api.deleteSession(s.path); setTimeout(() => api.listSessions(), 500)">🗑 删</span>
        </div>
      </div>
      <div class="ws">
        <div class="dh big">工作区</div>
        <div class="cwdnow muted">📁 {{ st?.cwd || '—' }}</div>
        <div v-for="p in chat.projects" :key="p.path" class="prow tap" :class="{ cur: p.path === st?.cwd }" @click="api.setCwd(p.path)">
          <span class="pico">📂</span>
          <span class="ppath">{{ p.path }}</span>
          <b v-if="p.path === st?.cwd" class="pcur">当前</b>
        </div>
        <div class="pinput">
          <input v-model="newCwd" placeholder="输入新路径…" @input="cwdDeb" />
          <button class="tap" @click="api.setCwd(newCwd); newCwd = ''">切换</button>
        </div>
        <div v-if="chat.pathCompletions.length" class="pcomp">
          <div v-for="pc in chat.pathCompletions.slice(0, 6)" :key="pc.path" class="pci tap" @click="newCwd = pc.path; api.completePath(pc.path)">
            {{ pc.type === 'dir' ? '📁' : '📄' }} {{ pc.name }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.ccopy { position: absolute; top: 6px; right: 6px; background: #23262e; color: #a78bfa; border: 1px solid #3d3560;
  border-radius: 6px; padding: 3px 9px; font-size: 11px; }
</style>

<style scoped>

/* 队列气泡/编辑/排队钮 */
.queued { display: flex; align-items: center; gap: 6px; font-size: 13px; opacity: .85; }
.qtag { font-size: 10px; padding: 2px 6px; border-radius: 8px; flex-shrink: 0; }
.qtag.steer { background: #3b2f18; color: #e8b268; }
.qtag.follow { background: #1e2a3a; color: #7db3e8; }
.qrm { color: #8b8f98; padding: 0 2px; }
.uact { display: flex; gap: 4px; margin-top: 3px; justify-content: flex-end; }
.ua { background: none; border: 0; color: #666b76; font-size: 11px; padding: 2px 6px; border-radius: 6px; }
.ua:active { color: #a78bfa; }
.editbar { display: flex; align-items: center; justify-content: space-between; background: #2a2418; color: #e8b268;
  font-size: 12px; border-radius: 9px; padding: 7px 12px; margin-bottom: 7px; }
.cb.q { background: #1e2a3a; border-color: #243a4a; color: #7db3e8; }
/* 斜杠候选 */
.slash { position: absolute; bottom: 100%; left: 10px; right: 10px; max-height: 208px; overflow-y: auto;
  background: #1a1d26; border: 1px solid #2c303b;
  border-radius: 12px; box-shadow: 0 -8px 28px rgba(0,0,0,.5); margin-bottom: 4px; z-index: 5; }
.si2 { display: flex; align-items: center; gap: 8px; padding: 9px 13px; font-size: 13px; color: #dcddde; border-bottom: 1px solid #23262e; }
.si2:last-child { border-bottom: 0; }
.si2:active { background: #232635; }
.sname2 { color: #a78bfa; flex-shrink: 0; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stag { flex-shrink: 0; font-size: 10px; padding: 2px 7px; border-radius: 7px; border: 1px solid; line-height: 1.2; }
.stag.builtin { color: #7db3e8; border-color: #24405a; background: #16222c; }
.stag.extension { color: #a78bfa; border-color: #3d3560; background: #232635; }
.stag.prompt { color: #e8b268; border-color: #4a3c26; background: #241f18; }
.stag.skill { color: #7dd3a8; border-color: #24402f; background: #182227; }
.stag.plugin { color: #e08585; border-color: #4a2626; background: #241a1a; }
.sdesc2 { flex: 1; min-width: 0; font-size: 11.5px; color: #8b8f98; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shint { font-style: normal; color: #666b76; margin-left: 5px; }
.chatwrap { position: fixed; inset: 0; background: #0d0e12; color: #dcddde;
  display: flex; flex-direction: column; z-index: 10; }

/* 顶栏 */
.top { display: flex; gap: 6px; align-items: center; padding: 8px 10px; background: #14161c; border-bottom: 1px solid #23262e; }
.tb { background: #1a1d26; color: #dcddde; border: 1px solid #23262e; border-radius: 18px; padding: 6px 12px; font-size: 13px; }
.tb.name { max-width: 40vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tb.stop { background: #3b1f24; border-color: #5c2b30; color: #f2a4a4; }
.fb { background: none; border: 0; font-size: 15px; padding: 2px 4px; }
.car { opacity: .6; font-size: 10px; }
.sp { flex: 1; }

/* 弹层 */
.backdrop { position: fixed; inset: 0; z-index: 60; }
.drop { position: fixed; top: 100px; left: 12px; right: 12px; max-width: 420px; z-index: 61;
  background: #1a1d26; border: 1px solid #2c303b; border-radius: 16px; padding: 8px; box-shadow: 0 12px 40px rgba(0,0,0,.5); }
.drop.small { right: auto; min-width: 180px; }
.dh { font-size: 12px; color: #8b8f98; padding: 8px 10px 4px; }
.dh.big { font-size: 15px; color: #dcddde; font-weight: 700; }
.di { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px; border-radius: 10px; }
.di.sel { background: rgba(139,92,246,.14); }
.din { display: flex; flex-direction: column; gap: 1px; }
.dim { font-size: 11px; color: #8b8f98; }
.tag { font-size: 10px; color: #a78bfa; }

/* 消息流 */
.flow { flex: 1; overflow-y: auto; padding: 14px 12px 8px; }
.empty { text-align: center; padding: 60px 20px; }
.logo { font-size: 44px; margin-bottom: 10px; }
.et { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
.mrow { margin: 12px 0; }
.urow { display: flex; justify-content: flex-end; }
.ub { max-width: 85%; background: linear-gradient(135deg, rgba(139,92,246,.42), rgba(139,92,246,.28));
  border: 1px solid rgba(139,92,246,.35); padding: 10px 14px; border-radius: 16px 16px 4px 16px; }
.ut { white-space: pre-wrap; font-size: 14.5px; line-height: 1.6; }
.uimg { max-width: 180px; border-radius: 10px; margin-bottom: 6px; }
.ameta { font-size: 10px; color: #666b76; margin: 0 0 3px 4px; }
.ab { max-width: 92%; }
.ab.live .caret { color: #a78bfa; animation: blink 1s step-start infinite; }
@keyframes blink { 50% { opacity: 0; } }

/* markdown 正文 */
.md { font-size: 14.5px; line-height: 1.65; white-space: normal; }
.md :deep(pre) { background: #14161c; border: 1px solid #23262e; border-radius: 10px; padding: 10px 12px; overflow-x: auto; font-size: 12.5px; margin: 8px 0; }
.md :deep(code) { font-family: ui-monospace, monospace; color: #c9b8f8; }
.md :deep(p) { margin: 6px 0; }
.md :deep(ul), .md :deep(ol) { padding-left: 20px; margin: 6px 0; }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { margin: 12px 0 6px; font-size: 15px; }
.md :deep(table) { border-collapse: collapse; margin: 8px 0; }
.md :deep(th), .md :deep(td) { border: 1px solid #2c303b; padding: 4px 10px; font-size: 12.5px; }

/* 思考折叠 */
.think { margin: 6px 0; }
.th-toggle { background: none; border: 0; color: #8b8f98; font-size: 12px; text-align: left;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 3px 0; display: block; }
.chev { display: inline-block; width: 14px; }
.th-body { border-left: 2px solid #2c303b; padding: 4px 0 4px 10px; margin: 4px 0 4px 6px;
  font-size: 12px; color: #9ea3ad; white-space: pre-wrap; max-height: 300px; overflow-y: auto; }
.th-live { color: #a78bfa; }
.dots::after { content: '…'; animation: dots 1.2s steps(4) infinite; }
@keyframes dots { 0% { content: ''; } 25% { content: '.'; } 50% { content: '..'; } 75% { content: '...'; } }

/* 工具卡折叠 */
.tc { background: #14161c; border: 1px solid #23262e; border-radius: 12px; margin: 6px 0; overflow: hidden; }
.tc-toggle { width: 100%; background: none; border: 0; color: #dcddde; text-align: left; padding: 9px 12px;
  font-size: 13px; display: flex; align-items: center; gap: 7px; }
.tc-sum { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.tc-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.tc-dot.run { background: #e8a33d; animation: pulse 1s infinite; }
.tc-dot.ok { background: #3ecf72; }
.tc-dot.err { background: #e05555; }
@keyframes pulse { 50% { opacity: .4; } }
.tc-body { border-top: 1px solid #23262e; padding: 8px 12px; }
.tc-sec { font-size: 10.5px; color: #8b8f98; margin: 6px 0 3px; }
.tc-pre { background: #0d0e12; border-radius: 8px; padding: 8px 10px; font-size: 11.5px;
  white-space: pre-wrap; word-break: break-all; max-height: 220px; overflow-y: auto; color: #b8bcc6; margin: 0; }
.errt { color: #e05555; }

/* 状态条 */
.foot { display: flex; align-items: center; padding: 4px 14px; font-size: 11px; background: #14161c; border-top: 1px solid #23262e; }
.ttsc { display: flex; align-items: center; gap: 4px; color: #8b8f98; }
.ttsc input { width: auto; }

/* 语音浮条 */
.vbar { position: fixed; bottom: 132px; left: 50%; transform: translateX(-50%); z-index: 70;
  background: #1a1d26; border: 1px solid #2c303b; color: #a78bfa; font-size: 13px; font-weight: 700;
  border-radius: 20px; padding: 8px 18px; box-shadow: 0 8px 24px rgba(0,0,0,.5); }

/* 输入区 */
.composer { position: relative; background: #14161c; border-top: 1px solid #23262e; padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); }
.errbn { background: #2a181c; color: #e08585; font-size: 12.5px; border-radius: 10px; padding: 8px 12px; margin-bottom: 8px; border: 1px solid #4a2626; }
.editbn { display: flex; align-items: center; justify-content: space-between; background: #2a2418; color: #e8b268;
  font-size: 12px; border-radius: 10px; padding: 7px 12px; margin-bottom: 8px; border: 1px solid #4a3c26; }
.editbn { gap: 6px; }
.ebtxt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.editbn button { background: none; border: 0; color: #e8b268; font-size: 12.5px; flex-shrink: 0; padding: 4px 8px; }
.editbn .ebgo { color: #fff; background: #e8b268; border-radius: 8px; font-weight: 700; }
.attrow { display: flex; gap: 8px; overflow-x: auto; scrollbar-width: none; padding: 2px 2px 8px; }
.attc { position: relative; flex-shrink: 0; width: 62px; height: 62px; border-radius: 11px; overflow: hidden;
  background: #1a1d26; border: 1px solid #2c303b; display: flex; align-items: center; justify-content: center; }
.attimg2 { width: 100%; height: 100%; object-fit: cover; }
.attfile2 { font-size: 16px; color: #8b8f98; display: flex; flex-direction: column; align-items: center; gap: 1px; }
.attfile2 i { font-style: normal; font-size: 8.5px; max-width: 54px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attx { position: absolute; top: 2px; right: 2px; width: 17px; height: 17px; border-radius: 50%;
  background: rgba(0,0,0,.7); color: #fff; border: 0; font-size: 9px; line-height: 1; }
.attadd { flex-shrink: 0; width: 62px; height: 62px; border-radius: 11px; border: 1px dashed #3a3e48;
  background: none; color: #666b76; font-size: 19px; }
.cb.dim { opacity: .35; }
.cb.edit { background: #e8b268; border-color: #e8b268; }
.recdot { display: block; width: 12px; height: 12px; border-radius: 50%; background: #fff; animation: pulse 1s infinite; }
.ctx { display: inline-flex; align-items: center; gap: 5px; background: none; border: 0; color: #8b8f98; font-size: 11px; padding: 0; }
.ctxnum { font-variant-numeric: tabular-nums; }
.sep { margin: 0 5px; opacity: .5; }
.cacheP { font-weight: 700; margin-left: 2px; }
.cacheP.hi { color: #7dd3a8; }
.cacheP.mid { color: #e8b268; }
.cacheP.lo { color: #e08585; }
.working { display: inline-flex; align-items: center; gap: 5px; color: #a78bfa; }
.wspin { width: 10px; height: 10px; border: 2px solid #3d3560; border-top-color: #a78bfa; border-radius: 50%; animation: rot 1s linear infinite; }
@keyframes rot { to { transform: rotate(360deg) } }
/* 服务端通知吐司 */
.toasts { position: absolute; top: 52px; left: 0; right: 0; z-index: 55; display: flex; flex-direction: column; align-items: center; gap: 6px; pointer-events: none; }
.toast { pointer-events: auto; max-width: 86%; background: rgba(26,29,38,.97); border: 1px solid #2c303b; color: #dcddde;
  font-size: 12.5px; border-radius: 12px; padding: 9px 14px; box-shadow: 0 8px 24px rgba(0,0,0,.5); line-height: 1.45; }
.toast.error { border-color: #4a2626; color: #e08585; }
.toast.warning { border-color: #4a3c26; color: #e8b268; }
.toast.info { border-color: #3d3560; color: #a78bfa; }
.tst-enter-active, .tst-leave-active { transition: all .25s ease; }
.tst-enter-from, .tst-leave-to { opacity: 0; transform: translateY(-10px); }
/* 快脑悬浮气泡 */
.vbub { position: absolute; left: 50%; transform: translateX(-50%); bottom: calc(100% + 10px); z-index: 40;
  max-width: 86%; background: rgba(26,29,38,.97); border: 1px solid #3d3560; border-radius: 16px;
  padding: 11px 15px; font-size: 14px; line-height: 1.6; color: #e8e9ec;
  box-shadow: 0 10px 36px rgba(0,0,0,.55); display: flex; align-items: baseline; gap: 7px; }
.vbadge { color: #a78bfa; font-size: 13px; flex-shrink: 0; }
.vstate { color: #a78bfa; font-size: 13px; }
.dots2::after { content: '…'; animation: kdots 1.2s steps(4) infinite; }
@keyframes kdots { 0% { content: ''; } 25% { content: '.'; } 50% { content: '..'; } 75% { content: '...'; } }
.vtxt { white-space: pre-wrap; word-break: break-all; }
.vcur { color: #a78bfa; animation: blink2 .9s step-start infinite; }
@keyframes blink2 { 50% { opacity: 0; } }
.vbf-enter-active, .vbf-leave-active { transition: all .2s ease; }
.vbf-enter-from, .vbf-leave-to { opacity: 0; transform: translateX(-50%) translateY(12px); }
.ctxbar { width: 56px; height: 5px; border-radius: 3px; background: #23262e; overflow: hidden; display: inline-block; }
.ctxbar i { display: block; height: 100%; background: #7dd3a8; border-radius: 3px; transition: width .3s; }
.ctx.warm .ctxbar i { background: #e8b268; }
.ctx.hot .ctxbar i { background: #e05555; }
.ctx.hot { color: #e08585; }
.ssearch { padding: 0 2px 8px; }
.ssearch input { width: 100%; background: #1a1d26; border: 1px solid #2c303b; color: #dcddde; border-radius: 9px; padding: 8px 11px; font-size: 13px; }
.ws { border-top: 1px solid #23262e; margin-top: 10px; padding-top: 8px; }
.cwdnow { font-size: 11px; padding: 0 4px 6px; word-break: break-all; }
.prow { display: flex; align-items: center; gap: 7px; padding: 8px 6px; border-radius: 8px; font-size: 12.5px; }
.prow.cur { background: rgba(139,92,246,.12); }
.pico { flex-shrink: 0; }
.ppath { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #dcddde; }
.pcur { color: #a78bfa; font-size: 10px; flex-shrink: 0; }
.pinput { display: flex; gap: 6px; padding: 8px 2px 4px; }
.pinput input { flex: 1; background: #1a1d26; border: 1px solid #2c303b; color: #dcddde; border-radius: 8px; padding: 7px 10px; font-size: 12px; min-width: 0; }
.pinput button { background: #8b5cf6; border: 0; color: #fff; border-radius: 8px; padding: 0 13px; font-size: 12px; }
.pcomp { padding: 4px 2px; }
.pci { font-size: 11.5px; color: #8b8f98; padding: 5px 6px; border-radius: 6px; font-family: ui-monospace, monospace; }
.crow { display: flex; gap: 8px; align-items: flex-end; }
.crow textarea { flex: 1; background: #1a1d26; border: 1px solid #2c303b; color: #dcddde;
  border-radius: 14px; padding: 12px 13px; font-size: 14.5px; font-family: inherit; resize: none;
  height: 46px; min-height: 46px; max-height: 110px; overflow-y: auto; }
input[type="checkbox"] { accent-color: #8b5cf6; }
.cb { width: 42px; height: 42px; border-radius: 50%; border: 1px solid #2c303b; background: #1a1d26;
  color: #dcddde; font-size: 16px; flex-shrink: 0; }
.cb.mic.rec { background: #e05555; border-color: #e05555; animation: pulse 1s infinite; }
.cb.send { background: #8b5cf6; border-color: #8b5cf6; color: #fff; }
.cb.send:disabled { opacity: .4; }

/* 会话抽屉 */
.drawer { position: fixed; top: 52px; bottom: 0; left: 0; width: 84vw; max-width: 340px; z-index: 61;
  background: #14161c; border-right: 1px solid #2c303b; padding: 14px 12px; overflow-y: auto;
  box-shadow: 12px 0 40px rgba(0,0,0,.45); }
.slist { margin-top: 8px; }
.si { padding: 11px 8px; border-bottom: 1px solid #1e2128; position: relative; border-radius: 8px; }
.sin { font-size: 14px; padding-right: 30px; }
.sdel { position: absolute; right: 8px; top: 12px; color: #e08585; font-size: 12px; padding: 3px 8px; border: 1px solid #4a2626; border-radius: 6px; background: #241a1a; }
</style>
