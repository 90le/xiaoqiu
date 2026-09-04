<script setup>
import { ref, onMounted } from 'vue'
import { call, outText } from '../api.js'

const tab = ref('vd')
const apps = ref([])
const appKw = ref('')
const out = ref('')
const busy = ref(false)

const vd = ref({ alive: false })
const launchPkg = ref('')
const shotTs = ref('')
const tapX = ref(500), tapY = ref(500)
const inputText = ref('')

const bright = ref(128)
const sysState = ref({ wifi: false, bluetooth: false, dnd: false, rotate: false })

async function refreshVd() {
  const r = await call('vd', { action: 'info' })
  vd.value = r.ok ? (r.data.alive ? r.data : { alive: false }) : { alive: false }
  if (vd.value.alive) shotTs.value = Date.now()
}
async function vdOp(action, extra = {}) {
  busy.value = true
  const r = await call('vd', { action, ...extra })
  out.value = outText(r)
  busy.value = false
  await refreshVd()
}
const imgData = ref('')
async function shot() {
  const r = await call('vd', { action: 'shot' })
  if (r.ok && r.data.png_b64) { imgData.value = 'data:image/png;base64,' + r.data.png_b64 }
  out.value = outText(r)
}
async function vdtap() {
  await call('vd', { action: 'tap', x: tapX.value, y: tapY.value })
  await shot()
}
async function vdinput() {
  if (!inputText.value) return
  const r = await call('ui_set_text', { text: inputText.value, display: undefined })
  out.value = outText(r)
}
async function loadApps() {
  const r = await call('apps_list', { filter: appKw.value, limit: 60 })
  apps.value = r.ok && Array.isArray(r.data) ? r.data : (r.ok && r.data?.apps) || []
}
async function appOp(pkg, op) {
  busy.value = true
  const r = op === 'launch'
    ? await call('apps_launch', { pkg })
    : await call('l2_exec', { cmd: `am force-stop ${pkg}` })
  out.value = outText(r)
  busy.value = false
}
async function applyBright() {
  const r = await call('settings_write', { ns: 'system', key: 'screen_brightness', value: String(bright.value) })
  out.value = outText(r)
}
async function toggleSys(action) {
  const cur = sysState.value[action]
  const r = await call('sysctl', { action, on: !cur })
  out.value = outText(r)
  await loadSys()
}
async function loadSys() {
  const r = await call('sysctl', { action: 'read' })
  if (r.ok && typeof r.data === 'object') {
    sysState.value = { wifi: r.data.wifi === '1', bluetooth: r.data.bluetooth === '1', dnd: r.data.dnd === '1', rotate: r.data.rotate === '1' }
  }
}
onMounted(() => { loadApps(); loadSys() })
</script>

<template>
  <div class="h1">设备</div>
  <div class="sub">副屏操控 · 应用 · 快捷设置</div>

  <div class="tabs">
    <span class="tb tap" :class="{ act: tab === 'vd' }" @click="tab = 'vd'; refreshVd()">副屏</span>
    <span class="tb tap" :class="{ act: tab === 'app' }" @click="tab = 'app'">应用</span>
    <span class="tb tap" :class="{ act: tab === 'qs' }" @click="tab = 'qs'">快捷设置</span>
  </div>

  <div v-if="tab === 'vd'" class="card">
    <div class="row">
      <b>隐形副屏</b>
      <span class="pill" :class="vd.alive ? 'g' : ''">{{ vd.alive ? '运行中 #' + vd.displayId : '未创建' }}</span>
    </div>
    <div class="row2">
      <button class="btn" @click="vdOp('create')">创建</button>
      <button class="btn warn" @click="vdOp('stop')">销毁</button>
    </div>
    <label>发射应用到副屏（包名）</label>
    <input v-model="launchPkg" placeholder="com.xingin.xhs" />
    <button class="btn" style="margin-top:8px" :disabled="busy || !launchPkg" @click="vdOp('launch', { pkg: launchPkg })">发射</button>

    <template v-if="vd.alive">
      <label>实时预览 <span class="muted">(/shots/vd-latest.png)</span></label>
      <div class="preview">
        <img v-if="imgData" :src="imgData" />
        <div v-else class="muted" style="padding:20px;text-align:center">点"刷新截图"获取副屏画面</div>
      </div>
      <div class="row2">
        <button class="btn ghost" @click="shot">刷新截图</button>
      </div>
      <label>点击（千分比坐标）</label>
      <div class="row2">
        <input v-model="tapX" type="number" /><input v-model="tapY" type="number" />
      </div>
      <button class="btn" style="margin-top:8px" @click="vdtap">点击并刷新预览</button>
    </template>
  </div>

  <div v-if="tab === 'app'" class="card">
    <label>应用搜索</label>
    <div class="row2">
      <input v-model="appKw" placeholder="微信 / xhs / meituan" />
      <button class="btn ghost" @click="loadApps">搜索</button>
    </div>
    <div class="alist">
      <div v-for="a in apps" :key="a.pkg || a" class="arow">
        <div class="ainfo"><b>{{ a.label || a }}</b><div class="muted" style="font-size:11px">{{ a.pkg || a }}</div></div>
        <div class="arow2">
          <button class="mini g" @click="appOp(a.pkg || a, 'launch')">启动</button>
          <button class="mini w" @click="appOp(a.pkg || a, 'stop')">强停</button>
        </div>
      </div>
    </div>
  </div>

  <div v-if="tab === 'qs'" class="card">
    <label>屏幕亮度 <span class="muted">{{ bright }}</span></label>
    <input type="range" min="10" max="255" v-model.number="bright" @change="applyBright" />
    <label>系统开关</label>
    <div class="grid2">
      <div class="qk tap" v-for="[k, label] in [['wifi','Wi-Fi'],['bluetooth','蓝牙'],['dnd','勿扰'],['rotate','自动旋转']]" :key="k"
        @click="toggleSys(k)">{{ label }}：{{ sysState[k] ? '开' : '关' }}</div>
    </div>
  </div>

  <div v-if="out" class="card out">{{ out }}</div>
</template>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 12px; }
.tb { padding: 8px 16px; border-radius: 20px; background: var(--card); border: 1px solid var(--line); font-size: 14px; }
.tb.act { background: var(--hill); color: #fff; border-color: var(--hill); font-weight: 700; }
.row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.row2 { display: flex; gap: 8px; margin-top: 6px; }
.row2 input { flex: 1; }
.pill { font-size: 12px; padding: 4px 10px; border-radius: 12px; background: var(--bg); }
.pill.g { background: var(--hill-soft); color: var(--hill); font-weight: 700; }
.preview { background: #111; border-radius: 12px; overflow: hidden; display: flex; justify-content: center; min-height: 120px; }
.preview img { width: 100%; object-fit: contain; }
.alist { max-height: 420px; overflow-y: auto; margin-top: 10px; }
.arow { display: flex; align-items: center; justify-content: space-between; padding: 9px 0; border-bottom: 1px dashed var(--line); }
.ainfo { font-size: 14px; }
.arow2 { display: flex; gap: 6px; }
.mini { border: 0; border-radius: 10px; padding: 7px 12px; font-size: 13px; font-weight: 600; }
.mini.g { background: var(--hill-soft); color: var(--hill); }
.mini.w { background: #f7e5e1; color: var(--bad); }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.qk { background: var(--hill-soft); border-radius: 12px; padding: 12px; color: var(--hill); font-weight: 600; font-size: 14px; text-align: center; }
.btn.ghost { background: var(--bg); color: var(--ink); }
.out { white-space: pre-wrap; word-break: break-all; font-size: 13px; }
</style>
