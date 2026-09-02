<script setup>
import { ref, onMounted } from 'vue'
import QiuLogo from './components/QiuLogo.vue'
import Scenes from './views/Scenes.vue'
import Tools from './views/Tools.vue'
import Settings from './views/Settings.vue'

const tabs = [
  { id: 'chat', icon: '💬', label: '对话' },
  { id: 'scenes', icon: '⚡', label: '场景' },
  { id: 'tools', icon: '🔧', label: '工具' },
  { id: 'settings', icon: '⚙', label: '设置' },
]
const tab = ref('chat')
const online = ref(false)

async function ping() {
  try {
    const r = await fetch('/ping', { cache: 'no-store' })
    online.value = (await r.json()).pong === true
  } catch { online.value = false }
}
onMounted(() => { ping(); setInterval(ping, 8000) })
function go(id) {
  tab.value = id
  if (id === 'chat') location.href = 'http://127.0.0.1:8182/'
}
</script>
<template>
  <div class="app">
    <header class="hd">
      <QiuLogo :size="30" />
      <span class="brand">小丘</span>
      <span class="dot" :class="online ? 'on' : 'off'" :title="online ? '服务在线' : '离线'"></span>
      <span class="tag">你说，我来办</span>
    </header>

    <main class="body">
      <div v-show="tab === 'chat'" class="chat-wrap">
        <iframe src="http://127.0.0.1:8182/" class="chat-frame" title="对话"></iframe>
        <div class="chat-hint">💬 在上方对话框打字，或点底栏 🎙 说话</div>
      </div>
      <div v-show="tab === 'scenes'" class="pad"><Scenes /></div>
      <div v-show="tab === 'tools'" class="pad"><Tools /></div>
      <div v-show="tab === 'settings'" class="pad"><Settings /></div>
    </main>

  </div>
</template>

<style>
.app { display:flex; flex-direction:column; height:100vh; background:var(--bg); }
.hd { display:flex; align-items:center; gap:8px; padding:12px 16px 8px; }
.brand { font-size:19px; font-weight:700; letter-spacing:.5px; }
.dot { width:9px; height:9px; border-radius:50%; }
.dot.on { background:var(--hill); box-shadow:0 0 6px var(--hill); }
.dot.off { background:var(--bad); }
.tag { font-size:11px; color:var(--muted); margin-left:auto; }
.body { flex:1; overflow-y:auto; }
.chat-wrap { position:relative; width:100%; height:100%; }
.chat-frame { width:100%; height:100%; border:0; }
.chat-hint { position:absolute; bottom:10px; left:50%; transform:translateX(-50%); font-size:11px; color:var(--muted); background:rgba(247,243,236,.9); padding:4px 12px; border-radius:10px; white-space:nowrap; }
.pad { padding:14px 14px 24px; }
.nav { display:flex; border-top:1px solid var(--line); background:#fff; padding:6px 4px calc(6px + env(safe-area-inset-bottom)); }
.nav button { flex:1; border:0; background:none; display:flex; flex-direction:column; align-items:center; gap:2px; font-size:11px; color:var(--muted); padding:6px 0; transition:color .15s; }
.nav button.on { color:var(--hill); font-weight:600; }
.nav .ico { font-size:20px; }
</style>
