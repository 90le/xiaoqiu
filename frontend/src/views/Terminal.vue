<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { chat, wsSend, connect } from '../useChat.js'
import { tstore, poolAttach, poolDetach, createSession, killSession, restartSession, renameSession } from '../termStore.js'

const stage = ref(null)
const activeId = ref('')
const showMgr = ref(false)
const keysMode = ref('slim')     // slim | full | hide
const ctrlOn = ref(false), altOn = ref(false)
const renaming = ref(''), renameId = ref('')

const sessions = computed(() => tstore.order.map(id => tstore.sessions[id]).filter(Boolean))

function openShellDrawer() { window.dispatchEvent(new Event('xq-open-drawer')) }

function activate(id) {
  const s = tstore.sessions[id]
  if (!s) return
  activeId.value = id
  for (const sid of tstore.order) tstore.sessions[sid]?.el.classList.toggle('act', sid === id)
  requestAnimationFrame(() => setTimeout(() => {
    try { s.fit.fit() } catch {}
    s.term.focus()
  }, 80))
}

function newTerm() {
  const s = createSession(chat.state?.cwd)
  activate(s.id)
  setTimeout(() => { try { s.fit.fit(); s.term.focus() } catch {} }, 200)
}

function raw(data) {
  wsSend({ type: 'terminal_input', terminalId: activeId.value, data })
  tstore.sessions[activeId.value]?.term.focus()
}
function press(k) {
  if (k === 'CTRL') { ctrlOn.value = !ctrlOn.value; altOn.value = false; return }
  if (k === 'ALT') { altOn.value = !altOn.value; ctrlOn.value = false; return }
  let data = k
  if (ctrlOn.value && data.length === 1) {
    const c = data.toUpperCase().charCodeAt(0)
    if (c >= 64 && c <= 95) data = String.fromCharCode(c - 64)
  } else if (altOn.value && data.length === 1) data = '\x1b' + data
  ctrlOn.value = false; altOn.value = false
  raw(data)
}
const slim = { ESC: '\x1b', TAB: '\t', '↑': '\x1b[A', '↓': '\x1b[B', '←': '\x1b[D', '→': '\x1b[C', DEL: '\x7f', ENTER: '\r' }
const combos = [
  ['^C', '\x03'], ['^D', '\x04'], ['^Z', '\x1a'], ['^L', '\x0c'], ['^U', '\x15'], ['^W', '\x17'],
  ['^R', '\x12'], ['^A', '\x01'], ['^E', '\x05'], ['^K', '\x0b'], ['^Y', '\x19'], ['^P', '\x10'], ['^N', '\x0e'],
]
const alts = [['ALT+B', '\x1bb'], ['ALT+F', '\x1bf'], ['ALT+D', '\x1bd'], ['ALT+.', '\x1b.'], ['ALT+<', '\x1b[1~'], ['ALT+>', '\x1b[4~']]
const navs = [['PGUP', '\x1b[5~'], ['PGDN', '\x1b[6~'], ['HOME', '\x1b[H'], ['END', '\x1b[F']]
const syms = ['|','-','/','\\','~','`','$','#','%','^','&','*','(',')','[',']','{','}','<','>','=','+','.',',',';',':','\'','"','!','?','_','@']

function fmtAgo(t) {
  const s = Math.floor((Date.now() - t) / 1000)
  if (s < 60) return s + '秒前'
  if (s < 3600) return Math.floor(s / 60) + '分前'
  return Math.floor(s / 3600) + '时前'
}

onMounted(() => {
  if (chat.status !== 'open') connect()
  nextTick(() => {
    poolAttach(stage.value)
    if (!tstore.order.length) newTerm()
    else activate(tstore.order[tstore.order.length - 1])
  })
})
onUnmounted(() => {
  poolDetach() // 只藏不杀：会话活着，回来还在
})
</script>

