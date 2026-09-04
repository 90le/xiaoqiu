<script setup>
import { ref, onMounted } from 'vue'
import { call, outText } from '../api.js'

const cards = ref({})
const busy = ref({})
const device = ref(null)
const vd = ref(null)
const battery = ref(null)

const quick = [
  { i: '📸', t: '截屏', tool: 'screenshot' },
  { i: '📶', t: '网络', tool: 'network_info' },
  { i: '📋', t: '剪贴板', tool: 'clipboard_read' },
  { i: '🔋', t: '电池', tool: 'battery_status' },
  { i: '🔔', t: '最新通知', tool: 'notify_read', args: { limit: 3 } },
  { i: '🗣', t: '错过的事', tool: 'voice_digest' },
]

async function run(tool, args, tag) {
  busy.value = { ...busy.value, [tag]: true }
  const r = await call(tool, args || {})
  cards.value = { ...cards.value, [tag]: outText(r) }
  busy.value = { ...busy.value, [tag]: false }
}

async function refresh() {
  const d = await call('device_state', {})
  device.value = d.ok ? d.data : null
  const v = await call('vd', { action: 'info' })
  vd.value = v.ok ? v.data : null
  const b = await call('battery_status', {})
  battery.value = b.ok ? (b.data.level ?? b.data.percent ?? null) : null
}
onMounted(refresh)
</script>

<template>
  <div class="h1">总览</div>
  <div class="sub">小丘的一切，一眼看清</div>

  <div class="card">
    <div class="sec">系统状态</div>
    <div v-if="device" class="grid2">
      <div class="stat"><span class="muted">锁屏</span><b>{{ device.locked ? '是' : '否' }}</b></div>
      <div class="stat"><span class="muted">亮屏</span><b>{{ device.screen_on ? '是' : '否' }}</b></div>
      <div class="stat"><span class="muted">副屏</span><b>{{ vd && vd.alive ? '#' + vd.displayId : '未创建' }}</b></div>
      <div class="stat"><span class="muted">电量</span><b>{{ battery ?? '—' }}<template v-if="battery != null">%</template></b></div>
    </div>
    <div v-else class="muted">读取中…</div>
    <button class="btn ghost" style="margin-top:10px" @click="refresh">刷新状态</button>
  </div>

  <div class="card">
    <div class="sec">快捷能力</div>
    <div class="grid2">
      <div v-for="c in quick" :key="c.t" class="qk tap" @click="!busy[c.t] && run(c.tool, c.args || {}, c.t)">
        <div class="qi">{{ c.i }}</div><div class="qt">{{ c.t }}</div>
      </div>
    </div>
  </div>

  <div v-for="(v, k) in cards" :key="k" class="card out"><b>{{ k }}</b><br>{{ v }}</div>
</template>

<style scoped>
.sec { font-weight: 700; font-size: 14px; margin-bottom: 10px; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.stat { background: var(--bg); border-radius: 12px; padding: 10px 12px; display: flex; flex-direction: column; gap: 2px; font-size: 14px; }
.qk { background: var(--hill-soft); border-radius: 12px; padding: 12px; display: flex; align-items: center; gap: 8px; color: var(--hill); font-weight: 600; font-size: 14px; }
.qi { font-size: 18px; }
.out { white-space: pre-wrap; word-break: break-all; font-size: 13px; }
.btn.ghost { background: var(--bg); color: var(--ink); }
</style>
