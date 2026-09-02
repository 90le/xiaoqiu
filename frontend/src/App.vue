<script setup>
import { ref } from 'vue'
import Scenes from './views/Scenes.vue'
import Tools from './views/Tools.vue'
import Settings from './views/Settings.vue'

const view = ref('scenes')
function syncFromHash() {
  const h = location.hash
  view.value = h.includes('tools') ? 'tools' : h.includes('settings') ? 'settings' : 'scenes'
}
syncFromHash()
window.addEventListener('hashchange', syncFromHash)
</script>

<template>
  <Scenes v-if="view === 'scenes'" />
  <Tools v-else-if="view === 'tools'" />
  <Settings v-else />
</template>
