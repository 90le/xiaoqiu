<script setup>
import { ref, computed, onMounted } from 'vue'
import QiuLogo from '../components/QiuLogo.vue'
import { chat, api as engineApi, connect } from '../useChat.js'

/* ═══════════ 子页导航（P1 壳） ═══════════ */
const page = ref(null)
const pages = {
  models: '🤖 模型大脑', prompt: '📝 提示词', skills: '🧩 技能与扩展',
  termtools: '⌨ 终端工具', vision: '👁 视觉桥', voice: '🎙 语音与唤醒',
  phone: '📱 手机与权限', about: 'ℹ️ 关于',
}
function back() { page.value = null }

/* ═══════════ 引擎设置摘要（首页分组右侧值） ═══════════ */
const st = computed(() => chat.settings || {})
const curModel = computed(() => {
  const m = chat.state?.model
  return m ? (m.label || m.id) : '—'
})
const THINK = { off: '', minimal: ' · 思考极简', low: ' · 思考低', medium: ' · 思考中', high: ' · 思考高', xhigh: ' · 思考极高', max: ' · 思考最大' }
const groups = computed(() => {
  const s = chat.settings
  return [
    { id: 'models', icon: '🤖', title: '模型大脑', sum: curModel.value + (THINK[chat.state?.thinkingLevel] || '') },
    { id: 'prompt', icon: '📝', title: '提示词', sum: s ? (s.promptMode === 'replace' ? '替换模式' : '追加模式') : '系统提示词 · 项目指令' },
    { id: 'skills', icon: '🧩', title: '技能与扩展', sum: s ? `${(s.skills || []).length} 技能 · ${(s.extensions || []).length} 扩展` : '技能/扩展/插件管理' },
    { id: 'termtools', icon: '⌨', title: '终端工具', sum: s ? `工具${s.terminalToolsEnabled !== false ? '开' : '关'} · bash${s.terminalBash ? '开' : '关'}` : '工具开关 · bash 接管' },
    { id: 'vision', icon: '👁', title: '视觉桥', sum: s ? (s.visionBridgeEnabled === false ? '关闭' : (s.visionBridgeModel || '默认')) : '图片理解通道' },
    { id: 'voice', icon: '🎙', title: '语音与唤醒', sum: '识别 · 播报 · 音色 · 唤醒词' },
    { id: 'phone', icon: '📱', title: '手机与权限', sum: '无障碍 · 悬浮窗 · 文件权限' },
    { id: 'about', icon: 'ℹ️', title: '关于', sum: '小丘 1.0.0 · pi 引擎' },
  ]
})

/* ═══════════ App 侧能力（/api/*，原页迁移） ═══════════ */
async function appapi(name, args) {
  try {
    const r = await fetch('/api/' + name, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args || {}) })
    return (await r.json()).structuredContent
  } catch (e) { return { ok: false, error: { message: '网络错误' } } }
}
async function setCfg(k, v) { await appapi('cfg_set', { key: k, value: v }) }
function flash(msg, ok = true) {
  voiceMsg.value = msg; voiceOk.value = ok
  setTimeout(() => { voiceMsg.value = '' }, 3000)
}

/* ── 语音 ── */
const sttEngine = ref('local')
const ttsEngine = ref('auto')
const ttsVoice = ref('tongtong')
const cloneId = ref('')
const voiceRewrite = ref(true)
const voiceMsg = ref(''), voiceOk = ref(true)
const testing = ref('')
const voices = [
  { id: 'tongtong', name: '彤彤（女·温暖，默认）' },
  { id: 'chuichui', name: '锤锤' },
  { id: 'xiaochen', name: '小陈' },
  { id: 'jam', name: 'Jam（动物圈）' },
  { id: 'kazi', name: '卡兹（动物圈）' },
  { id: 'douji', name: '豆几（动物圈）' },
  { id: 'luodo', name: '罗多（动物圈）' },
]
async function pickStt() { await setCfg('stt_engine', sttEngine.value); flash('识别引擎：' + (sttEngine.value === 'cloud' ? '云端 GLM-ASR' : '本地 SenseVoice')) }
async function pickEngine() { await setCfg('tts_engine', ttsEngine.value); flash('播报引擎已切换') }
async function pickVoice() { await setCfg('tts_voice', ttsVoice.value); flash('音色已切换，点「试听」可预览') }
async function saveClone() {
  if (!cloneId.value.trim()) return
  ttsVoice.value = cloneId.value.trim()
  await setCfg('tts_voice', ttsVoice.value)
  flash('复刻音色已保存')
}
async function toggleRewrite() {
  voiceRewrite.value = !voiceRewrite.value
  await setCfg('voice_rewrite', voiceRewrite.value ? 'true' : 'false')
  flash(voiceRewrite.value ? '口语化改写：开' : '口语化改写：关（原样朗读）')
}
async function testVoice(engine) {
  testing.value = engine
  await appapi('tts_speak', { text: '你好，我是小丘，很高兴为你服务。', engine })
  setTimeout(() => { testing.value = '' }, 3000)
}

