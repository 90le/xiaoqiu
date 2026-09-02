<script setup>
import { ref, onMounted } from 'vue'
import QiuLogo from '../components/QiuLogo.vue'
const provider = ref('zai-coding-cn')
const key = ref('')
const model = ref('glm-5.3-flash')
const msg = ref('')
const msgOk = ref(false)
const perm = ref(null)
const savedKey = ref('')

async function save() {
  msg.value = '保存中…'
  try {
    const r = await fetch('/api/setkey', { method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ provider: provider.value, key: key.value, model: model.value }) })
    const d = (await r.json()).structuredContent
    msgOk.value = !!d.ok
    msg.value = d.ok ? d.data : (d.error ? d.error.message : '失败')
    if (d.ok) savedKey.value = '已配置'
  } catch(e) { msgOk.value = false; msg.value = '网络错误' }
}
async function loadPerm() {
  try {
    const r = await fetch('/api/perm_status', { method:'POST' })
    perm.value = (await r.json()).structuredContent.data
  } catch(e) {}
}
async function toggleBall() {
  await fetch('/api/floatball', { method:'POST' })
  await new Promise(r => setTimeout(r, 600))
  await loadPerm()
}
function openPerm(type) {
  fetch('/api/open_permission_settings', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ type }) })
}
const permRows = [
  { k:'accessibility', label:'无障碍', type:'a11y' },
  { k:'overlay', label:'悬浮窗', type:'overlay' },
  { k:'allFiles', label:'所有文件', type:'allfiles' },
]

/* ═══════════ 语音播报引擎 ═══════════ */
const ttsEngine = ref('auto')
const ttsMsg = ref('')
const ttsMsgOk = ref(true)
const testing = ref('')

const engines = [
  { id:'auto',   icon:'✨', name:'智能优先',      desc:'推荐 · 有额度用云端，没额度用小米本地', note:'永远不会哑，绝大多数用户选这个' },
  { id:'cloud',  icon:'☁️', name:'云端音色',      desc:'GLM-TTS · 音色最自然',   note:'需智谱TTS额度；只云不回退时选这个' },
  { id:'xiaomi', icon:'📱', name:'小米本地',      desc:'免费离线 · 已调优音调',   note:'换其他品牌手机自动改用该机系统音色' },
]
const voices = [
  { id:'tongtong', name:'彤彤（女·温暖，默认）' },
  { id:'chuichui', name:'锤锤' },
  { id:'xiaochen', name:'小陈' },
  { id:'jam',      name:'Jam（动物圈）' },
  { id:'kazi',     name:'卡兹（动物圈）' },
  { id:'douji',    name:'豆几（动物圈）' },
  { id:'luodo',    name:'罗多（动物圈）' },
]
const ttsVoice = ref('tongtong')
const sttEngine = ref('local')
const cloneId = ref('')
const wakeOn = ref(false)
const wakeStat = ref('')
async function pickVoice() {
  await fetch('/api/cfg_set', { method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({ key:'tts_voice', value: ttsVoice.value }) })
  ttsMsg.value = '音色已切换，试听即可听到'
  ttsMsgOk.value = true
  setTimeout(() => { ttsMsg.value = '' }, 2500)
}
async function saveClone() {
  if (!cloneId.value.trim()) return
  ttsVoice.value = cloneId.value.trim()
  await pickVoice()
}
async function toggleWake() {
  const next = !wakeOn.value
  wakeStat.value = next ? '启动中…' : '停止中…'
  const r = await fetch('/api/wake_service', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ action: next ? 'start' : 'stop' }) })
  const d = (await r.json()).structuredContent
  wakeOn.value = next && d.ok
  wakeStat.value = next ? (d.ok ? '✅ 待命中，说「小丘」试试（支持息屏）' : '启动失败，请重试') : '已停止'
  setTimeout(() => { wakeStat.value = '' }, 4000)
}
async function pickStt() {
  await fetch('/api/cfg_set', { method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({ key:'stt_engine', value: sttEngine.value }) })
  ttsMsg.value = '识别引擎已切换'
  ttsMsgOk.value = true
  setTimeout(() => { ttsMsg.value = '' }, 2500)
}

