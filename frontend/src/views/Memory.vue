<script setup>
import { ref, onMounted, computed } from 'vue'
import { call, outText } from '../api.js'

const mems = ref([])
const kw = ref('')
const out = ref('')
const openKey = ref('')

const groups = computed(() => {
  const g = {}
  for (const [k, v] of Object.entries(mems.value)) {
    const fam = k.includes('.') ? k.split('.')[0] : 'other'
    ;(g[fam] = g[fam] || []).push([k, v])
  }
  if (kw.value) {
    const f = {}
    for (const [fam, arr] of Object.entries(g)) {
      const hit = arr.filter(([k, v]) => k.includes(kw.value) || String(v).includes(kw.value))
      if (hit.length) f[fam] = hit
    }
    return f
  }
  return g
})
const famName = { voice: '🗣 播报记忆', app: '📱 应用知识', user: '👤 用户偏好', other: '📦 其他' }

async function load() {
  const r = await call('memory_list', {})
  mems.value = r.ok ? (r.data || {}) : {}
}
async function del(k) {
  const r = await call('memory_del', { key: k })
  out.value = outText(r)
  await load()
}
onMounted(load)
</script>

<template>
  <div class="h1">记忆</div>
  <div class="sub">小丘知道的一切，都在这里</div>

  <div class="card">
    <label>搜索</label>
    <input v-model="kw" placeholder="关键词 / 发送人 / key" />
  </div>

  <div v-for="(arr, fam) in groups" :key="fam" class="card">
    <div class="sec">{{ famName[fam] || fam }} <span class="muted" style="font-weight:400">({{ arr.length }})</span></div>
    <div v-for="[k, v] in arr" :key="k" class="mrow">
      <div class="mbody tap" @click="openKey = openKey === k ? '' : k">
        <b style="font-size:13px">{{ k }}</b>
        <div class="mv" :class="{ open: openKey === k }">{{ v }}</div>
      </div>
      <span class="mdel tap" @click="del(k)">✕</span>
    </div>
  </div>

  <div v-if="!Object.keys(groups).length" class="card muted">暂无记忆。播报的微信消息、应用知识、用户偏好都会自动沉淀到这里。</div>
  <div v-if="out" class="card out">{{ out }}</div>
</template>

<style scoped>
.sec { font-weight: 700; font-size: 14px; margin-bottom: 8px; }
.mrow { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 8px 0; border-bottom: 1px dashed var(--line); }
.mbody { flex: 1; min-width: 0; }
.mv { font-size: 13px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mv.open { white-space: pre-wrap; word-break: break-all; color: var(--ink); }
.mdel { color: var(--bad); font-size: 14px; padding: 2px 6px; }
.out { white-space: pre-wrap; font-size: 13px; }
</style>
