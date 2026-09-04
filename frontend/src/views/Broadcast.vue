<script setup>
import { ref, onMounted } from 'vue'
import { call, outText, cfgAll, cfgSet } from '../api.js'

const logs = ref([])
const cfg = ref({})
const digest = ref('')
const out = ref('')
const busy = ref(false)

const boolKeys = [
  ['notify_announce', '实时播报总开关'],
  ['notify_announce_ai', 'AI 拟人化改写'],
]
const textKeys = [
  ['notify_announce_pkgs', '白名单包名（逗号分隔）'],
  ['notify_announce_exclude', '免打扰关键词（逗号分隔）'],
  ['notify_announce_engine', '播报引擎覆盖（空=跟随全局）'],
]
const waitMs = ref(10000)

async function load() {
  cfg.value = await cfgAll()
  waitMs.value = Number(cfg.value.notify_announce_wait || 10000)
  const r = await call('notify_read', { limit: 20 })
  logs.value = r.ok && Array.isArray(r.data) ? r.data : (r.ok && r.data?.items) || []
}
async function setBool(k) { cfg.value[k] = cfg.value[k] === 'true' ? 'false' : 'true'; await cfgSet(k, cfg.value[k]) }
async function setText(k) { await cfgSet(k, cfg.value[k] || ''); out.value = '已保存 ' + k }
async function setWait() { await cfgSet('notify_announce_wait', String(waitMs.value)); out.value = '平稳期已设 ' + waitMs.value + 'ms' }
async function doDigest() {
  busy.value = true
  const r = await call('voice_digest', {})
  digest.value = r.ok ? r.data.digest : '失败：' + outText(r)
  busy.value = false
}
onMounted(load)
</script>

<template>
  <div class="h1">播报</div>
  <div class="sub">小丘的听觉：谁在找你、说了什么</div>

  <div class="card">
    <div class="sec">错过了什么</div>
    <button class="btn" :disabled="busy" @click="doDigest">{{ busy ? '整理中…' : '生成摘要' }}</button>
    <div v-if="digest" class="digest">🗣 {{ digest }}</div>
  </div>

  <div class="card">
    <div class="sec">播报配置 <span class="muted" style="font-weight:400;font-size:12px">（即改即生效）</span></div>
    <div v-for="[k, label] in boolKeys" :key="k" class="trow tap" @click="setBool(k)">
      <span>{{ label }}</span>
      <span class="sw" :class="{ on: cfg[k] === 'true' }"><i></i></span>
    </div>
    <div v-for="[k, label] in textKeys" :key="k">
      <label>{{ label }}</label>
      <div class="row2">
        <input v-model="cfg[k]" />
        <button class="btn ghost" @click="setText(k)">存</button>
      </div>
    </div>
    <label>聚合平稳期（毫秒）</label>
    <div class="row2">
      <input v-model.number="waitMs" type="number" step="1000" />
      <button class="btn ghost" @click="setWait">存</button>
    </div>
  </div>

  <div class="card">
    <div class="sec">最近通知流</div>
    <div v-if="!logs.length" class="muted">暂无</div>
    <div v-for="(l, i) in logs" :key="i" class="lrow">
      <b class="lt">{{ l.title || l.pkg }}</b>
      <div class="lx">{{ l.text }}</div>
      <div class="muted" style="font-size:11px">{{ new Date(l.time).toLocaleTimeString() }}</div>
    </div>
  </div>

  <div v-if="out" class="card out">{{ out }}</div>
</template>

<style scoped>
.sec { font-weight: 700; font-size: 14px; margin-bottom: 10px; }
.digest { margin-top: 12px; background: var(--hill-soft); color: var(--hill); border-radius: 12px; padding: 12px; font-size: 14px; line-height: 1.6; }
.trow { display: flex; align-items: center; justify-content: space-between; padding: 11px 0; border-bottom: 1px dashed var(--line); font-size: 14px; }
.sw { width: 44px; height: 26px; border-radius: 14px; background: var(--line); position: relative; transition: background .2s; }
.sw.on { background: var(--hill); }
.sw i { position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: #fff; transition: left .2s; }
.sw.on i { left: 21px; }
.row2 { display: flex; gap: 8px; }
.row2 input { flex: 1; }
.btn.ghost { background: var(--bg); color: var(--ink); width: auto; padding: 10px 16px; }
.lrow { padding: 9px 0; border-bottom: 1px dashed var(--line); }
.lt { font-size: 14px; }
.lx { font-size: 13px; color: var(--ink); margin: 3px 0; word-break: break-all; }
.out { white-space: pre-wrap; font-size: 13px; }
</style>
