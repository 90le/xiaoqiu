<script setup>
import { ref, onMounted } from 'vue'
import { call, outText } from '../api.js'

const macros = ref([])
const out = ref('')
const busyName = ref('')
const runName = ref('')
const p1 = ref(''), p2 = ref(''), p3 = ref('')
const speak = ref(true)

async function load() {
  const r = await call('macro_list', {})
  macros.value = r.ok ? (Array.isArray(r.data) ? r.data : r.data?.macros || []) : []
}
async function run(m) {
  busyName.value = m.name
  const args = { name: m.name || m, speak: speak.value }
  if (runName.value === (m.name || m)) { if (p1.value) args.p1 = p1.value; if (p2.value) args.p2 = p2.value; if (p3.value) args.p3 = p3.value }
  const r2 = await call('macro_run', args)
  out.value = '▶ ' + (m.name || m) + '\n' + outText(r2)
  busyName.value = ''
}
async function del(m) {
  const r = await call('macro_del', { name: m.name || m })
  out.value = outText(r)
  await load()
}
async function exp(m) {
  const r = await call('macro_export', { name: m.name || m })
  out.value = outText(r)
}
onMounted(load)
</script>

<template>
  <div class="h1">自动化</div>
  <div class="sub">宏 = 跑通即固化的技能，越用越聪明</div>

  <div class="card">
    <div class="sec">运行参数（可选）</div>
    <label>选中宏的参数 p1 / p2 / p3</label>
    <div class="row3">
      <input v-model="p1" placeholder="p1" /><input v-model="p2" placeholder="p2" /><input v-model="p3" placeholder="p3" />
    </div>
    <label class="chk"><input type="checkbox" v-model="speak" /> 完成后语音播报</label>
  </div>

  <div v-for="m in macros" :key="m.name || m" class="card">
    <div class="mrow">
      <div>
        <b>{{ m.name || m }}</b>
        <div class="muted" style="font-size:12px">{{ m.desc || '' }} {{ m.steps ? '· ' + m.steps + ' 步' : '' }}</div>
      </div>
      <button class="btn go" :disabled="busyName === (m.name || m)" @click="run(m)">{{ busyName === (m.name || m) ? '⟳' : '▶' }}</button>
    </div>
    <div class="mops">
      <span class="mop tap" @click="runName = m.name || m">设参数</span>
      <span class="mop tap" @click="exp(m)">导出</span>
      <span class="mop tap bad" @click="del(m)">删除</span>
    </div>
  </div>

  <div v-if="!macros.length" class="card muted">暂无宏。对话里让小丘跑通的流程，用 macro_from_session 固化成宏。</div>
  <div v-if="out" class="card out">{{ out }}</div>
</template>

<style scoped>
.row3 { display: flex; gap: 8px; }
.row3 input { flex: 1; }
.chk { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--ink); }
.chk input { width: auto; }
.mrow { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.btn.go { width: 46px; padding: 10px 0; font-size: 17px; }
.mops { display: flex; gap: 14px; margin-top: 8px; font-size: 12px; }
.mop { color: var(--muted); }
.mop.bad { color: var(--bad); }
.out { white-space: pre-wrap; word-break: break-all; font-size: 13px; }
</style>
