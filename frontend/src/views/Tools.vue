<script setup>
import { ref, onMounted, computed } from 'vue'
import { call, outText } from '../api.js'

const tools = ref([])
const kw = ref('')
const openName = ref('')
const argsText = ref('{}')
const result = ref('')
const busy = ref(false)

const FAMS = [
  ['ui_', '🖐 无障碍操作'],
  ['vd', '🖥 隐形副屏'],
  ['notify', '🔔 通知'],
  ['memory', '🧠 记忆'],
  ['macro', '🔁 宏'],
  ['tts', '🔊 语音'],
  ['vision', '👁 视觉'],
  ['ocr', '👁 视觉'],
  ['sysctl', '⚙️ 系统设置'],
  ['settings_', '⚙️ 系统设置'],
  ['l2_', '🛡 特权通道'],
  ['app', '📱 应用'],
  ['alarm', '⏰ 闹钟定时'],
  ['intent', '🎯 Intent'],
  ['chat', '🧠 快脑'],
  ['pi_rpc', '🧠 慢脑'],
  ['voice', '🗣 播报'],
  ['ai_', '🧠 AI'],
]
function famOf(n) {
  for (const [p, label] of FAMS) if (n.startsWith(p)) return label
  return '📦 其他'
}
const grouped = computed(() => {
  const f = kw.value
  const list = tools.value.filter(t => !f || t.name.includes(f) || (t.desc || '').includes(f))
  const g = {}
  for (const t of list) (g[famOf(t.name)] = g[famOf(t.name)] || []).push(t)
  return g
})

async function load() {
  const r = await call('tools_list', {})
  const raw = r.ok ? r.data : []
  tools.value = raw.map(t => typeof t === 'string' ? { name: t, desc: '' } : { name: t.name, desc: (t.desc || t.description || '').split('：')[0].split('(')[0] })
}
function open(t) {
  openName.value = openName.value === t.name ? '' : t.name
  argsText.value = '{}'
  result.value = ''
}
async function run(t) {
  busy.value = true
  let args = {}
  try { args = JSON.parse(argsText.value || '{}') } catch { result.value = '❌ 参数不是合法 JSON'; busy.value = false; return }
  const r = await call(t.name, args)
  result.value = outText(r)
  busy.value = false
}
onMounted(load)
</script>

<template>
  <div class="h1">工具</div>
  <div class="sub">97+ 原生能力，全部可直达</div>

  <div class="card">
    <input v-model="kw" placeholder="搜索工具名或描述" />
  </div>

  <div v-for="(arr, fam) in grouped" :key="fam" class="card">
    <div class="sec">{{ fam }} <span class="muted" style="font-weight:400">({{ arr.length }})</span></div>
    <div v-for="t in arr" :key="t.name" class="trow">
      <div class="tap" style="flex:1" @click="open(t)">
        <b style="font-size:14px">{{ t.name }}</b>
        <div class="muted" style="font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ t.desc }}</div>
      </div>
    </div>
    <div v-if="openName && arr.some(x => x.name === openName)" class="caller">
      <label>{{ openName }} 参数（JSON）</label>
      <textarea v-model="argsText" rows="3"></textarea>
      <button class="btn" style="margin-top:8px" :disabled="busy" @click="run(arr.find(x => x.name === openName))">{{ busy ? '执行中…' : '▶ 执行' }}</button>
      <div v-if="result" class="res">{{ result }}</div>
    </div>
  </div>

  <div v-if="!tools.length" class="card muted">加载中…</div>
</template>

<style scoped>
.sec { font-weight: 700; font-size: 14px; margin-bottom: 8px; }
.trow { display: flex; padding: 9px 0; border-bottom: 1px dashed var(--line); }
.caller { background: var(--bg); border-radius: 12px; padding: 12px; margin-top: 8px; }
.res { margin-top: 10px; background: var(--card); border-radius: 10px; padding: 10px; font-size: 13px; white-space: pre-wrap; word-break: break-all; max-height: 300px; overflow-y: auto; }
</style>
