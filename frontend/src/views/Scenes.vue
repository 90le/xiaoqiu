<script setup>
import { ref } from 'vue'
const results = ref({})
const busy = ref({})
async function run(tool, args, tag) {
  busy.value = { ...busy.value, [tag]: true }
  try {
    const r = await fetch('/api/' + tool, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(args||{}) })
    const d = (await r.json()).structuredContent
    const s = d.ok ? (typeof d.data === 'string' ? d.data : JSON.stringify(d.data, null, 1)) : '❌ ' + (d.error ? d.error.message : '失败')
    results.value = { ...results.value, [tag]: (d.ok ? '✅ ' : '') + (s.length > 900 ? s.slice(0,900)+'…' : s) }
  } catch(e) { results.value = { ...results.value, [tag]: '❌ 网络错误' } }
  busy.value = { ...busy.value, [tag]: false }
}
const cards = [
  { icon:'🔋', t:'电池状态', d:'电量/温度/充电', tool:'battery_status' },
  { icon:'📶', t:'网络信息', d:'WiFi/流量/信号', tool:'network_info' },
  { icon:'📸', t:'截个屏', d:'保存当前屏幕', tool:'screenshot' },
  { icon:'📱', t:'设备信息', d:'型号/系统/存储', tool:'device_info' },
  { icon:'📦', t:'应用统计', d:'已装应用列表', tool:'apps_list', args:{ limit: 200 } },
  { icon:'🔆', t:'屏幕状态', d:'亮灭/亮度', tool:'screen_state' },
]
</script>
<template>
  <div class="h1">场景</div>
  <div class="sub">一键直达，小丘替你办</div>
  <div class="grid">
    <div v-for="c in cards" :key="c.t" class="card tap" @click="!busy[c.t] && run(c.tool, c.args||{}, c.t)">
      <div class="ico">{{ c.icon }}</div>
      <div class="t">{{ c.t }} <span v-if="busy[c.t]" class="spin">⟳</span></div>
      <div class="d">{{ c.d }}</div>
    </div>
  </div>
  <div v-for="(v, k) in results" :key="k" class="card out"><b>{{ k }}</b><br>{{ v }}</div>
</template>
<style scoped>
.grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
.tap { cursor:pointer; transition:transform .06s, box-shadow .15s; }
.tap:active { transform:scale(.97); }
.ico { font-size:26px; }
.t { font-size:15px; font-weight:600; margin:8px 0 3px; }
.d { font-size:12px; color:var(--muted); }
.spin { display:inline-block; animation:rot 1s linear infinite; color:var(--hill); }
@keyframes rot { to { transform:rotate(360deg) } }
.out { white-space:pre-wrap; word-break:break-all; font-size:13px; }
</style>
