<script setup>
import { chat, api } from './useChat.js'
import { ref, watch, onMounted, onUnmounted } from 'vue'
import Dashboard from './views/Dashboard.vue'
import Chat from './views/Chat.vue'
import Device from './views/Device.vue'
import Broadcast from './views/Broadcast.vue'
import Macros from './views/Macros.vue'
import Memory from './views/Memory.vue'
import Tools from './views/Tools.vue'
import Settings from './views/Settings.vue'
import Terminal from './views/Terminal.vue'

const view = ref('dashboard')
const drawer = ref(false)
const online = ref(false)
let timer = null

const nav = [
  { id: 'dashboard', icon: '🏠', label: '总览' },
  { id: 'chat', icon: '💬', label: '对话' },
  { id: 'terminal', icon: '🖥', label: '终端' },
  { id: 'device', icon: '📱', label: '设备' },
  { id: 'broadcast', icon: '🔔', label: '播报' },
  { id: 'macros', icon: '🔁', label: '自动化' },
  { id: 'memory', icon: '🧠', label: '记忆' },
  { id: 'tools', icon: '🔧', label: '工具' },
  { id: 'settings', icon: '⚙️', label: '设置' },
]

function pick(h) {
  const id = nav.find(n => h.includes(n.id))?.id
  if (id) view.value = id
}
const openDrawerReq = () => { drawer.value = true }
// 唤醒语音会话的任务通道（全局，任意视图可用，不切屏）：
// 进 App 当前对话（api.prompt=活动会话）→ 简短确认 → 流结束口语化播报结果
window.__xiaoqiuTask = (t, speak) => {
  if (!t) return
  const H = { 'Content-Type': 'application/json' }
  const say = (text) => { try { fetch('/api/tts_speak', { method: 'POST', headers: H, body: JSON.stringify({ text }) }) } catch {} }
  api.prompt(t) // 发送进对话页当前活动会话
  if (!speak) return
  say('好嘞，这就办')
  const stop = watch(() => chat.streaming, (v, ov) => {
    if (v || !ov) return
    stop()
    setTimeout(async () => {
      try {
        const msgs = chat.state?.messages || []
        let last = null
        for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i]?.role === 'assistant') { last = msgs[i]; break }
        let text = (last?.content || []).map(b => (b.type === 'text' ? b.text : '')).join('').trim()
        if (!text) return
        let out = text
        if (text.length > 90) {
          try {
            const r = await fetch('/api/ai_humanize', { method: 'POST', headers: H, body: JSON.stringify({ kind: 'reply', text: text.slice(0, 4000) }) })
            const d = (await r.json())?.structuredContent
            if (d?.ok && d?.data) out = String(d.data)
          } catch {}
        }
        say(out.slice(0, 400))
      } catch {}
    }, 400)
  })
}

onMounted(() => {
  window.addEventListener('xq-open-drawer', openDrawerReq)
  pick(location.hash)
  window.addEventListener('hashchange', () => pick(location.hash))
  const ping = async () => {
    try { const r = await fetch('/ping'); online.value = (await r.json()).pong === true } catch { online.value = false }
  }
  ping(); timer = setInterval(ping, 5000)
})
onUnmounted(() => { clearInterval(timer); window.removeEventListener('xq-open-drawer', openDrawerReq) })
function go(id) { view.value = id; location.hash = '#' + id; drawer.value = false }
</script>

<template>
  <div class="shell">
    <header v-if="view !== 'chat' && view !== 'terminal'" class="top">
      <button class="burger tap" @click="drawer = !drawer">☰</button>
      <div class="brand">
        <span class="logo">丘</span>
        <span class="bname">小丘工作台</span>
      </div>
      <span class="dot" :class="online ? 'on' : 'off'" :title="online ? 'MCP 在线' : 'MCP 离线'"></span>
    </header>

    <transition name="drawer">
      <div v-if="drawer" class="mask" @click="drawer = false"></div>
    </transition>
    <transition name="drawer">
      <nav v-if="drawer" class="side">
        <div class="side-head">
          <span class="logo big">丘</span>
          <div><b>小丘</b><div class="muted" style="font-size:12px">v1.0.0 · 你说，我来办</div></div>
        </div>
        <div v-for="n in nav" :key="n.id" class="navi tap" :class="{ act: view === n.id }" @click="go(n.id)">
          <span class="ni">{{ n.icon }}</span>{{ n.label }}
        </div>
        <div class="side-foot muted">97+ 原生工具 · 九大家族</div>
      </nav>
    </transition>

    <main class="page">
      <Dashboard v-if="view === 'dashboard'" />
      <Chat v-else-if="view === 'chat'" />
      <Terminal v-else-if="view === 'terminal'" />
      <Device v-else-if="view === 'device'" />
      <Broadcast v-else-if="view === 'broadcast'" />
      <Macros v-else-if="view === 'macros'" />
      <Memory v-else-if="view === 'memory'" />
      <Tools v-else-if="view === 'tools'" />
      <Settings v-else-if="view === 'settings'" />
    </main>


  </div>
</template>

<style scoped>
.shell { min-height: 100vh; padding-bottom: 10px; }
.top { position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; background: rgba(247,243,236,.92); backdrop-filter: blur(8px); border-bottom: 1px solid var(--line); }
.burger { border: 0; background: none; font-size: 22px; color: var(--ink); padding: 4px 8px; }
.brand { display: flex; align-items: center; gap: 8px; flex: 1; }
.logo { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px;
  border-radius: 10px; background: var(--hill); color: #fff; font-weight: 700; font-size: 15px; }
.logo.big { width: 42px; height: 42px; font-size: 20px; }
.bname { font-weight: 700; font-size: 16px; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.on { background: var(--hill); box-shadow: 0 0 0 4px var(--hill-soft); }
.dot.off { background: var(--bad); box-shadow: 0 0 0 4px #f7e5e1; }
.mask { position: fixed; inset: 0; background: rgba(34,48,31,.35); z-index: 30; }
.side { position: fixed; top: 0; left: 0; bottom: 0; width: 264px; z-index: 31; background: var(--card);
  padding: 18px 12px; display: flex; flex-direction: column; gap: 2px; box-shadow: var(--shadow); overflow-y: auto; }
.side-head { display: flex; align-items: center; gap: 10px; padding: 6px 8px 16px; }
.navi { display: flex; align-items: center; gap: 10px; padding: 12px 10px; border-radius: 12px; font-size: 15px; }
.navi.act { background: var(--hill-soft); color: var(--hill); font-weight: 700; }
.ni { width: 24px; text-align: center; }
.side-foot { margin-top: auto; font-size: 12px; padding: 10px; }
.page { padding: 14px 14px 10px; max-width: 720px; margin: 0 auto; }
.drawer-enter-active, .drawer-leave-active { transition: all .2s ease; }
.drawer-enter-from, .drawer-leave-to { opacity: 0; transform: translateX(-16px); }
</style>