<template>
  <div class="termwrap">
    <!-- 顶栏：☰ | 快速标签 | 管理 | 新建 -->
    <header class="tabs">
      <button class="tb tap" title="工作台" @click="openShellDrawer">☰</button>
      <button class="tb mgr tap" title="会话管理器" @click="showMgr = true">🗂</button>
      <div class="tabscroller">
        <div v-for="s in sessions" :key="s.id" class="tab tap" :class="{ act: s.id === activeId, dead: !s.alive }" @click="activate(s.id)">
          <span class="tdot" :class="{ on: s.alive }"></span>
          <span class="tlab">{{ s.title }}</span>
        </div>
      </div>
      <button class="tb newb tap" @click="newTerm">＋</button>
    </header>

    <!-- 终端舞台：pane 由会话池借入 -->
    <div ref="stage" class="stage"></div>

    <!-- 虚拟按键（三态：hide→浮球 / slim / full） -->
    <div v-if="keysMode !== 'hide'" class="vkeys" :class="{ full: keysMode === 'full' }">
      <div class="krow">
        <button class="vk mod tap" :class="{ on: ctrlOn }" @click="press('CTRL')">CTRL</button>
        <button class="vk mod tap" :class="{ on: altOn }" @click="press('ALT')">ALT</button>
        <button v-for="(v, k) in slim" :key="k" class="vk tap" @click="press(v)">{{ k }}</button>
        <button class="vk cc tap" @click="raw('\x03')">^C</button>
        <button class="vk cc tap" @click="raw('\x0c')">^L</button>
        <span class="kspace"></span>
        <button v-if="keysMode === 'slim'" class="vk exp tap" @click="keysMode = 'full'">▴</button>
        <button v-else class="vk exp on tap" @click="keysMode = 'slim'">▾</button>
        <button class="vk hide tap" @click="keysMode = 'hide'">⌨✕</button>
      </div>
      <template v-if="keysMode === 'full'">
        <div class="krow"><button v-for="[l, c] in combos" :key="l" class="vk cc tap" @click="raw(c)">{{ l }}</button></div>
        <div class="krow"><button v-for="[l, c] in alts" :key="l" class="vk alt tap" @click="raw(c)">{{ l }}</button><button v-for="[l, c] in navs" :key="l" class="vk tap" @click="raw(c)">{{ l }}</button></div>
        <div class="krow"><button v-for="s2 in syms" :key="s2" class="vk sym tap" @click="press(s2)">{{ s2 }}</button></div>
      </template>
    </div>
    <!-- 隐藏态浮球 -->
    <button v-else class="kfab tap" @click="keysMode = 'slim'">⌨</button>

    <!-- 会话管理器弹窗 -->
    <div v-if="showMgr" class="mask tap" @click="showMgr = false"></div>
    <div v-if="showMgr" class="mgr">
      <div class="mh">
        <b>终端会话</b><span class="muted" style="font-size:12px"> {{ sessions.length }} 个</span>
        <span class="sp"></span>
        <button class="mb tap" @click="newTerm(); showMgr = false">＋ 新建</button>
      </div>
      <div class="mlist">
        <div v-for="s in sessions" :key="s.id" class="mi tap" @click="activate(s.id); showMgr = false">
          <span class="tdot big" :class="{ on: s.alive }"></span>
          <div class="mib">
            <template v-if="renameId === s.id">
              <input v-model="renaming" class="rin" @click.stop @keydown.enter="renameSession(s.id, renaming); renameId = ''" @blur="renameSession(s.id, renaming); renameId = ''" />
            </template>
            <b v-else @dblclick.stop="renameId = s.id; renaming = s.title">{{ s.title }}</b>
            <span class="muted">{{ s.alive ? '运行中 · 活动 ' + fmtAgo(s.lastOut) : '已退出' + (s.exitCode != null ? '（码' + s.exitCode + '）' : '') }}</span>
          </div>
          <span class="sp"></span>
          <button class="mb tap" title="切换" @click.stop="activate(s.id); showMgr = false">▶</button>
          <button class="mb tap" title="重启" @click.stop="restartSession(s.id)">↻</button>
          <button class="mb del tap" title="关闭" @click.stop="killSession(s.id)">🗑</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.tpane { position: absolute; inset: 0; display: none; padding: 4px 6px; }
