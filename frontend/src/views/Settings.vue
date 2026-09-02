<script setup>
import { ref, onMounted } from 'vue'
import QiuLogo from '../components/QiuLogo.vue'

/* ═══════════ 状态 ═══════════ */
// 大脑模型
const provider = ref('zai-coding-cn')
const key = ref('')
const model = ref('glm-5.3-flash')
const keyMsg = ref(''), keyOk = ref(false), savedKey = ref('')
// 语音识别
const sttEngine = ref('local')
// 语音播报
const ttsEngine = ref('auto')
const ttsVoice = ref('tongtong')
const cloneId = ref('')
const voiceRewrite = ref(true)
const voiceMsg = ref(''), voiceOk = ref(true)
const testing = ref('')
// 唤醒
const wakeOn = ref(false), wakeMsg = ref('')
// 权限 / 悬浮球
const perm = ref(null)

/* ═══════════ 通用 ═══════════ */
async function api(name, args) {
  try {
    const r = await fetch('/api/' + name, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args || {}) })
    return (await r.json()).structuredContent
  } catch (e) { return { ok: false, error: { message: '网络错误' } } }
}
async function setCfg(k, v) {
  await api('cfg_set', { key: k, value: v })
}
function flash(msg, ok = true) {
  voiceMsg.value = msg; voiceOk.value = ok
  setTimeout(() => { voiceMsg.value = '' }, 3000)
}

/* ═══════════ 大脑模型 ═══════════ */
async function save() {
  keyMsg.value = '保存中…'; keyOk.value = false
  const d = await api('setkey', { provider: provider.value, key: key.value, model: model.value })
  keyOk.value = !!d.ok
  keyMsg.value = d.ok ? d.data : (d.error ? d.error.message : '失败')
  if (d.ok) savedKey.value = '已配置'
}

/* ═══════════ 语音 ═══════════ */
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
async function pickVoice() {
  await setCfg('tts_voice', ttsVoice.value)
  flash('音色已切换，点「试听此音色」可预览')
}
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
  await api('tts_speak', { text: '你好，我是小丘，很高兴为你服务。', engine })
  setTimeout(() => { testing.value = '' }, 3000)
}

