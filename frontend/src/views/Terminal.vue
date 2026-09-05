<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { chat, wsSend, connect } from '../useChat.js'
import { tstore, poolAttach, poolDetach, createSession, killSession, restartSession, renameSession } from '../termStore.js'
import '@xterm/xterm/css/xterm.css'

const stage = ref(null)
const activeId = ref('')
const showMgr = ref(false)
const keysMode = ref('slim')     // slim | full | hide
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
      <div class="k2">
        <div class="krow">
          <button class="vk mod tap" :class="{ on: ctrlOn }" @touchstart.prevent="press('CTRL')">CTRL</button>
          <button class="vk mod tap" :class="{ on: altOn }" @touchstart.prevent="press('ALT')">ALT</button>
          <button class="vk mod tap" :class="{ on: shiftOn }" @touchstart.prevent="press('SHIFT')">SHIFT</button>
          <button class="vk cc tap" @touchstart.prevent="raw('\x0c')">^L</button>
          <button class="vk tap" @touchstart.prevent="raw('\x7f')">DEL</button>
          <span class="kfill"></span>
          <div class="knav one"><button class="vk tap" @touchstart.prevent="raw('\x1b[A')">↑</button></div>
          <button v-if="keysMode === 'slim'" class="vk exp tap" @touchstart.prevent="keysMode = 'full'">▾</button>
          <button v-else class="vk exp on tap" @touchstart.prevent="keysMode = 'slim'">▴</button>
          <button class="vhide tap" @touchstart.prevent="keysMode = 'hide'">✕</button>
        </div>
        <div class="krow">
          <button class="vk tap" @touchstart.prevent="raw('\x1b')">ESC</button>
          <button class="vk tap" @touchstart.prevent="raw('\t')">TAB</button>
          <button class="vk enter tap" @touchstart.prevent="raw('\r')">ENTER</button>
          <button class="vk cc tap" @touchstart.prevent="raw('\x03')">^C</button>
          <button class="vk kb tap" @touchstart.prevent="showKb">⌨</button>
          <span class="kfill"></span>
          <div class="knav tri">
            <button class="vk tap" @touchstart.prevent="raw('\x1b[D')">←</button>
            <button class="vk tap" @touchstart.prevent="raw('\x1b[B')">↓</button>
            <button class="vk tap" @touchstart.prevent="raw('\x1b[C')">→</button>
          </div>
        </div>
      </div>
      <div v-if="keysMode === 'full'" class="kpanel">
        <!-- 导航组 -->
        <div class="ksec">导航</div>
        <div class="kgrid">
          <button class="vk tap" @touchstart.prevent="raw('\x1b[H')">HOME</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[F')">END</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[5~')">PGUP</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[6~')">PGDN</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[2~')">INS</button>
          <button class="vk tap" @touchstart.prevent="raw('\x7f')">DEL</button>
          <button class="vk enter tap" @touchstart.prevent="raw('\r')">ENTER</button>
          <button class="vk tap" @touchstart.prevent="raw(' ')">SPACE</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[D')">←</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[A')">↑</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[B')">↓</button>
          <button class="vk tap" @touchstart.prevent="raw('\x1b[C')">→</button>
        </div>
        <!-- Ctrl 组合组 -->
        <div class="ksec">Ctrl 组合</div>
        <div class="kgrid cc">
          <button v-for="[l, c] in combos" :key="l" class="vk tap" @touchstart.prevent="raw(c)">{{ l }}</button>
        </div>
        <!-- Alt 组合组 -->
        <div class="ksec">Alt 组合</div>
        <div class="kgrid altg">
          <button v-for="[l, c] in alts" :key="l" class="vk alt tap" @touchstart.prevent="raw(c)">{{ l }}</button>
        </div>
        <!-- 符号组 -->
        <div class="ksec">符号</div>
        <div class="kgrid sym">
          <button v-for="s2 in syms" :key="s2" class="vk tap" @touchstart.prevent="press(s2)">{{ s2 }}</button>
        </div>
      </div>
    </div>
    <!-- 隐藏态浮球 -->
    <button v-else class="kfab tap" @click="keysMode = 'slim'">⌨</button>

    <!-- 会话管理器（底部 sheet） -->
    <div v-if="showMgr" class="mask tap" @click="showMgr = false"></div>
    <div v-if="showMgr" class="mgr">
      <div class="mgrbar"></div>
      <div class="mh">
        <b>终端会话</b><span class="muted mcount">{{ sessions.length }} 个</span>
        <span class="sp"></span>
        <button class="mb new tap" @touchstart.prevent="newTerm(); showMgr = false">＋ 新建</button>
        <button class="mb tap" @touchstart.prevent="showMgr = false">✕ 关闭</button>
      </div>
      <div class="mlist">
        <div v-for="s in sessions" :key="s.id" class="mi tap" @click="activate(s.id); showMgr = false">
          <span class="tdot big" :class="{ on: s.alive }"></span>
          <div class="mib">
            <template v-if="renameId === s.id">
              <input v-model="renaming" class="rin" autofocus @click.stop
                @keydown.enter="renameSession(s.id, renaming); renameId = ''"
                @blur="renameSession(s.id, renaming); renameId = ''" />
            </template>
            <b v-else>{{ s.title }}</b>
            <span class="muted">{{ s.alive ? '运行中 · 活动 ' + fmtAgo(s.lastOut) : '已退出' + (s.exitCode != null ? '（码' + s.exitCode + '）' : '') }}{{ s.cwd ? ' · ' + s.cwd.replace('/data/data/com.pihost/files/home', '~') : '' }}</span>
          </div>
          <span class="sp"></span>
          <button class="mb tap" title="重命名" @click.stop="renameId = s.id; renaming = s.title">✎</button>
          <button class="mb tap" title="重启" @click.stop="restartSession(s.id)">↻</button>
          <button class="mb del tap" :class="{ confirm: confirmKill === s.id }" :title="confirmKill === s.id ? '再点确认' : '关闭'"
            @click.stop="confirmKill === s.id ? (killSession(s.id), confirmKill = '') : (confirmKill = s.id, resetConfirm(s.id))">
            {{ confirmKill === s.id ? '确认?' : '🗑' }}
          </button>
        </div>
        <div v-if="!sessions.length" class="mempty">没有终端会话</div>
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
.k2 { display: flex; flex-direction: column; gap: 4px; padding: 5px 7px; }
.krow { display: flex; gap: 4px; align-items: center; flex-wrap: wrap; } /* 不横滚，挤了换行 */
.kfill { flex: 1; min-width: 6px; }
/* 方向键尾部：两行同宽，↑ 恰好对齐在 ↓ 正上方 */
.knav { display: flex; gap: 4px; flex-shrink: 0; }
.knav.tri { width: 116px; }
.knav.one { width: 116px; justify-content: center; }
.knav .vk { min-width: 36px; width: 36px; }
.vhide { flex-shrink: 0; width: 20px; height: 30px; background: none; border: 0; color: #666b76; font-size: 11px; }
.vk { flex-shrink: 0; min-width: 36px; height: 34px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 8px; font-size: 12px; font-weight: 600; font-family: ui-monospace, monospace; }
.vk.mod { background: #232635; color: #a78bfa; border-color: #3d3560; }
.vk.mod.on { background: #8b5cf6; color: #fff; }
.vk.cc { color: #7dd3a8; background: #182227; border-color: #24402f; }
.vk.alt { color: #e8b268; background: #241f18; border-color: #4a3c26; }
.vk.sym { min-width: 30px; }
.vk.exp, .vk.hide { color: #a78bfa; }
.vk.kb { color: #7db3e8; background: #16222c; border-color: #243a4a; }
.kspace { flex: 1; min-width: 4px; }
.kfab { position: fixed; right: 14px; bottom: 18px; z-index: 20; width: 44px; height: 44px; border-radius: 50%;
  background: #8b5cf6; color: #fff; border: 0; font-size: 18px; box-shadow: 0 6px 20px rgba(0,0,0,.5); }
/* 展开键盘：网格自适应（不再横向滚动） */
.kpanel { border-top: 1px dashed #23262e; background: #101219;
  animation: kslide .18s ease; max-height: 42vh; overflow-y: auto; padding-bottom: 2px; }
@keyframes kslide { from { transform: translateY(30px); opacity: 0; } }
.kgrid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 4px; padding: 5px 7px 2px; }
.kgrid.cc { grid-template-columns: repeat(7, 1fr); }
.kgrid .vk { width: 100%; min-width: 0; }
.kgrid.cc .vk { color: #7dd3a8; background: #182227; border-color: #24402f; }
.kgrid.cc .vk.alt { color: #e8b268; background: #241f18; border-color: #4a3c26; }
.kgrid.sym { grid-template-columns: repeat(10, 1fr); }
.kgrid.sym .vk { font-size: 13px; }
/* 会话管理器：底部 sheet（手机惯例，thumb 友好） */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 60; }
.mgr { position: fixed; left: 0; right: 0; bottom: 0; max-height: 66vh;
  background: #14161c; border-radius: 18px 18px 0 0; z-index: 61; display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom); animation: mgrin .18s ease; }
@keyframes mgrin { from { transform: translateY(60px); opacity: 0; } }
.mgrbar { width: 36px; height: 4px; border-radius: 2px; background: #2c303b; margin: 10px auto 2px; flex-shrink: 0; }
.mh { display: flex; align-items: center; gap: 8px; padding: 10px 15px 8px; color: #dcddde; font-size: 14px; }
.sp { flex: 1; }
.mb { background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 8px; padding: 6px 12px; font-size: 12px; flex-shrink: 0; min-width: 36px; text-align: center; }
.mb.new { color: #a78bfa; border-color: #3d3560; }
.mb.del { color: #e08585; border-color: #4a2626; }
.mb.del.confirm { background: #5c2626; color: #ffb4b4; border-color: #8b3a3a; animation: pulse 1s infinite; }
.mcount { font-size: 12px; }
.mlist { overflow-y: auto; padding: 0 10px calc(12px); }
.mi { display: flex; align-items: center; gap: 6px; padding: 11px 4px; border-bottom: 1px solid #1e2128; }
.mi:active { background: rgba(139,92,246,.06); }
.mib { display: flex; flex-direction: column; gap: 3px; font-size: 13.5px; color: #dcddde; min-width: 0; flex: 1; }
.mib b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mib .muted { font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rin { background: #1a1d26; border: 1px solid #8b5cf6; color: #dcddde; border-radius: 6px; padding: 4px 8px; font-size: 13px; width: 60vw; max-width: 200px; }
.mempty { text-align: center; color: #666b76; font-size: 13px; padding: 30px 0; }
/* ENTER 主操作色 */
.vk.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; font-weight: 700; }
/* full 面板分组标签 */
.ksec { font-size: 10px; color: #666b76; padding: 8px 12px 3px; letter-spacing: 1px; }
.kgrid.altg { grid-template-columns: repeat(4, 1fr); }
</style>