.tpane.act { display: block; }
.xq-term-pool { position: fixed; left: -9999px; top: 0; width: 100%; height: 100%; pointer-events: none; }
</style>

<style scoped>
.termwrap { position: fixed; inset: 0; background: #0d0e12; display: flex; flex-direction: column; z-index: 10; }
.tabs { display: flex; align-items: center; gap: 5px; padding: 6px 8px; background: #14161c; border-bottom: 1px solid #23262e; }
.tb { background: #1a1d26; color: #dcddde; border: 1px solid #23262e; border-radius: 9px; padding: 6px 10px; font-size: 14px; flex-shrink: 0; }
.tb.mgr { color: #a78bfa; }
.tabscroller { display: flex; gap: 4px; overflow-x: auto; flex: 1; scrollbar-width: none; }
.tab { display: flex; align-items: center; gap: 5px; background: #1a1d26; border: 1px solid #23262e; border-radius: 9px; padding: 5px 9px; font-size: 12px; color: #8b8f98; flex-shrink: 0; }
.tab.act { border-color: #8b5cf6; color: #dcddde; background: rgba(139,92,246,.1); }
.tab.dead { opacity: .5; }
.tdot { width: 6px; height: 6px; border-radius: 50%; background: #4a4e58; flex-shrink: 0; }
.tdot.on { background: #3ecf72; }
.tdot.big { width: 9px; height: 9px; margin: 0 8px 0 2px; }
.tlab { max-width: 84px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stage { flex: 1; position: relative; }
.stage :deep(.xterm) { height: 100%; }
.stage :deep(.xterm-viewport) { background: #0d0e12 !important; }
/* 虚拟按键 */
.vkeys { background: #14161c; border-top: 1px solid #23262e; }
.krow { display: flex; gap: 4px; padding: 5px 7px; overflow-x: auto; scrollbar-width: none; align-items: center; }
.vk { flex-shrink: 0; min-width: 36px; height: 34px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 8px; font-size: 12px; font-weight: 600; font-family: ui-monospace, monospace; }
.vk.mod { background: #232635; color: #a78bfa; border-color: #3d3560; }
.vk.mod.on { background: #8b5cf6; color: #fff; }
.vk.cc { color: #7dd3a8; background: #182227; border-color: #24402f; }
.vk.alt { color: #e8b268; background: #241f18; border-color: #4a3c26; }
.vk.sym { min-width: 30px; }
.vk.exp, .vk.hide { color: #a78bfa; }
.kspace { flex: 1; min-width: 4px; }
.kfab { position: fixed; right: 14px; bottom: 18px; z-index: 20; width: 44px; height: 44px; border-radius: 50%;
  background: #8b5cf6; color: #fff; border: 0; font-size: 18px; box-shadow: 0 6px 20px rgba(0,0,0,.5); }
/* 会话管理器 */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); z-index: 60; }
.mgr { position: fixed; left: 50%; transform: translateX(-50%); bottom: 0; width: 100%; max-width: 560px; max-height: 72vh;
  background: #14161c; border: 1px solid #2c303b; border-radius: 18px 18px 0 0; z-index: 61; display: flex; flex-direction: column;
  box-shadow: 0 -12px 40px rgba(0,0,0,.5); }
.mh { display: flex; align-items: center; gap: 8px; padding: 14px 16px 8px; color: #dcddde; font-size: 15px; }
.sp { flex: 1; }
.mb { background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 8px; padding: 6px 10px; font-size: 12px; flex-shrink: 0; }
.mb.del { color: #e08585; border-color: #4a2626; }
.mlist { overflow-y: auto; padding: 0 10px 14px; }
.mi { display: flex; align-items: center; gap: 6px; padding: 11px 8px; border-bottom: 1px solid #1e2128; }
.mib { display: flex; flex-direction: column; gap: 2px; font-size: 13.5px; color: #dcddde; min-width: 0; flex: 1; }
.mib .muted { font-size: 11px; }
.rin { background: #1a1d26; border: 1px solid #8b5cf6; color: #dcddde; border-radius: 6px; padding: 3px 8px; font-size: 13px; width: 130px; }
</style>