async function loadCfg() {
  try {
    const r = await fetch('/api/cfg_get', { method:'POST' })
    const d = (await r.json()).structuredContent
    if (d.ok && d.data.tts_engine) ttsEngine.value = d.data.tts_engine
    if (d.ok && d.data.tts_voice) {
      ttsVoice.value = d.data.tts_voice
      if (!['tongtong','chuichui','xiaochen','jam','kazi','douji','luodo'].includes(d.data.tts_voice)) cloneId.value = d.data.tts_voice
    }
    if (d.ok && d.data.stt_engine) sttEngine.value = d.data.stt_engine
    try {
      const r2 = await fetch('/api/wake_service', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ action:'status' }) })
      wakeOn.value = (await r2.json()).structuredContent.data.running
    } catch(e) {}
  } catch(e) {}
}
async function pickEngine(id) {
  ttsEngine.value = id
  ttsMsg.value = '已切换：' + (engines.find(e => e.id === id) || {}).name
  ttsMsgOk.value = true
  await fetch('/api/cfg_set', { method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({ key:'tts_engine', value: id }) })
  setTimeout(() => { ttsMsg.value = '' }, 2500)
}
async function testVoice(id) {
  testing.value = id
  try {
    const r = await fetch('/api/tts_speak', { method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ text:'你好，我是小丘，很高兴为你服务。', engine: id }) })
    const d = (await r.json()).structuredContent
    ttsMsg.value = d.ok ? ('🔊 ' + d.data) : ('✗ ' + (d.error ? d.error.message : '失败'))
    ttsMsgOk.value = !!d.ok
    setTimeout(() => { ttsMsg.value = '' }, 4000)
  } catch(e) { ttsMsg.value = '网络错误' }
  setTimeout(() => { testing.value = '' }, 3000)
}

onMounted(loadPerm)
onMounted(loadCfg)
</script>
<template>
  <div style="display:flex;align-items:center;gap:8px;margin:6px 4px 2px;">
    <QiuLogo :size="34" /><span class="h1" style="margin:0;">小丘设置</span>
  </div>
  <div class="sub">你说，我来办。</div>

  <!-- ═══════ 语音配置中心 ═══════ -->
  <div class="sec">🎙 语音识别</div>
  <div class="card">
    <div class="engine-row" style="display:flex;justify-content:space-between;align-items:center;gap:10px;">
      <div>
        <div class="engine-name">SenseVoice · 本地离线 <span class="pill ok-pill">已就绪</span></div>
        <div class="engine-desc">即时识别，无需网络 · 永久免费 · 隐私不出手机</div>
      </div>
      <select v-model="sttEngine" @change="pickStt" style="max-width:150px;font-size:13px;">
        <option value="local">本地识别（默认）</option>
        <option value="cloud">云端 GLM-ASR</option>
      </select>
    </div>
    <div style="font-size:11px;color:var(--muted);margin-top:6px;">云端识别更准但需网络与额度；识别失败会提示，可随时切回本地。</div>
  </div>

  <div class="sec">🔊 语音播报</div>
  <div class="card">
    <div class="engine-grid">
      <div v-for="e in engines" :key="e.id"
           class="engine-card" :class="{ active: ttsEngine === e.id }"
           @click="pickEngine(e.id)">
        <div class="engine-head">
          <span class="engine-icon">{{ e.icon }}</span>
          <span class="engine-name">{{ e.name }}</span>
          <span v-if="ttsEngine === e.id" class="pill ok-pill">使用中</span>
        </div>
        <div class="engine-desc">{{ e.desc }}</div>
        <div class="engine-note">{{ e.note }}</div>
        <div class="engine-foot">
          <button class="mini-btn" @click.stop="testVoice(e.id)">
            {{ testing === e.id ? '播放中…' : '🔊 试听' }}
          </button>
        </div>
      </div>
    </div>
    <div class="msg" :class="ttsMsgOk ? 'ok' : 'bad'" v-if="ttsMsg">{{ ttsMsg }}</div>
    <div style="margin-top:12px;border-top:1px dashed var(--line);padding-top:10px;">
      <label style="font-size:12px;font-weight:600;">🔊 云端音色（试听立即生效）</label>
      <div style="display:flex;gap:8px;margin-top:6px;">
        <select v-model="ttsVoice" @change="pickVoice" style="flex:1;font-size:13px;">
          <option v-for="v in voices" :key="v.id" :value="v.id">{{ v.name }}</option>
          <option v-if="cloneId" :value="cloneId">🎵 我的复刻音色</option>
        </select>
        <button class="mini-btn" style="padding:8px 14px;" @click="testVoice(ttsEngine === 'xiaomi' ? 'xiaomi' : 'cloud')">试听此音色</button>
      </div>
      <label style="font-size:11px;color:var(--muted);display:block;margin-top:8px;">🎵 复刻音色 ID（在智谱开放平台「语音复刻」上传录音后获得，粘贴即用）</label>
      <div style="display:flex;gap:8px;margin-top:4px;">
        <input v-model="cloneId" type="text" placeholder="粘贴复刻 voice_id（选填）" style="flex:1;font-size:12px;">
        <button class="mini-btn" style="padding:8px 14px;" @click="saveClone">保存</button>
      </div>
    </div>
    <div style="font-size:11px;color:var(--muted);margin-top:10px;line-height:1.6;">
      💡 「智能优先」= 云端音色 → 小米本地，按可用性自动降级，永远不会哑。
    </div>
  </div>

  <!-- ═══════ API 配置 ═══════ -->
  <div class="sec">🔑 大脑模型（pi + 快脑共用）</div>
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
    <div class="msg" :class="msgOk ? 'ok' : 'bad'">{{ msg }}</div>
  </div>

  <!-- ═══════ 权限中心 ═══════ -->
  <div class="sec">🛡 权限中心</div>
  <div class="card">
    <div v-if="perm">
      <div v-for="r in permRows" :key="r.k" class="kv">
        <span>{{ r.label }}</span>
        <span :class="{ ok: perm[r.k] }" style="cursor:pointer;" @click="r.type && openPerm(r.type)">
          {{ perm[r.k] ? '✅' : '❌ 去授权' }}
        </span>
      </div>
    </div>
    <div v-else class="muted">加载中…</div>
  </div>

  <div class="card" style="display:flex;justify-content:space-between;align-items:center;">
    <div><div style="font-weight:600;font-size:14px;">🏔 悬浮球</div>
    <div style="font-size:12px;color:var(--muted);">保活可见 · 单击回小丘 · 贴边吸附</div></div>
    <button class="btn" style="width:auto;padding:10px 16px;" @click="toggleBall(); loadPerm()">切换</button>
  </div>

  <div class="card">
    <div class="kv"><span>版本</span><span>1.0.0-dev</span></div>
    <div class="kv"><span>引擎</span><span>pi coding-agent</span></div>
    <div class="kv"><span>语音识别</span><span>SenseVoice 离线</span></div>
    <div class="kv"><span>工具</span><span>55 项</span></div>
  </div>
