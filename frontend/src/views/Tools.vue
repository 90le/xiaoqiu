<script setup>
import { ref, computed, onMounted } from 'vue'
const q = ref('')
const all = ref([])
const list = computed(() => all.value.filter(t => !q.value || (t.n + t.d).toLowerCase().includes(q.value.toLowerCase())))
onMounted(async () => {
  const r = await fetch('/api/tools_list', { method:'POST' })
  const d = (await r.json()).structuredContent
  all.value = (d.data || []).map(s => { const i = s.indexOf(' — '); return { n: s.slice(0,i), d: s.slice(i+3) } })
})
const cat = (n) => n.startsWith('ui_') ? '感知与操控' : n.startsWith('l2_') ? '特权' : n.startsWith('env_') ? '环境' : n.startsWith('files_') ? '文件' : n.startsWith('stt') || n === 'mic_record' ? '语音' : n.startsWith('sensors') ? '传感器' : n.match(/sms|calllog|contacts|notify|clipboard|tts/) ? '通讯' : '系统'
const groups = computed(() => {
  const g = {}
  for (const t of list.value) { const k = cat(t.n); (g[k] = g[k] || []).push(t) }
  return g
})
</script>
<template>
  <div class="h1">工具箱</div>
  <div class="sub">小丘的全部手脚</div>
  <input v-model="q" placeholder="搜索工具…">
  <div v-for="(items, g) in groups" :key="g" class="grp">
    <div class="gt">{{ g }} <span class="gc">{{ items.length }}</span></div>
    <div v-for="t in items" :key="t.n" class="card tool">
      <div class="n">{{ t.n }}</div><div class="d2">{{ t.d }}</div>
    </div>
  </div>
</template>
<style scoped>
.tool { padding:11px 14px; }
.n { font-size:13px; font-weight:600; color:var(--hill); }
.d2 { font-size:12.5px; margin-top:2px; }
.grp { margin-bottom:16px; }
.gt { font-size:13px; font-weight:700; margin:12px 4px 8px; color:var(--ink); }
.gc { color:var(--muted); font-weight:400; margin-left:6px; }
</style>