/* ═══════════ 唤醒 ═══════════ */
async function loadWake() {
  const d = await api('wake_service', { action: 'status' })
  wakeOn.value = !!(d.ok && d.data && d.data.running)
}
async function toggleWake() {
  const next = !wakeOn.value
  wakeMsg.value = next ? '启动中…' : '停止中…'
  const d = await api('wake_service', { action: next ? 'start' : 'stop' })
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

/* ═══════════ 权限 / 悬浮球 ═══════════ */
async function loadPerm() {
  const d = await api('perm_status')
  perm.value = d.data
}
async function toggleBall() {
  await api('floatball')
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

/* ═══════════ 启动加载 ═══════════ */
async function loadCfg() {
  const d = await api('cfg_get')
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
onMounted(() => { loadPerm(); loadCfg() })
</script>

<template>
  <div style="display:flex;align-items:center;gap:8px;margin:6px 4px 2px;">
    <QiuLogo :size="34" /><span class="h1" style="margin:0;">小丘设置</span>
  </div>
  <div class="sub">你说，我来办。</div>

  <!-- ═══════ 大脑 ═══════ -->
  <div class="sec">🧠 大脑模型</div>
  <div class="card">
    <label>API 供应商</label>
    <select v-model="provider">
      <option value="zai-coding-cn">智谱（zai-coding-cn）</option>
      <option value="zhipu">智谱开放平台</option>
      <option value="custom">自定义</option>
    </select>
    <label>API Key <span v-if="savedKey" class="ok" style="font-size:11px;">{{ savedKey }}</span></label>
    <input v-model="key" type="password" placeholder="粘贴你的 API Key">
    <label>默认模型</label>
    <input v-model="model" type="text">
    <button class="btn" @click="save">保存</button>
    <div class="msg" :class="keyOk ? 'ok' : 'bad'">{{ keyMsg }}</div>
  </div>

  <!-- ═══════ 语音识别 ═══════ -->
  <div class="sec">🎙 语音识别</div>
  <div class="card">
    <div class="row">
      <div class="row-txt">
        <div class="row-title">识别引擎</div>
        <div class="row-desc">本地即时免费 · 云端更准但需额度与网络</div>
      </div>
      <select v-model="sttEngine" @change="pickStt" class="slim">
        <option value="local">本地 SenseVoice</option>
        <option value="cloud">云端 GLM-ASR</option>
      </select>
    </div>
    <div class="pill-line"><span class="pill ok-pill">SenseVoice 已就绪</span><span class="pill dim-pill">离线 · 永久免费 · 隐私不出手机</span></div>
  </div>

  <!-- ═══════ 语音播报 ═══════ -->
  <div class="sec">🔊 语音播报</div>
  <div class="card">
    <div class="row">
      <div class="row-txt">
        <div class="row-title">播报引擎</div>
        <div class="row-desc">智能优先 = 云端可用则用云端，否则小米本地</div>
      </div>
      <select v-model="ttsEngine" @change="pickEngine" class="slim">
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
      <select v-model="ttsVoice" @change="pickVoice" class="slim">
        <option v-for="v in voices" :key="v.id" :value="v.id">{{ v.name }}</option>
        <option v-if="cloneId" :value="cloneId">🎵 我的复刻音色</option>
      </select>
    </div>

    <div class="row" style="margin-top:10px;">
      <div class="row-txt">
        <div class="row-title">复刻音色 ID</div>
        <div class="row-desc">智谱开放平台「语音复刻」上传录音后获得，粘贴即用</div>
      </div>
      <button class="mini-btn" @click="saveClone">保存</button>
    </div>
    <input v-model="cloneId" type="text" placeholder="粘贴复刻 voice_id（选填）" style="font-size:12px;">

    <div class="row" style="margin-top:10px;">
      <div class="row-txt">
        <div class="row-title">口语化改写</div>
        <div class="row-desc">长回复先改写成自然口语再朗读（推荐开）</div>
      </div>
      <div class="sw" :class="{ on: voiceRewrite }" @click="toggleRewrite"><div class="knob"></div></div>
    </div>

    <div class="btn-line">
      <button class="mini-btn" @click="testVoice(ttsEngine === 'xiaomi' ? 'xiaomi' : 'cloud')">
        {{ testing === 'cloud' ? '播放中…' : '🔊 试听当前配置' }}
      </button>
      <button class="mini-btn" @click="testVoice('xiaomi')">🔊 试听小米本地</button>
    </div>
    <div class="msg" :class="voiceOk ? 'ok' : 'bad'" v-if="voiceMsg">{{ voiceMsg }}</div>
  </div>

  <!-- ═══════ 全局唤醒 ═══════ -->
  <div class="sec">📢 全局唤醒词</div>
  <div class="card">
    <div class="row">
      <div class="row-txt">
        <div class="row-title">「小丘」随时唤醒
          <span class="pill" :class="wakeOn ? 'ok-pill' : 'dim-pill'">{{ wakeOn ? '待命中' : '已关闭' }}</span>
        </div>
        <div class="row-desc">任意界面/息屏喊「小丘」→ 回应后直接下指令<br>支持：小丘 · 小丘小丘 · 你好小丘 · 嘿小丘 · 嗨小丘</div>
      </div>
      <div class="sw" :class="{ on: wakeOn }" @click="toggleWake"><div class="knob"></div></div>
    </div>
    <div class="msg ok" v-if="wakeMsg" style="text-align:left;">{{ wakeMsg }}</div>
    <div style="font-size:11px;color:var(--muted);margin-top:8px;line-height:1.6;">
      ⚠ 唤醒监听会持续使用麦克风与少量电量，不用时可关闭。<br>
      ⚠ 需要在系统设置中允许小丘「自启动」与「后台运行」（MIUI: 省电策略→无限制）。
    </div>
  </div>

  <!-- ═══════ 权限 ═══════ -->
  <div class="sec">🛡 权限中心</div>
  <div class="card">
    <div v-if="perm">
      <div v-for="r in permRows" :key="r.k" class="kv">
        <span>{{ r.label }}</span>
        <span :class="{ ok: perm[r.k] }" style="cursor:pointer;" @click="r.type && openPerm(r.type)">
          {{ perm[r.k] ? '✅ 已授权' : '❌ 去授权' }}
        </span>
      </div>
    </div>
    <div v-else class="muted">加载中…</div>
  </div>

  <!-- ═══════ 悬浮球 ═══════ -->
  <div class="sec">🏔 悬浮球</div>
  <div class="card" style="display:flex;justify-content:space-between;align-items:center;">
    <div>
      <div style="font-weight:600;font-size:14px;">悬浮球</div>
      <div style="font-size:12px;color:var(--muted);margin-top:2px;">
        单击开小丘 · <b>双击开语音对话</b> · 拖动贴边<br>对话时球变色＋旁有文字提示
      </div>
    </div>
    <button class="btn" style="width:auto;padding:10px 16px;" @click="toggleBall(); loadPerm()">切换</button>
  </div>

  <!-- ═══════ 关于 ═══════ -->
  <div class="sec">ℹ️ 关于</div>
  <div class="card">
    <div class="kv"><span>版本</span><span>1.0.0-dev</span></div>
    <div class="kv"><span>执行引擎</span><span>pi coding-agent</span></div>
    <div class="kv"><span>快脑</span><span>GLM-5.3-flash</span></div>
    <div class="kv"><span>语音识别</span><span>SenseVoice / GLM-ASR</span></div>
    <div class="kv"><span>语音合成</span><span>GLM-TTS / 小米</span></div>
    <div class="kv"><span>内置工具</span><span>55 项</span></div>
  </div>

  <div style="height:20px;"></div>
</template>

<style scoped>
.sec { margin:16px 4px 6px; font-size:12px; font-weight:700; color:var(--muted); letter-spacing:.5px; }
.msg { margin-top:12px; font-size:14px; text-align:center; min-height:18px; }
.msg.ok { color:var(--hill); } .msg.bad { color:var(--bad); }
.kv { display:flex; justify-content:space-between; font-size:13px; padding:6px 0; border-bottom:1px dashed var(--line); }
.kv:last-child { border:0; }
.ok { color:var(--hill); font-weight:600; }
.row { display:flex; justify-content:space-between; align-items:center; gap:10px; }
.row-txt { flex:1; }
.row-title { font-weight:600; font-size:14px; }
.row-desc { font-size:12px; color:var(--muted); margin-top:2px; line-height:1.5; }
.slim { max-width:170px; font-size:13px; }
.btn-line { display:flex; gap:8px; margin-top:12px; }
.mini-btn { border:1px solid var(--line); background:#fafaf7; border-radius:8px; padding:6px 12px; font-size:12px; cursor:pointer; }
.mini-btn:active { background:var(--hill); color:#fff; }
.pill { font-size:10px; padding:2px 8px; border-radius:99px; }
.ok-pill { background:#e6f2ea; color:var(--hill); font-weight:700; }
.dim-pill { background:#f0efe9; color:var(--muted); }
.pill-line { display:flex; gap:6px; margin-top:10px; flex-wrap:wrap; }
.sw { width:46px; height:26px; border-radius:99px; background:#d8d6cd; position:relative; transition:.2s; cursor:pointer; flex-shrink:0; }
.sw.on { background:var(--hill); }
.sw .knob { position:absolute; top:3px; left:3px; width:20px; height:20px; border-radius:50%; background:#fff; transition:.2s; box-shadow:0 1px 3px rgba(0,0,0,.2); }
.sw.on .knob { left:23px; }
</style>
