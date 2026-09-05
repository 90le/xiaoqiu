<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { chat, wsSend, connect } from '../useChat.js'
import { tstore, poolAttach, poolDetach, createSession, killSession, restartSession, renameSession } from '../termStore.js'
import '@xterm/xterm/css/xterm.css'

const stage = ref(null)
const activeId = ref('')
const tabMenu = ref(null) // { id, title, x }
let pressTimer = null
function tabPress(s, e) {
  const x = e.touches ? e.touches[0].clientX : e.clientX
  pressTimer = setTimeout(() => {
    if (navigator.vibrate) try { navigator.vibrate(15) } catch {}
    tabMenu.value = { id: s.id, title: s.title, x: Math.min(Math.max(x - 70, 8), window.innerWidth - 156) }
  }, 480)
}
function tabRelease() { if (pressTimer) clearTimeout(pressTimer); pressTimer = null }
const keysMode = ref('bar')     // bar（收缩：核心键） | full（展开：+扩展行）——常驻底部无悬浮球
// 完整键盘布局（QWERTY；press() 应用 CTRL/ALT/SHIFT 粘滞组合）
const kbRows = [
  ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
  ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
  ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
  ['z', 'x', 'c', 'v', 'b', 'n', 'm'],
]
const kbSyms = ['`', '~', '|', '-', '/', '\\', ':', ';', '\'', '"', '[', ']', '{', '}', '<', '>', '(', ')', '$', '#', '%', '&', '*', '+', '=', '_', '!', '?', '@', '^', '.']
const dpadOff = ref(localStorage.getItem('xq_dpad_off') === '1')  // 悬浮方向键
function saveDpad(off) { try { localStorage.setItem('xq_dpad_off', off ? '1' : '0') } catch {} }
const ctrlOn = ref(false), altOn = ref(false)
const renaming = ref(''), renameId = ref('')
const confirmKill = ref('')
let killTimer = null
function resetConfirm(id) { if (killTimer) clearTimeout(killTimer); killTimer = setTimeout(() => { if (confirmKill.value === id) confirmKill.value = '' }, 3000) }

const sessions = computed(() => tstore.order.map(id => tstore.sessions[id]).filter(Boolean))

