<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
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
watch(() => st.value?.isStreaming, (b, old) => { if (b === false && old === true && ttsOn.value) speakLast() })

const editId = ref(''), editDraft = ref('')
function startEdit(m) {
  editId.value = m.id
  editDraft.value = (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n')
}
function submitEdit(m) {
  const text = editDraft.value.trim()
  if (!text) return
  wsSend({ type: 'edit_message', messageId: m.id, text })
  editId.value = ''
}
function send(mode) { // mode: undefined=普通/steer插队, 'queue'=排队
  const text = input.value.trim()
  if (!text) return
  const atts = attachments.value.length ? attachments.value.map(a => a.path
    ? { path: a.path, mode: 'inline' }
    : { data: a.base64, mimeType: a.mime }) : undefined
  api.prompt(text, atts)
  input.value = ''; attachments.value = ''
  attachments.value = []
}
function sendQueued() { api.prompt(input.value.trim(), undefined, true); input.value = '' }
function rmQueued(kind, text) { wsSend({ type: 'queue_remove', kind, text }) }
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
window.__voiceResult = (t) => { voiceState.value = ''; if (t) api.prompt(t) }
window.__voiceStatus = (s) => {
  if (s === 'recording') { recording.value = true; voiceState.value = '🎙 录音中…' }
  else if (s === 'thinking') { recording.value = false; voiceState.value = '识别中…' }
  else if (s === 'empty') { voiceState.value = '没听清，再试一次'; setTimeout(() => voiceState.value = '', 1800) }
  else if (s.startsWith('error')) { voiceState.value = s; setTimeout(() => voiceState.value = '', 2500) }
  else voiceState.value = ''
}
window.__xiaoqiuTask = (t) => { if (t) api.prompt(t) }

onMounted(() => { connect(); scroll() })
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
      <button v-if="st?.isStreaming" class="tb stop tap" @click="api.abort()">⏹ 停止</button>
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
          <template v-if="editId === m.id">
            <div class="ueditbox">
              <textarea v-model="editDraft" rows="3"></textarea>
              <div class="ueditops">
                <button class="ubtn cancel tap" @click="editId = ''">取消</button>
                <button class="ubtn ok tap" @click="submitEdit(m)">✓ 保存并发送</button>
              </div>
            </div>
          </template>
          <template v-else>
            <div class="ub">
              <img v-for="(b, bi) in (m.content||[]).filter(b => b.type === 'image' && b.dataUrl)" :key="bi" :src="b.dataUrl" class="uimg" />
              <div class="ut">{{ (m.content||[]).filter(b => b.type === 'text').map(b => b.text).join('\n') }}</div>
            </div>
            <div class="uact">
              <button v-if="!busy" class="ua tap" @click="startEdit(m)">✎ 编辑</button>
              <button class="ua tap" @click="navigator.clipboard?.writeText((m.content||[]).filter(b => b.type === 'text').map(b => b.text).join('\n'))">📋</button>
            </div>
          </template>
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
          <div class="ub queued"><span class="qtag steer">插队</span>{{ q }}<span class="qrm tap" @click="rmQueued('steering', q)">✕</span></div>
        </div>
        <div v-for="(q, i) in (st.queue.followUp || [])" :key="'f' + i" class="mrow urow">
          <div class="ub queued"><span class="qtag follow">排队</span>{{ q }}<span class="qrm tap" @click="rmQueued('followUp', q)">✕</span></div>
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
        {{ st.stats.tokens.total }} tok · 上下文 {{ st.stats.contextUsage.percent ?? '—' }}% · ${{ (st.stats.cost || 0).toFixed(3) }}
      </template>
      <span class="sp"></span>
      <button class="fb tap" :title="ttsOn ? '朗读开' : '朗读关'" @click="ttsOn = !ttsOn; localStorage.setItem('xq_tts2', ttsOn)">{{ ttsOn ? '🔊' : '🔇' }}</button>
    </div>

    <!-- 语音状态浮条 -->
    <div v-if="voiceState" class="vbar">{{ recording ? '⏺ ' : '' }}{{ voiceState }}</div>

    <!-- 输入区 -->
    <div class="composer">
      <div v-if="attachments.length" class="atts">
        <span v-for="(a, i) in attachments" :key="i" class="att">
          <img v-if="a.dataUrl" :src="a.dataUrl" class="att-thumb" />📎{{ a.name }}
          <b class="tap" @click="rmAtt(i)">✕</b>
        </span>
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
        <textarea v-model="input" rows="1" placeholder="发消息…" @keydown.enter.exact.prevent="send" @paste="onPaste"></textarea>
        <button class="cb mic tap" :class="{ rec: recording }"
          @touchstart.prevent="micDown" @touchend.prevent="micUp" @mousedown="micDown" @mouseup="micUp">🎙</button>
        <button v-if="busy && input.trim()" class="cb q tap" title="排队：本轮全部结束后再发" @click="sendQueued">⏳</button>
        <button class="cb send tap" :disabled="!input.trim()" @click="send">{{ busy ? '⤴' : '➤' }}</button>
      </div>
    </div>

    <!-- 历史会话抽屉 -->
    <div v-if="menu === 'sessions'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'sessions'" class="drawer">
      <div class="dh big">历史会话 <span class="muted">({{ chat.sessions.length }})</span></div>
      <div class="slist">
        <div v-for="s in chat.sessions" :key="s.path" class="si tap" @click="api.switchSession(s.path); menu = ''">
          <div class="sin"><b>{{ s.name || s.firstMessage?.slice(0, 26) || '会话' }}</b>
            <span v-if="s.source === 'tui'" class="tag">CLI</span></div>
          <div class="dim">{{ s.messageCount }} 条 · {{ new Date(s.modified).toLocaleString('zh', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}</div>
          <span class="sdel tap" @click.stop="api.deleteSession(s.path); setTimeout(() => api.listSessions(), 500)">🗑 删</span>
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
/* 内联消息编辑器 */
.ueditbox { background: rgba(139,92,246,.1); border: 1px solid rgba(139,92,246,.35); border-radius: 14px; padding: 10px; max-width: 85%; }
.ueditbox textarea { width: 100%; background: #1a1d26; border: 1px solid #2c303b; color: #e8e9ec;
  border-radius: 9px; padding: 9px 11px; font-size: 14px; font-family: inherit; resize: vertical; min-height: 64px; }
.ueditops { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
.ubtn { border-radius: 9px; padding: 7px 14px; font-size: 12.5px; font-weight: 700; border: 1px solid; }
.ubtn.cancel { background: none; color: #8b8f98; border-color: #2c303b; }
.ubtn.ok { background: #8b5cf6; color: #fff; border-color: #8b5cf6; }
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
.atts { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.att { display: inline-flex; align-items: center; gap: 5px; background: #1a1d26; border: 1px solid #2c303b;
  color: #dcddde; font-size: 12px; border-radius: 10px; padding: 4px 8px; }
.att-thumb { width: 22px; height: 22px; object-fit: cover; border-radius: 5px; }
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
