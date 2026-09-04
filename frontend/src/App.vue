<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import Dashboard from './views/Dashboard.vue'
import Chat from './views/Chat.vue'
import Device from './views/Device.vue'
import Broadcast from './views/Broadcast.vue'
import Macros from './views/Macros.vue'
import Memory from './views/Memory.vue'
import Tools from './views/Tools.vue'
import Settings from './views/Settings.vue'

const view = ref('dashboard')
const drawer = ref(false)
const online = ref(false)
let timer = null

const nav = [
  { id: 'dashboard', icon: '🏠', label: '总览' },
  { id: 'chat', icon: '💬', label: '对话' },
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
    <header v-if="view !== 'chat'" class="top">
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
