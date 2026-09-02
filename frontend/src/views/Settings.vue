<script setup>
import { ref, onMounted } from 'vue'
import QiuLogo from '../components/QiuLogo.vue'
const provider = ref('zai-coding-cn')
const key = ref('')
const model = ref('glm-5.3-flash')
const msg = ref('')
const msgOk = ref(false)
const perm = ref(null)
const ball = ref(false)

async function save() {
  msg.value = '保存中…'
  try {
    const r = await fetch('/api/setkey', { method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ provider: provider.value, key: key.value, model: model.value }) })
    const d = (await r.json()).structuredContent
    msgOk.value = !!d.ok
    msg.value = d.ok ? d.data : (d.error ? d.error.message : '失败')
  } catch(e) { msgOk.value = false; msg.value = '网络错误' }
}
async function loadPerm() {
  try {
    const r = await fetch('/api/perm_status', { method:'POST' })
    perm.value = (await r.json()).structuredContent.data
  } catch(e) { perm.value = null }
}
async function toggleBall() {
  await fetch('/api/floatball', { method:'POST' })
  await new Promise(r => setTimeout(r, 600))
  await loadPerm()
}
function openPerm(type) {
  fetch('/api/open_permission_settings', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ type }) })
}
const permRows = [
  { k:'accessibility', label:'无障碍', type:'a11y' },
  { k:'overlay', label:'悬浮窗', type:'overlay' },
  { k:'allFiles', label:'所有文件', type:'allfiles' },
  { k:'queueBridge', label:'队列桥', type:null },
  { k:'envReady', label:'pi 环境', type:null },
  { k:'webui', label:'对话服务', type:null },
]
onMounted(loadPerm)
</script>
<template>
  <div style="display:flex;align-items:center;gap:8px;margin:6px 4px 2px;">
    <QiuLogo :size="34" /><span class="h1" style="margin:0;">小丘设置</span>
  </div>
  <div class="sub">你说，我来办。</div>

  <div class="card">
    <label>API 供应商</label>
    <select v-model="provider">
      <option value="zai-coding-cn">智谱（zai-coding-cn）</option>
      <option value="zhipu">智谱开放平台</option>
      <option value="custom">自定义</option>
    </select>
    <label>API Key</label>
    <input v-model="key" type="password" placeholder="粘贴你的 API Key">
    <label>默认模型</label>
    <input v-model="model" type="text">
    <button class="btn" @click="save">保存</button>
    <div class="msg" :class="msgOk ? 'ok' : 'bad'">{{ msg }}</div>
  </div>

  <div class="card">
    <div style="font-weight:600;font-size:14px;margin-bottom:8px;">权限中心</div>
    <div v-if="perm">
      <div v-for="r in permRows" :key="r.k" class="kv">
        <span>{{ r.label }}</span>
        <span :class="{ ok: perm[r.k] }" style="cursor:pointer;" @click="r.type && openPerm(r.type)">
          {{ perm[r.k] ? '✅' : '❌ 点击授权' }}
        </span>
      </div>
    </div>
    <div v-else class="muted">加载中…</div>
  </div>

  <div class="card" style="display:flex;justify-content:space-between;align-items:center;">
    <div><div style="font-weight:600;font-size:14px;">🏔 悬浮球</div>
    <div style="font-size:12px;color:var(--muted);">保活可见 · 单击回小丘 · 贴边吸附</div></div>
    <button class="btn" style="width:auto;padding:10px 16px;" @click="toggleBall(); loadPerm()">切换</button>
  </div>

  <div class="card">
    <div class="kv"><span>版本</span><span>1.0.0-dev</span></div>
    <div class="kv"><span>引擎</span><span>pi coding-agent</span></div>
    <div class="kv"><span>语音识别</span><span>SenseVoice 离线</span></div>
  </div>
</template>
<style scoped>
.kv { display:flex; justify-content:space-between; font-size:13px; padding:6px 0; border-bottom:1px dashed var(--line); }
.kv:last-child { border:0; }
.ok { color:var(--hill); font-weight:600; }
.msg { margin-top:12px; font-size:14px; text-align:center; min-height:18px; }
.msg.ok { color:var(--hill); } .msg.bad { color:var(--bad); }
</style>
