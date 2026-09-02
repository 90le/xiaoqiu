<script setup>
import { ref, computed, onMounted } from 'vue'
const q = ref('')
const all = ref([])
const list = computed(() => all.value.filter(t => !q.value || (t.n + t.d).includes(q.value)))
onMounted(async () => {
  const r = await fetch('/api/tools_list', { method:'POST' })
  const d = (await r.json()).structuredContent
  all.value = (d.data || []).map(s => { const i = s.indexOf(' — '); return { n: s.slice(0,i), d: s.slice(i+3) } })
})
</script>
<template>
  <div class="h1">工具箱</div>
  <div class="sub">小丘的全部手脚</div>
  <input v-model="q" placeholder="搜索工具…" @input="render">
  <div class="cnt muted">{{ list.length }} / {{ all.length }} 项</div>
  <div v-for="t in list" :key="t.n" class="card tool">
    <div class="n">{{ t.n }}</div><div class="d2">{{ t.d }}</div>
  </div>
</template>
<style scoped>
.tool { padding:11px 14px; }
.n { font-size:13px; font-weight:600; color:var(--hill); }
.d2 { font-size:12.5px; margin-top:2px; }
.cnt { font-size:12px; margin:10px 4px; }
</style>
