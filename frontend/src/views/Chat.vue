<script setup>
import { ref } from 'vue'
// 对话 = 小丘本体（pi-web-ui:8182 完整对话界面）
const loaded = ref(false)
</script>

<template>
  <div class="h1">对话</div>
  <div class="sub">和小丘说话，它来办</div>
  <div class="card chatwrap">
    <iframe v-if="loaded || true" src="http://127.0.0.1:8182/" @load="loaded = true"></iframe>
    <div v-if="!loaded" class="muted" style="padding:20px">连接小丘对话引擎…</div>
  </div>
</template>

<style scoped>
.chatwrap { padding: 0; overflow: hidden; height: calc(100vh - 190px); }
iframe { width: 100%; height: 100%; border: 0; }
</style>
