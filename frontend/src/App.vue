<script setup>
import { ref } from 'vue'
import Scenes from './views/Scenes.vue'
import Tools from './views/Tools.vue'
import Settings from './views/Settings.vue'
const view = ref(location.hash.includes('tools') ? 'tools' : location.hash.includes('settings') ? 'settings' : 'scenes')
window.addEventListener('hashchange', () => {
  view.value = location.hash.includes('tools') ? 'tools' : location.hash.includes('settings') ? 'settings' : 'scenes'
})
</script>
<template>
  <Scenes v-if="view === 'scenes'" />
  <Tools v-else-if="view === 'tools'" />
  <Settings v-else />
</template>
