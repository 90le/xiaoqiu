<script setup>
import { ref } from 'vue'
const out = ref('')
const busy = ref(false)
async function run(tool, args, tag) {
  busy.value = true
  out.value = tag + ' 执行中…'
  try {
    const r = await fetch('/api/' + tool, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(args||{}) })
    const d = (await r.json()).structuredContent
    if (d.ok) {
      const s = typeof d.data === 'string' ? d.data : JSON.stringify(d.data, null, 1)
      out.value = tag + ' ✅\n' + (s.length > 1200 ? s.slice(0,1200)+'…' : s)
    } else out.value = tag + ' ❌ ' + (d.error ? d.error.message : '失败')
  } catch(e) { out.value = tag + ' ❌ 网络错误' }
  busy.value = false
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
    <div v-for="c in cards" :key="c.t" class="card tap" @click="run(c.tool, c.args||{}, c.t)">
      <div class="ico">{{ c.icon }}</div><div class="t">{{ c.t }}</div><div class="d">{{ c.d }}</div>
    </div>
  </div>
  <div v-if="out" class="card out">{{ out }}</div>
</template>
<style scoped>
.grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
.tap { cursor:pointer; transition:transform .06s; }
.tap:active { transform:scale(.97); }
.ico { font-size:26px; }
.t { font-size:15px; font-weight:600; margin:8px 0 3px; }
.d { font-size:12px; color:var(--muted); }
.out { white-space:pre-wrap; word-break:break-all; font-size:13px; }
</style>