function openShellDrawer() { window.dispatchEvent(new Event('xq-open-drawer')) }
function showKb() { try { window.XiaoqiuBridge && window.XiaoqiuBridge.showKeyboard() } catch {} }
function activate(id) {
  const s = tstore.sessions[id]
  if (!s) return
  activeId.value = id
  // 关键：新建的 pane 在隐藏池里，必须搬进舞台（否则黑屏——只切类不搬家）
  if (stage.value && s.el.parentNode !== stage.value) stage.value.appendChild(s.el)
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
const shiftOn = ref(false)
function press(k) {
  if (k === 'CTRL') { ctrlOn.value = !ctrlOn.value; altOn.value = false; shiftOn.value = false; return }
  if (k === 'ALT') { altOn.value = !altOn.value; ctrlOn.value = false; shiftOn.value = false; return }
  if (k === 'SHIFT') { shiftOn.value = !shiftOn.value; ctrlOn.value = false; altOn.value = false; return }
  let data = k
  if (ctrlOn.value && data.length === 1) {
    const c = data.toUpperCase().charCodeAt(0)
    if (c >= 64 && c <= 95) data = String.fromCharCode(c - 64)
  } else if (altOn.value && data.length === 1) data = '\x1b' + data
  else if (shiftOn.value && data.length === 1) data = data.toUpperCase()
  ctrlOn.value = false; altOn.value = false; shiftOn.value = false
  raw(data)
}
const combos = [
  ['^C', '\x03'], ['^D', '\x04'], ['^Z', '\x1a'], ['^L', '\x0c'], ['^U', '\x15'], ['^W', '\x17'],
  ['^R', '\x12'], ['^A', '\x01'], ['^E', '\x05'], ['^K', '\x0b'], ['^Y', '\x19'], ['^P', '\x10'], ['^N', '\x0e'],
]
const alts = [['ALT+B', '\x1bb'], ['ALT+F', '\x1bf'], ['ALT+D', '\x1bd'], ['ALT+.', '\x1b.']]
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
      <div class="tabscroller">
        <div v-for="s in sessions" :key="s.id" class="tab tap" :class="{ act: s.id === activeId, dead: !s.alive }"
          @click="activate(s.id)"
          @touchstart.passive="tabPress(s, $event)" @touchend="tabRelease" @touchmove="tabRelease"
          @mousedown="tabPress(s, $event)" @mouseup="tabRelease" @mouseleave="tabRelease">
          <span class="tdot" :class="{ on: s.alive }"></span>
          <span class="tlab">{{ renameId === s.id ? '' : s.title }}</span>
          <input v-if="renameId === s.id" v-model="renaming" class="tin" autofocus @click.stop
            @keydown.enter="renameSession(s.id, renaming); renameId = ''"
            @blur="renameSession(s.id, renaming); renameId = ''" />
        </div>
      </div>
      <button class="tb newb tap" @click="newTerm">＋</button>
    </header>

    <!-- 终端舞台：pane 由会话池借入 -->
    <div ref="stage" class="stage"></div>

    <!-- 虚拟按键 v6：常驻两行编程精选 + 可展开完整键盘 -->
    <div class="vkeys">
      <!-- 展开态：完整键盘（数字/字母/符号/修饰底条） -->
      <div v-if="keysMode === 'full'" class="kbpanel">
        <div class="kbr" v-for="(row, ri) in kbRows" :key="'kr' + ri">
          <button v-for="k in row" :key="k" class="kk tap" @click="press(k)">{{ shiftOn ? k.toUpperCase() : k }}</button>
          <button v-if="ri === 2" class="kk wide tap" @click="raw('\x7f')">⌫</button>
        </div>
        <div class="kbr sym-r">
          <button v-for="s2 in kbSyms" :key="s2" class="kk sym tap" @click="press(s2)">{{ s2 }}</button>
        </div>
        <div class="kbr bot">
          <button class="kk mod tap" :class="{ on: ctrlOn }" @click="press('CTRL')">CTRL</button>
          <button class="kk mod tap" :class="{ on: altOn }" @click="press('ALT')">ALT</button>
          <button class="kk mod tap" :class="{ on: shiftOn }" @click="press('SHIFT')">SHIFT</button>
          <button class="kk tap" @click="raw('\x1b')">ESC</button>
          <button class="kk tap" @click="raw('\t')">TAB</button>
          <button class="kk cc tap" @click="raw('\x03')">^C</button>
          <button class="kk spc tap" @click="raw(' ')">空格</button>
          <button class="kk enter tap" @click="raw('\r')">⏎</button>
          <button class="kk tog on tap" @click="keysMode = 'bar'">▴</button>
        </div>
      </div>
      <!-- 常态：两行编程精选（可横滚） -->
      <div v-else class="kwrap">
        <div class="kscroll">
          <button class="vk kb tap" @click="showKb">⌨</button>
          <button class="vk mod tap" :class="{ on: ctrlOn }" @click="press('CTRL')">CTRL</button>
          <button class="vk mod tap" :class="{ on: altOn }" @click="press('ALT')">ALT</button>
          <button class="vk mod tap" :class="{ on: shiftOn }" @click="press('SHIFT')">SHIFT</button>
          <button class="vk tap" @click="raw('\x1b')">ESC</button>
          <button class="vk tap" @click="raw('\t')">TAB</button>
          <button class="vk enter tap" @click="raw('\r')">⏎</button>
          <button class="vk cc tap" @click="raw('\x03')">^C</button>
          <button class="vk cc tap" @click="raw('\x0c')">^L</button>
          <button class="vk cc tap" @click="raw('\x04')">^D</button>
        </div>
        <div class="kscroll">
          <button class="vk cc tap" @click="raw('\x15')">^U</button>
          <button class="vk cc tap" @click="raw('\x17')">^W</button>
          <button class="vk cc tap" @click="raw('\x12')">^R</button>
          <button class="vk cc tap" @click="raw('\x01')">^A</button>
          <button class="vk cc tap" @click="raw('\x05')">^E</button>
          <button class="vk cc tap" @click="raw('\x0b')">^K</button>
          <button class="vk cc tap" @click="raw('\x19')">^Y</button>
          <button class="vk cc tap" @click="raw('\x1a')">^Z</button>
          <button class="vk tap" @click="raw('\x1b[H')">HOME</button>
          <button class="vk tap" @click="raw('\x1b[F')">END</button>
          <button class="vk tap" @click="raw('\x1b[5~')">PGUP</button>
          <button class="vk tap" @click="raw('\x1b[6~')">PGDN</button>
          <button class="vk tap" @click="raw('\x1b[2~')">INS</button>
          <button class="vk tap" @click="raw('\x7f')">DEL</button>
          <button class="vk alt tap" @click="raw('\x1bb')">A·B</button>
          <button class="vk alt tap" @click="raw('\x1bf')">A·F</button>
          <button class="vk alt tap" @click="raw('\x1bd')">A·D</button>
          <button class="vk tap" @click="raw(' ')">SPC</button>
          <button class="vk tog tap" @click="keysMode = 'full'">▾</button>
        </div>
      </div>
    </div>

        <!-- 悬浮方向键 D-pad（终端区右缘，可收起） -->
    <button v-if="dpadOff" class="dpad-fab tap" title="方向键" @touchstart.prevent="dpadOff = false; saveDpad(false)">✥</button>
    <div v-else class="dpad">
      <button class="dp tap" @touchstart.prevent="raw('\x1b[A')">↑</button>
      <div class="dpb">
        <button class="dp tap" @touchstart.prevent="raw('\x1b[D')">←</button>
        <button class="dp tap" @touchstart.prevent="raw('\x1b[B')">↓</button>
        <button class="dp tap" @touchstart.prevent="raw('\x1b[C')">→</button>
      </div>
      <button class="dp-x tap" @touchstart.prevent="dpadOff = true; saveDpad(true)">⌄</button>
    </div>

    <!-- 长按标签：行内管理菜单 -->
    <div v-if="tabMenu" class="tmask tap" @touchstart.prevent="tabMenu = null"></div>
    <div v-if="tabMenu" class="tmenu" :style="{ left: tabMenu.x + 'px' }">
      <div class="tmi tap" @touchstart.prevent="renameId = tabMenu.id; renaming = tabMenu.title; tabMenu = null">✎ 重命名</div>
      <div class="tmi tap" @touchstart.prevent="restartSession(tabMenu.id); tabMenu = null">↻ 重启</div>
      <div class="tmi danger tap" :class="{ confirm: confirmKill === tabMenu.id }"
        @touchstart.prevent="confirmKill === tabMenu.id ? (killSession(tabMenu.id), tabMenu = null, confirmKill = '') : (confirmKill = tabMenu.id, resetConfirm(tabMenu.id))">
        {{ confirmKill === tabMenu.id ? '⚠ 再点确认关闭' : '🗑 关闭会话' }}
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
/* 虚拟按键 v2：单行横滚条（Termius 模式） */
.vkeys { background: #14161c; border-top: 1px solid #23262e; padding: 5px 6px calc(5px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; gap: 5px; }
/* 常态：两行编程精选（各自可横滚） */
.kwrap { display: flex; flex-direction: column; gap: 5px; }
.kscroll { display: flex; gap: 5px; overflow-x: auto; scrollbar-width: none; flex: 1; min-width: 0; padding: 1px 0; align-items: center; }
.kscroll::-webkit-scrollbar { display: none; }
/* 展开态：完整键盘 */
.kbpanel { display: flex; flex-direction: column; gap: 5px; animation: kslide .16s ease; }
.kbr { display: flex; gap: 5px; }
.kbr.sym-r { overflow-x: auto; scrollbar-width: none; padding-bottom: 1px; }
.kbr.sym-r::-webkit-scrollbar { display: none; }
.kk { flex: 1; min-width: 0; height: 42px; background: #232735; color: #dfe4ec; border: 1px solid #2e3342; border-radius: 8px; font-size: 15px; font-family: ui-monospace, monospace; }
.kk:active { background: #2f3446; }
.kk.wide { flex: 1.6; font-size: 13px; color: #e08585; background: #2a1d1d; border-color: #4a2626; }
.kk.sym { flex: 0 0 auto; min-width: 34px; padding: 0 6px; font-size: 13.5px; }
.kk.mod { flex: 1.4; font-size: 11.5px; color: #a78bfa; background: #232635; border-color: #3d3560; }
.kk.mod.on { background: #8b5cf6; color: #fff; }
.kk.cc { color: #7dd3a8; background: #182227; border-color: #24402f; font-size: 12px; flex: 1.2; }
.kk.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; flex: 1.3; }
.kk.spc { flex: 5; font-size: 12.5px; color: #aeb6c4; }
.kk.tog { flex: 1.1; color: #a78bfa; font-size: 13px; }
.kk.tog.on { background: #2b2440; }
.vk.tog { color: #a78bfa; }
.vk.tog.on { background: #2b2440; }
/* 悬浮 D-pad（终端区右缘竖排锚定） */
.dpad { position: absolute; right: 10px; bottom: 14px; z-index: 15; display: flex; flex-direction: column; align-items: center; gap: 5px;
  background: rgba(20, 22, 28, .82); backdrop-filter: blur(8px); border: 1px solid #323848; border-radius: 14px; padding: 6px; }
.dpad .dp { width: 44px; height: 40px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 9px; font-size: 16px; }
.dpad .dp:active { background: #2b2440; }
.dpad .dpb { display: flex; gap: 5px; }
.dpad .dp-x { position: absolute; top: -8px; right: -8px; width: 22px; height: 22px; border-radius: 50%; background: #2c303b; color: #8a93a3; border: 1px solid #3a4150; font-size: 11px; display: flex; align-items: center; justify-content: center; }
.dpad-fab { position: absolute; right: 10px; bottom: 14px; z-index: 15; width: 42px; height: 42px; border-radius: 50%;
  background: rgba(139, 92, 246, .85); color: #fff; border: 0; font-size: 17px; box-shadow: 0 4px 14px rgba(0,0,0,.45); }
/* 扩展行：输入法式滑入 */
.kextra { display: flex; gap: 5px; overflow-x: auto; scrollbar-width: none; padding: 2px 0; animation: kslide .16s ease; }
.kextra::-webkit-scrollbar { display: none; }
@keyframes kslide { from { transform: translateY(14px); opacity: 0; } }
.vk { flex-shrink: 0; min-width: 42px; height: 40px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 9px; font-size: 12.5px; font-weight: 600; font-family: ui-monospace, monospace; }
.vk:active { background: #262b38; }
.vk.mod { background: #232635; color: #a78bfa; border-color: #3d3560; }
.vk.mod.on { background: #8b5cf6; color: #fff; }
.vk.cc { color: #7dd3a8; background: #182227; border-color: #24402f; }
.vk.alt { color: #e8b268; background: #241f18; border-color: #4a3c26; }
.vk.sym { min-width: 34px; font-size: 13.5px; }
.vk.kb { color: #7db3e8; background: #16222c; border-color: #243a4a; }
.vk.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; min-width: 46px; font-size: 15px; }
.vk.exp { color: #a78bfa; }
.vk.exp.on { background: #2b2440; }
.vk.dim { color: #666b76; }
/* 扩展面板 */
.sp { flex: 1; min-width: 8px; }
/* 长按标签行内菜单（Chrome 惯例） */
.tmask { position: fixed; inset: 0; z-index: 70; background: transparent; }
.tmenu { position: fixed; top: 46px; z-index: 71; min-width: 148px; background: #1c2027; border: 1px solid #323848; border-radius: 12px; overflow: hidden; box-shadow: 0 12px 36px rgba(0,0,0,.55); animation: mgrin .12s ease; }
.tmi { padding: 12px 15px; font-size: 13px; color: #dfe4ec; border-bottom: 1px solid #262b36; }
.tmi:active { background: #262b38; }
.tmi.danger { color: #e08585; }
.tmi.danger.confirm { background: #3a1d1d; color: #ffb4b4; font-weight: 700; }
.tin { background: #1a1d26; border: 1px solid #8b5cf6; color: #fff; border-radius: 5px; padding: 2px 6px; font-size: 12px; width: 72px; outline: none; }
@keyframes mgrin { from { transform: translateY(-8px); opacity: 0; } }
@keyframes pulse { 50% { opacity: .55; } }

/* ENTER 主操作色 */
.vk.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; font-weight: 700; }
/* full 面板分组标签 */
.ksec { font-size: 10px; color: #666b76; padding: 8px 12px 3px; letter-spacing: 1px; }
.kgrid.altg { grid-template-columns: repeat(4, 1fr); }
</style>