/* ── 唤醒 ── */
const wakeOn = ref(false), wakeMsg = ref('')
async function loadWake() {
  const d = await appapi('wake_service', { action: 'status' })
  wakeOn.value = !!(d.ok && d.data && d.data.running)
}
async function toggleWake() {
  const next = !wakeOn.value
  wakeMsg.value = next ? '启动中…' : '停止中…'
  await appapi('wake_service', { action: next ? 'start' : 'stop' })
  await new Promise(r => setTimeout(r, 800))
  await loadWake()
  if (next) {
    await setCfg('wake_on', wakeOn.value ? 'true' : 'false')
    wakeMsg.value = wakeOn.value ? '✅ 待命中，说「小丘」唤醒我' : '启动失败，请重试'
  } else {
    await setCfg('wake_on', 'false')
    wakeMsg.value = '已关闭'
  }
  setTimeout(() => { wakeMsg.value = '' }, 4000)
}

/* ── 权限 / 悬浮球 ── */
const perm = ref(null)
async function loadPerm() { const d = await appapi('perm_status'); perm.value = d.data }
async function toggleBall() {
  await appapi('floatball')
  await new Promise(r => setTimeout(r, 600))
  await loadPerm()
}
function openPerm(type) {
  fetch('/api/open_permission_settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ type }) })
}
const permRows = [
  { k: 'accessibility', label: '无障碍（屏幕读取）', type: 'a11y' },
  { k: 'overlay', label: '悬浮窗（悬浮球）', type: 'overlay' },
  { k: 'allFiles', label: '所有文件（环境引擎）', type: 'allfiles' },
]

/* ── 旧版密钥（P3 模型管理上线后下线） ── */
const legacyOpen = ref(false)
const provider = ref('zai-coding-cn')
const key = ref('')
const model = ref('glm-5.3-flash')
const keyMsg = ref(''), keyOk = ref(false)
async function saveKey() {
  keyMsg.value = '保存中…'; keyOk.value = false
  const d = await appapi('setkey', { provider: provider.value, key: key.value, model: model.value })
  keyOk.value = !!d.ok
  keyMsg.value = d.ok ? d.data : (d.error ? d.error.message : '失败')
}

/* ═══════════ 启动 ═══════════ */
onMounted(() => {
  // ws 未连则连（设置页可能先于对话页打开）
  if (chat.status !== 'open' && chat.status !== 'connecting') connect()
  if (chat.status === 'open') engineApi.getSettings()
  loadPerm(); loadCfg()
})
async function loadCfg() {
  const d = await appapi('cfg_get')
  if (d.ok && d.data) {
    const c = d.data
    if (c.tts_engine) ttsEngine.value = c.tts_engine
    if (c.stt_engine) sttEngine.value = c.stt_engine
    if (c.tts_voice) {
      ttsVoice.value = c.tts_voice
      if (!voices.some(v => v.id === c.tts_voice)) cloneId.value = c.tts_voice
    }
    voiceRewrite.value = c.voice_rewrite !== 'false'
  }
  await loadWake()
}
</script>

<template>
  <!-- ═══════════ 首页：分组列表 ═══════════ -->
  <div v-show="!page">
    <div style="display:flex;align-items:center;gap:8px;margin:6px 4px 2px;">
      <QiuLogo :size="34" /><span class="h1" style="margin:0;">设置</span>
    </div>
    <div class="sub">你说，我来办。</div>

    <div class="grp-card">
      <div v-for="g in groups" :key="g.id" class="grp tap" @click="page = g.id">
        <span class="grp-ic">{{ g.icon }}</span>
        <span class="grp-txt">
          <span class="grp-t">{{ g.title }}</span>
          <span class="grp-s">{{ g.sum }}</span>
        </span>
        <span class="grp-ar">›</span>
      </div>
    </div>

    <!-- 旧版密钥（P3 下线） -->
    <div class="card" style="padding:0;overflow:hidden;">
      <div class="lgcy-h tap" @click="legacyOpen = !legacyOpen">
        <span>🔑 旧版密钥 <em>（即将由「模型大脑」取代）</em></span>
        <span>{{ legacyOpen ? '▴' : '▾' }}</span>
      </div>
      <div v-if="legacyOpen" class="lgcy-b">
        <label>API 供应商</label>
        <select v-model="provider">
          <option value="zai-coding-cn">智谱（zai-coding-cn）</option>
          <option value="zhipu">智谱开放平台</option>
          <option value="custom">自定义</option>
        </select>
        <label>API Key</label>
        <input v-model="key" type="password" placeholder="粘贴你的 API Key">
        <label>默认模型</label>
        <input v-model="model" type="text">
        <button class="btn" style="margin-top:10px;" @click="saveKey">保存</button>
        <div v-if="keyMsg" :class="['msg', keyOk ? 'ok' : 'bad']">{{ keyMsg }}</div>
      </div>
    </div>
    <div style="height:20px;"></div>
  </div>

  <!-- ═══════════ 子页覆盖层（滑入，‹ 返回） ═══════════ -->
  <div v-if="page" class="subp">
    <div class="subbar">
      <button class="backb tap" @click="back">‹</button>
      <span class="subbar-t">{{ pages[page] }}</span>
    </div>

    <!-- ── 🎙 语音与唤醒（迁移完成） ── -->
    <template v-if="page === 'voice'">
      <div class="sec">语音识别</div>
      <div class="card">
        <div class="row">
          <div class="row-txt">
            <div class="row-title">识别引擎</div>
            <div class="row-desc">本地即时免费 · 云端更准但需额度与网络</div>
          </div>
          <select v-model="sttEngine" class="slim" @change="pickStt">
            <option value="local">本地 SenseVoice</option>
            <option value="cloud">云端 GLM-ASR</option>
          </select>
        </div>
        <div class="pill-line">
          <span class="pill ok-pill">SenseVoice 已就绪</span>
          <span class="pill dim-pill">离线 · 永久免费 · 隐私不出手机</span>
        </div>
      </div>

      <div class="sec">语音播报</div>
      <div class="card">
        <div class="row">
          <div class="row-txt">
            <div class="row-title">播报引擎</div>
            <div class="row-desc">智能优先 = 云端可用则用云端，否则小米本地</div>
          </div>
          <select v-model="ttsEngine" class="slim" @change="pickEngine">
            <option value="auto">智能优先（推荐）</option>
            <option value="cloud">仅云端</option>
            <option value="xiaomi">小米本地</option>
          </select>
        </div>
        <div class="row" style="margin-top:10px;">
          <div class="row-txt">
            <div class="row-title">云端音色</div>
            <div class="row-desc">GLM-TTS · 需智谱额度</div>
          </div>
          <select v-model="ttsVoice" class="slim" @change="pickVoice">
            <option v-for="v in voices" :key="v.id" :value="v.id">{{ v.name }}</option>
            <option v-if="cloneId" :value="cloneId">🎵 我的复刻音色</option>
          </select>
        </div>
        <div class="row" style="margin-top:10px;">
          <div class="row-txt">
            <div class="row-title">复刻音色 ID</div>
            <div class="row-desc">智谱开放平台「语音复刻」上传录音后获得</div>
          </div>
          <button class="mini-btn" @click="saveClone">保存</button>
        </div>
        <input v-model="cloneId" type="text" placeholder="粘贴复刻 voice_id（选填）" style="font-size:12px;">
        <div class="row" style="margin-top:10px;">
          <div class="row-txt">
            <div class="row-title">口语化改写</div>
            <div class="row-desc">长回复先改写成自然口语再朗读（推荐开）</div>
          </div>
          <div :class="['sw', { on: voiceRewrite }]" @click="toggleRewrite"><div class="knob"></div></div>
        </div>
        <div class="btn-line">
          <button class="mini-btn" @click="testVoice(ttsEngine === 'xiaomi' ? 'xiaomi' : 'cloud')">{{ testing === 'cloud' ? '播放中…' : '🔊 试听当前配置' }}</button>
          <button class="mini-btn" @click="testVoice('xiaomi')">🔊 试听小米本地</button>
        </div>
        <div v-if="voiceMsg" :class="['msg', voiceOk ? 'ok' : 'bad']">{{ voiceMsg }}</div>
      </div>

      <div class="sec">全局唤醒词</div>
      <div class="card">
        <div class="row">
          <div class="row-txt">
            <div class="row-title">「小丘」随时唤醒
              <span :class="['pill', wakeOn ? 'ok-pill' : 'dim-pill']">{{ wakeOn ? '待命中' : '已关闭' }}</span>
            </div>
            <div class="row-desc">任意界面/息屏喊「小丘」→ 回应后直接下指令<br>支持：小丘 · 小丘小丘 · 你好小丘 · 嘿小丘 · 嗨小丘</div>
          </div>
          <div :class="['sw', { on: wakeOn }]" @click="toggleWake"><div class="knob"></div></div>
        </div>
        <div v-if="wakeMsg" class="msg ok" style="text-align:left;">{{ wakeMsg }}</div>
        <div style="font-size:11px;color:var(--muted);margin-top:8px;line-height:1.6;">
          ⚠ 唤醒监听会持续使用麦克风与少量电量，不用时可关闭。<br>
          ⚠ 需要在系统设置中允许小丘「自启动」与「后台运行」。
        </div>
      </div>
    </template>

    <!-- ── 📱 手机与权限（迁移完成） ── -->
    <template v-else-if="page === 'phone'">
      <div class="sec">权限中心</div>
      <div class="card">
        <template v-if="perm">
          <div v-for="r in permRows" :key="r.k" class="kv">
            <span>{{ r.label }}</span>
            <span :class="{ ok: perm[r.k] }" style="cursor:pointer;" @click="r.type && openPerm(r.type)">{{ perm[r.k] ? '✅ 已授权' : '❌ 去授权' }}</span>
          </div>
        </template>
        <div v-else class="muted">加载中…</div>
      </div>

      <div class="sec">悬浮球</div>
      <div class="card" style="display:flex;justify-content:space-between;align-items:center;">
        <div>
          <div style="font-weight:600;font-size:14px;">悬浮球</div>
          <div style="font-size:12px;color:var(--muted);margin-top:2px;">单击开小丘 · <b>双击开语音对话</b> · 拖动贴边<br>对话时球变色＋旁有文字提示</div>
        </div>
        <button class="btn" style="width:auto;padding:10px 16px;" @click="toggleBall(); loadPerm()">切换</button>
      </div>
    </template>

    <!-- ── ℹ️ 关于 ── -->
    <template v-else-if="page === 'about'">
      <div class="card">
        <div class="kv"><span>版本</span><span>1.0.0-dev</span></div>
        <div class="kv"><span>执行引擎</span><span>pi coding-agent</span></div>
        <div class="kv"><span>快脑</span><span>GLM-5.3-flash</span></div>
        <div class="kv"><span>语音识别</span><span>SenseVoice / GLM-ASR</span></div>
        <div class="kv"><span>语音合成</span><span>GLM-TTS / 小米</span></div>
        <div class="kv"><span>内置工具</span><span>55 项</span></div>
      </div>
      <div class="muted" style="text-align:center;font-size:12px;margin-top:8px;">小丘 · 山间工作台 · GPL v3</div>
    </template>

    <!-- ── 建设中占位（P2-P4 依次点亮） ── -->
    <template v-else>
      <div class="card" style="text-align:center;padding:34px 16px;">
        <div style="font-size:36px;">🚧</div>
        <div style="font-weight:700;margin-top:10px;">{{ pages[page] }}</div>
        <div class="muted" style="font-size:13px;margin-top:8px;line-height:1.7;">
          {{ page === 'models' && '默认模型与思考档 · 供应商密钥 · 自定义模型 · 预设（P3）' }}
          {{ page === 'prompt' && '系统提示词追加/替换 · 项目指令 AGENTS.md（P2）' }}
          {{ page === 'skills' && '技能开关 · 扩展管理 · UI 插件（P2）' }}
          {{ page === 'termtools' && '终端工具开关 · bash 接管 · 空闲超时（P4）' }}
          {{ page === 'vision' && '图片理解通道 · 视觉模型选择（P4）' }}
        </div>
      </div>
    </template>
    <div style="height:24px;"></div>
  </div>
</template>

<style scoped>
/* 分组卡片：iOS 设置风格 */
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); margin-bottom: 14px; }
.grp { display: flex; align-items: center; gap: 12px; padding: 13px 14px; border-bottom: 1px solid var(--line); }
.grp:last-child { border-bottom: 0; }
.grp:active { background: var(--bg); }
.grp-ic { font-size: 20px; width: 26px; text-align: center; }
.grp-txt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.grp-t { font-size: 15px; font-weight: 600; }
.grp-s { font-size: 12px; color: var(--muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.grp-ar { color: var(--muted); font-size: 18px; }

/* 旧版密钥折叠 */
.lgcy-h { display: flex; justify-content: space-between; align-items: center; padding: 13px 14px; font-size: 14px; font-weight: 600; }
.lgcy-h em { font-style: normal; font-size: 11px; color: var(--muted); font-weight: 400; }
.lgcy-b { padding: 0 14px 14px; }

/* 子页覆盖层：右滑入全屏 */
.subp { position: fixed; inset: 0; z-index: 60; background: var(--bg); overflow-y: auto; padding: 0 12px; animation: slidein .22s ease; }
@keyframes slidein { from { transform: translateX(100%); } to { transform: none; } }
.subbar { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 6px; padding: 10px 2px; background: var(--bg); border-bottom: 1px solid var(--line); margin-bottom: 10px; }
.backb { border: 0; background: none; font-size: 26px; color: var(--ink); padding: 2px 10px 2px 2px; line-height: 1; }
.backb:active { opacity: .5; }
.subbar-t { font-size: 17px; font-weight: 700; }
</style>
