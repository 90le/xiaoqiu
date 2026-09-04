<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { chat, api, connect } from '../useChat.js'

const input = ref('')
const listEl = ref(null)
const showSessions = ref(false)
const showModel = ref(false)
const ttsOn = ref(localStorage.getItem('xq_tts2') !== 'false')
const recording = ref(false)
const voiceState = ref('')
const attachments = ref([]) // {name, path} 或 {name, dataUrl, mime, base64}
const fileEl = ref(null)

const st = computed(() => chat.state)
const msgs = computed(() => st.value?.messages || [])
const streaming = computed(() => st.value?.streamingMessage || null)
const busy = computed(() => !!st.value?.isStreaming)

// ── 消息块渲染 ──
function blocksOf(m) {
  const arr = (m?.content || []).filter(Boolean)
  if (m?.role === 'toolResult') return []
  return arr
}
function toolResultOf(id) {
  return msgs.value.find(x => x.role === 'toolResult' && x.toolCallId === id)
}
function fmtTs(t) { return t ? new Date(t).toLocaleTimeString('zh', { hour: '2-digit', minute: '2-digit' }) : '' }
function scroll() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }
watch(() => [msgs.value.length, streaming.value?.content?.length], scroll)
watch(busy, (b) => { if (!b && ttsOn.value) speakLast() })

// ── 发送 ──
function send() {
  const text = input.value.trim()
  if (!text || busy.value) return
  api.prompt(text, attachments.value.length ? attachments.value.map(a => a.path
    ? { path: a.path, mode: 'inline' }
    : { data: a.base64, mimeType: a.mime }) : undefined)
  input.value = ''; attachments.value = []
}
function onEnter(e) { if (!e.shiftKey && !e.isComposing) { e.preventDefault(); send() } }

// ── 附件 ──
function pickFile() { fileEl.value?.click() }
function onFile(e) {
  const f = e.target.files?.[0]
  if (!f) return
  if (f.type.startsWith('image/')) {
    const r = new FileReader()
    r.onload = () => {
      const dataUrl = r.result
      attachments.value.push({ name: f.name, dataUrl, mime: f.type, base64: dataUrl.split(',')[1] })
    }
    r.readAsDataURL(f)
  } else {
    attachments.value.push({ name: f.name, path: (st.value?.cwd || '/sdcard') + '/' + f.name })
  }
  e.target.value = ''
}
function rmAtt(i) { attachments.value.splice(i, 1) }

// ── 模型/思考 ──
function pickModel(m) { api.setModel(m.id); showModel.value = false }
const levels = computed(() => st.value?.availableThinkingLevels?.length ? st.value.availableThinkingLevels : ['off', 'low', 'medium', 'high'])

// ── 会话 ──
function openSessions() { api.listSessions(); showSessions.value = true }

