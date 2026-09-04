<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue'
import { call } from '../api.js'

const msgs = ref(JSON.parse(localStorage.getItem('xq_chat') || '[]'))
const input = ref('')
const thinking = ref(false)
const voiceState = ref('')
const recording = ref(false)
const ttsOn = ref(localStorage.getItem('xq_tts') !== 'false')
const listEl = ref(null)

function save() { localStorage.setItem('xq_chat', JSON.stringify(msgs.value.slice(-80))) }
function scroll() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }
function speak(t) {
  const s = t.length > 200 ? t.slice(0, 200) + '……' : t
  call('tts_speak', { text: s })
}

async function handleSend(text, fromVoice = false) {
  text = (text || '').trim()
  if (!text || thinking.value) return
  msgs.value.push({ role: 'user', text, ts: Date.now() })
  save(); scroll()
  input.value = ''
  thinking.value = true
  try {
    // 快慢脑分流：闲聊快脑秒答（语音来的一定开口念），任务交慢脑真执行
    const r = await call('chat_fast', { q: text })
    if (r.ok && r.data && r.data.type === 'chat') {
      msgs.value.push({ role: 'assistant', text: r.data.answer, ts: Date.now() })
      save(); scroll()
      if (fromVoice || ttsOn.value) speak(r.data.answer)
    } else {
      msgs.value.push({ role: 'assistant', text: '🛠 收到，交给慢脑执行中…', ts: Date.now(), note: true })
      save(); scroll()
      const p = await call('pi_rpc', { prompt: text, wait_sec: 180 })
      const ans = p.ok ? (typeof p.data === 'string' ? p.data : JSON.stringify(p.data)) : ('❌ ' + (p.error?.message || '执行失败'))
      msgs.value.pop() // 把过渡语替换成真答案
      msgs.value.push({ role: 'assistant', text: ans, ts: Date.now() })
      save(); scroll()
      if (fromVoice && p.ok) speak(ans)
    }
  } catch (e) {
    msgs.value.push({ role: 'assistant', text: '❌ 网络异常，稍后再试', ts: Date.now() })
  }
  thinking.value = false
  save(); scroll()
}

// ── 原生麦克风桥（按住说话）──
function micDown() {
  if (recording.value || thinking.value) return
  if (!window.XiaoqiuBridge) { voiceState.value = '桥未就绪'; return }
  recording.value = true
  voiceState.value = 'recording'
  window.XiaoqiuBridge.startVoice()
}
function micUp() {
  if (!recording.value) return
  recording.value = false
  window.XiaoqiuBridge && window.XiaoqiuBridge.stopVoice()
}
function onVoiceResult(t) {
  voiceState.value = ''
  handleSend(t, true) // 语音输入 → 快脑能答就开口念
}
function onVoiceStatus(s) {
  if (s === 'recording') { recording.value = true; voiceState.value = '🎙 录音中…松手结束' }
  else if (s === 'thinking') { recording.value = false; voiceState.value = '识别中…' }
  else if (s === 'empty') { voiceState.value = '没听清，再试一次'; setTimeout(() => voiceState.value = '', 1800) }
  else if (s.startsWith('error')) { voiceState.value = '🎙 ' + s; setTimeout(() => voiceState.value = '', 2500) }
}
function onConvoState(s) { voiceState.value = s.split('|')[1] || s }
function onTask(t, speakReply) { handleSend(t, speakReply) }

onMounted(() => {
  window.__voiceResult = onVoiceResult
  window.__voiceStatus = onVoiceStatus
  window.__convoState = onConvoState
  window.__xiaoqiuTask = onTask
  scroll()
})
onUnmounted(() => {
  delete window.__voiceResult; delete window.__voiceStatus
  delete window.__convoState; delete window.__xiaoqiuTask
})
</script>

<template>
  <div class="h1">对话</div>
  <div class="sub">和小丘说话，它来办</div>

  <div class="card chatbox" ref="listEl">
    <div v-if="!msgs.length" class="empty">
      <div class="big">🏔</div>
      <div class="muted">跟我说话，或打字。<br>我能操作手机、写代码、查记忆、跑自动化。</div>
    </div>
    <div v-for="(m, i) in msgs" :key="i" class="msg" :class="m.role">
      <div class="bubble">{{ m.text }}</div>
    </div>
    <div v-if="thinking" class="msg assistant"><div class="bubble note">⏳ 小丘思考中…</div></div>
  </div>

  <div v-if="voiceState" class="card vstate">{{ voiceState }}</div>

  <div class="card inputrow">
    <button class="mic tap" :class="{ rec: recording }"
        @touchstart.prevent="micDown" @touchend.prevent="micUp" @mousedown="micDown" @mouseup="micUp">🎙</button>
    <input v-model="input" placeholder="打字，或按住麦克风说话"
        @keydown.enter="handleSend(input)" />
    <button class="send tap" :disabled="thinking || !input.trim()" @click="handleSend(input)">➤</button>
  </div>
  <div class="card opts">
    <label class="chk"><input type="checkbox" v-model="ttsOn" @change="localStorage.setItem('xq_tts', ttsOn)" /> 回答自动朗读</label>
    <span class="muted" style="font-size:11px">麦克风按住说话 · 快脑秒答 · 任务走慢脑真执行</span>
  </div>
</template>

<style scoped>
.chatbox { height: calc(100vh - 260px); overflow-y: auto; padding: 12px; }
.empty { text-align: center; padding: 30px 10px; }
.big { font-size: 40px; margin-bottom: 10px; }
.msg { display: flex; margin: 8px 0; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }
.bubble { max-width: 82%; padding: 10px 13px; border-radius: 16px; font-size: 14.5px; line-height: 1.55;
  white-space: pre-wrap; word-break: break-all; }
.msg.user .bubble { background: var(--hill); color: #fff; border-bottom-right-radius: 5px; }
.msg.assistant .bubble { background: var(--bg); border-bottom-left-radius: 5px; }
.bubble.note { color: var(--muted); font-size: 13px; }
.vstate { text-align: center; color: var(--hill); font-weight: 700; font-size: 14px; padding: 10px; }
.inputrow { display: flex; gap: 8px; align-items: center; }
.mic { width: 46px; height: 46px; border-radius: 50%; border: 0; background: var(--hill); color: #fff; font-size: 19px; flex-shrink: 0; }
.mic.rec { background: var(--bad); animation: pulse 1s infinite; }
@keyframes pulse { 50% { box-shadow: 0 0 0 8px rgba(194,75,60,.25); } }
.inputrow input { flex: 1; }
.send { width: 46px; height: 46px; border-radius: 50%; border: 0; background: var(--dawn); color: #fff; font-size: 17px; flex-shrink: 0; }
.opts { padding: 10px 14px; }
.chk { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink); margin: 0 0 6px; }
.chk input { width: auto; }
</style>