</template>
<style scoped>
.sec { margin:14px 4px 6px; font-size:12px; font-weight:700; color:var(--muted); letter-spacing:.5px; }
.msg { margin-top:12px; font-size:14px; text-align:center; min-height:18px; }
.msg.ok { color:var(--hill); } .msg.bad { color:var(--bad); }
.kv { display:flex; justify-content:space-between; font-size:13px; padding:6px 0; border-bottom:1px dashed var(--line); }
.kv:last-child { border:0; }
.ok { color:var(--hill); font-weight:600; }
.engine-grid { display:flex; flex-direction:column; gap:8px; }
.engine-card { border:1.5px solid var(--line); border-radius:12px; padding:10px 12px; cursor:pointer; transition:all .15s; background:#fff; }
.engine-card.active { border-color:var(--hill); background:linear-gradient(135deg,#f2f8f4,#fff); box-shadow:0 2px 8px rgba(62,124,89,.12); }
.engine-head { display:flex; align-items:center; gap:8px; }
.engine-icon { font-size:18px; }
.engine-name { font-weight:700; font-size:14px; flex:1; }
.engine-desc { font-size:12px; color:var(--ink); margin-top:4px; }
.engine-note { font-size:11px; color:var(--muted); margin-top:2px; }
.engine-foot { display:flex; align-items:center; justify-content:space-between; margin-top:8px; }
.mini-btn { border:1px solid var(--line); background:#fafaf7; border-radius:8px; padding:5px 12px; font-size:12px; cursor:pointer; }
.mini-btn:active { background:var(--hill); color:#fff; }
.pill { font-size:10px; padding:2px 8px; border-radius:99px; }
.ok-pill { background:#e6f2ea; color:var(--hill); font-weight:700; }
.dim-pill { background:#f0efe9; color:var(--muted); }
.dim-tag { font-size:10px; background:#f0efe9; color:var(--muted); padding:2px 8px; border-radius:99px; font-weight:400; }
.progress { height:6px; background:var(--line); border-radius:99px; overflow:hidden; margin-top:10px; }
.bar { height:100%; background:var(--hill); border-radius:99px; transition:width .5s; }
.btn.ghost { background:transparent; border:1px solid var(--line); color:var(--muted); }
</style>