// ── TTS ──
async function speakLast() {
  const last = [...msgs.value].reverse().find(m => m.role === 'assistant')
  if (!last) return
  const text = (last.content || []).filter(b => b.type === 'text').map(b => b.text).join(' ')
  if (!text) return
  const s = text.length > 220 ? text.slice(0, 220) + '……' : text
  try { await fetch('/api/tts_speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: s }) }) } catch {}
}

// ── 原生麦克风桥 ──
function micDown() {
  if (recording.value || busy.value) return
  if (!window.XiaoqiuBridge) { voiceState.value = '桥未就绪'; return }
  recording.value = true; voiceState.value = '🎙 录音中…松手结束'
  window.XiaoqiuBridge.startVoice()
}
function micUp() { if (recording.value) { recording.value = false; window.XiaoqiuBridge?.stopVoice() } }
window.__voiceResult = (t) => {
  voiceState.value = ''
  if (t) api.prompt(t) // 语音直接进 pi 会话
}
window.__voiceStatus = (s) => {
  if (s === 'recording') { recording.value = true; voiceState.value = '🎙 录音中…' }
  else if (s === 'thinking') { recording.value = false; voiceState.value = '识别中…' }
  else if (s === 'empty') { voiceState.value = '没听清，再试一次'; setTimeout(() => voiceState.value = '', 1800) }
  else if (s.startsWith('error')) { voiceState.value = s; setTimeout(() => voiceState.value = '', 2500) }
  else voiceState.value = ''
}
window.__xiaoqiuTask = (t) => { if (t) api.prompt(t) }

onMounted(() => { connect(); scroll() })
onUnmounted(() => {
  delete window.__voiceResult; delete window.__voiceStatus; delete window.__xiaoqiuTask
})
</script>

<template>
  <div class="h1">对话 <span v-if="chat.status !== 'open'" class="muted" style="font-size:12px">{{ chat.status === 'connecting' ? '连接中…' : '已断线，重连中' }}</span></div>

  <!-- 顶栏：模型 / 思考 / 会话 / 新建 -->
  <div class="card topbar">
    <button class="chip tap" @click="showModel = !showModel">🤖 {{ st?.model?.name || '模型' }} ▾</button>
    <div v-if="showModel" class="mpanel card">
      <div v-for="m in chat.models" :key="m.id" class="mrow tap" :class="{ sel: m.id === st?.model?.id }" @click="pickModel(m)">
        <b>{{ m.name }}</b><span class="muted" style="font-size:11px">{{ m.provider }}{{ m.vision ? ' ·👁' : '' }}{{ m.reasoning ? ' ·🧠' : '' }}</span>
      </div>
      <div v-if="!chat.models.length" class="muted">模型清单载入中…</div>
    </div>
    <div class="lvl">
      <button v-for="l in levels" :key="l" class="chip sm tap" :class="{ sel: l === st?.thinkingLevel }" @click="api.setThinking(l)">{{ l }}</button>
    </div>
    <span class="spacer"></span>
    <button class="chip tap" @click="openSessions">🗂 会话</button>
    <button class="chip tap" @click="api.newChat()">＋ 新对话</button>
  </div>

  <!-- 消息流 -->
  <div class="card chatbox" ref="listEl">
    <div v-if="!msgs.length && !streaming" class="empty">
      <div class="big">🏔</div>
      <div class="muted">和小丘说话或打字。<br>我能操作手机、跑命令、写代码、查记忆。</div>
    </div>

    <div v-for="m in msgs" :key="m.id" class="msgrow">
      <div v-if="m.role === 'user'" class="msg user"><div class="bubble u">
        <div v-for="(b, bi) in blocksOf(m)" :key="bi">
          <img v-if="b.type === 'image' && b.dataUrl" :src="b.dataUrl" class="attimg" />
          <template v-else>{{ b.text }}</template>
        </div>
      </div></div>

      <div v-else-if="m.role === 'assistant'" class="msg ai">
        <div class="meta muted">{{ fmtTs(m.timestamp) }} · {{ m.model }}</div>
        <div class="bubble a">
          <template v-for="(b, bi) in blocksOf(m)" :key="bi">
            <div v-if="b.type === 'thinking'" class="think">💭 {{ b.thinking }}</div>
            <div v-else-if="b.type === 'toolCall'" class="tool">
              <b>🛠 {{ b.name }}</b>
              <pre class="targs">{{ (b.argumentsText || '').slice(0, 300) }}</pre>
              <div v-if="toolResultOf(b.id)" class="tres" :class="{ err: toolResultOf(b.id).isError }">
                {{ String(toolResultOf(b.id).content?.[0]?.text || toolResultOf(b.id).content?.[0] || '').slice(0, 500) }}
              </div>
            </div>
            <div v-else-if="b.type === 'text'" class="txt">{{ b.text }}</div>
          </template>
        </div>
      </div>
    </div>

    <!-- 流式中的消息 -->
    <div v-if="streaming" class="msg ai">
      <div class="bubble a streaming">
        <template v-for="(b, bi) in streaming.content || []" :key="bi">
          <div v-if="b.type === 'thinking'" class="think">💭 {{ b.thinking }}</div>
          <div v-else-if="b.type === 'toolCall'" class="tool"><b>🛠 {{ b.name }}</b><pre class="targs">{{ (b.argumentsText || '').slice(0, 300) }}</pre></div>
          <div v-else-if="b.type === 'text'" class="txt">{{ b.text }}</div>
        </template>
        <span class="cursor">▍</span>
      </div>
    </div>
  </div>

  <!-- 底部状态：tokens/上下文 -->
  <div v-if="st?.stats" class="statline muted">
    💬{{ st.stats.totalMessages }} · tokens {{ st.stats.tokens.total }} · 上下文 {{ st.stats.contextUsage.percent ?? '—' }}% · ${{ (st.stats.cost || 0).toFixed(3) }}
    <span v-if="busy" class="spin"> ⟳</span>
    <button v-if="busy" class="chip sm tap" style="margin-left:8px" @click="api.abort()">⏹ 停止</button>
  </div>

  <div v-if="voiceState" class="card vstate">{{ voiceState }}</div>

  <!-- 输入区 -->
  <div class="card inputrow">
    <div v-if="attachments.length" class="atts">
      <span v-for="(a, i) in attachments" :key="i" class="att">📎{{ a.name }} <b class="tap" @click="rmAtt(i)">✕</b></span>
    </div>
    <div class="irow">
      <button class="round tap" title="附件" @click="pickFile">📎</button>
      <input ref="fileEl" type="file" hidden @change="onFile" />
      <button class="round mic tap" :class="{ rec: recording }" title="按住说话"
        @touchstart.prevent="micDown" @touchend.prevent="micUp" @mousedown="micDown" @mouseup="micUp">🎙</button>
      <input v-model="input" placeholder="打字或按住🎙说话（Enter 发送）" @keydown="onEnter" />
      <button class="round send tap" :disabled="busy || !input.trim()" @click="send">➤</button>
    </div>
    <label class="chk"><input type="checkbox" v-model="ttsOn" @change="localStorage.setItem('xq_tts2', ttsOn)" /> 完成后朗读</label>
  </div>

  <!-- 会话抽屉 -->
  <div v-if="showSessions" class="mask tap" @click="showSessions = false"></div>
  <div v-if="showSessions" class="sespanel card">
    <div class="sec">历史会话（{{ chat.sessions.length }}）</div>
    <div class="slist">
      <div v-for="s in chat.sessions" :key="s.path" class="srow tap" @click="api.switchSession(s.path); showSessions = false">
        <div class="sname"><b>{{ s.name || s.firstMessage?.slice(0, 24) || '会话' }}</b>
          <span v-if="s.source === 'tui'" class="muted" style="font-size:10px"> [CLI]</span></div>
        <div class="muted" style="font-size:11px">{{ s.messageCount }} 条 · {{ new Date(s.modified).toLocaleString('zh', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}</div>
        <span class="sdel tap" @click.stop="api.deleteSession(s.path)">✕</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topbar { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; position: relative; padding: 10px 12px; }
.chip { border: 1px solid var(--line); background: var(--bg); border-radius: 16px; padding: 6px 12px; font-size: 13px; }
.chip.sel { background: var(--hill); color: #fff; border-color: var(--hill); }
.chip.sm { font-size: 11px; padding: 4px 9px; }
.spacer { flex: 1; }
.mpanel { position: absolute; top: 46px; left: 10px; z-index: 40; width: 260px; max-height: 300px; overflow-y: auto; }
.mrow { padding: 9px 10px; border-bottom: 1px dashed var(--line); display: flex; justify-content: space-between; gap: 6px; }
.mrow.sel { background: var(--hill-soft); }
.lvl { display: flex; gap: 4px; }
.chatbox { height: calc(100vh - 305px); overflow-y: auto; padding: 12px; }
.empty { text-align: center; padding: 26px 10px; }
.big { font-size: 38px; margin-bottom: 8px; }
.msgrow { margin: 10px 0; }
.msg { display: flex; flex-direction: column; }
.msg.user { align-items: flex-end; }
.msg.ai { align-items: flex-start; }
.meta { font-size: 10px; margin-bottom: 2px; }
.bubble { max-width: 88%; padding: 10px 13px; border-radius: 16px; font-size: 14.5px; line-height: 1.6; word-break: break-word; }
.bubble.u { background: var(--hill); color: #fff; border-bottom-right-radius: 5px; }
.bubble.a { background: var(--bg); border-bottom-left-radius: 5px; }
.txt { white-space: pre-wrap; }
.think { color: var(--muted); font-size: 12.5px; border-left: 3px solid var(--line); padding-left: 8px; margin: 4px 0; white-space: pre-wrap; }
.tool { background: var(--card); border: 1px solid var(--line); border-radius: 10px; padding: 8px; margin: 6px 0; font-size: 12.5px; }
.targs { margin: 4px 0 0; white-space: pre-wrap; color: var(--muted); font-size: 11px; max-height: 90px; overflow-y: auto; }
.tres { margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--line); white-space: pre-wrap; font-size: 11.5px; color: var(--muted); max-height: 120px; overflow-y: auto; }
.tres.err { color: var(--bad); }
.attimg { max-width: 180px; border-radius: 10px; }
.streaming .cursor { animation: blink 1s step-start infinite; color: var(--hill); }
@keyframes blink { 50% { opacity: 0; } }
.statline { font-size: 11px; padding: 2px 6px; display: flex; align-items: center; }
.spin { display: inline-block; animation: rot 1s linear infinite; }
@keyframes rot { to { transform: rotate(360deg) } }
.vstate { text-align: center; color: var(--hill); font-weight: 700; font-size: 13px; padding: 8px; }
.inputrow { padding: 10px 12px; }
.atts { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.att { background: var(--hill-soft); color: var(--hill); font-size: 12px; padding: 4px 10px; border-radius: 12px; }
.irow { display: flex; gap: 8px; align-items: center; }
.round { width: 42px; height: 42px; border-radius: 50%; border: 0; background: var(--bg); color: var(--ink); font-size: 16px; flex-shrink: 0; }
.mic { background: var(--hill); color: #fff; }
.mic.rec { background: var(--bad); animation: pulse 1s infinite; }
@keyframes pulse { 50% { box-shadow: 0 0 0 8px rgba(194,75,60,.25); } }
.send { background: var(--dawn); color: #fff; }
.irow input { flex: 1; }
.chk { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); margin: 8px 0 0; }
.chk input { width: auto; }
.mask { position: fixed; inset: 0; background: rgba(34,48,31,.3); z-index: 50; }
.sespanel { position: fixed; top: 60px; left: 12px; right: 12px; z-index: 51; max-height: 70vh; overflow-y: auto; }
.sec { font-weight: 700; font-size: 14px; margin-bottom: 8px; }
.srow { padding: 10px 8px; border-bottom: 1px dashed var(--line); position: relative; }
.sname { font-size: 14px; padding-right: 26px; }
.sdel { position: absolute; right: 8px; top: 12px; color: var(--bad); }
</style>
