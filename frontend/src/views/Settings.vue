<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import QiuLogo from '../components/QiuLogo.vue'
import { chat, api as engineApi, connect, wsSend } from '../useChat.js'

/* ═══════════ 子页导航 ═══════════ */
const page = ref(null)
const pages = {
  models: '模型大脑', prompt: '提示词', skills: '技能与扩展',
  termtools: '终端工具', vision: '视觉桥', voice: '语音与唤醒',
  phone: '手机与权限', about: '关于',
}
// 安卓返回手势：开子页压一条历史，popstate 关子页
let subPushed = false
function openPage(p) {
  page.value = p
  try { history.pushState({ sub: p }, ''); subPushed = true } catch { subPushed = false }
}
function onPop() {
  if (page.value) { page.value = null; subPushed = false }
  else if (subPushed) subPushed = false
}
function back() {
  if (subPushed) { try { history.back(); return } catch {} }
  page.value = null; subPushed = false
}

/* ═══════════ 首页分组 ═══════════ */
const THINK = { off: '', minimal: ' · 思考极简', low: ' · 思考低', medium: ' · 思考中', high: ' · 思考高', xhigh: ' · 思考极高', max: ' · 思考最大' }
const engineGroups = computed(() => {
  const s = chat.settings
  const m = chat.state?.model
  return [
    { id: 'models', icon: '🤖', t: '模型大脑', sum: m ? (m.label || m.id) : '—' },
    { id: 'prompt', icon: '📝', t: '提示词', sum: s ? (s.promptMode === 'replace' ? '替换模式' : '追加模式') : '系统提示词 · 全局指令' },
    { id: 'skills', icon: '🧩', t: '技能与扩展', sum: s ? `${(s.skills || []).length} 技能 · ${(s.extensions || []).length} 扩展` : '开关 · 说明 · 重载' },
    { id: 'termtools', icon: '⌨', t: '终端工具', sum: s ? `工具${s.terminalToolsEnabled !== false ? '开' : '关'} · bash${s.terminalBash ? '开' : '关'}` : '工具开关 · bash 接管' },
    { id: 'vision', icon: '👁', t: '视觉桥', sum: s ? (s.visionBridgeEnabled === false ? '关闭' : (s.visionBridgeModel || '默认')) : '图片理解通道' },
  ]
})
const qiuGroups = [
  { id: 'voice', icon: '🎙', t: '语音与唤醒', sum: '识别 · 播报 · 音色 · 唤醒词' },
  { id: 'phone', icon: '📱', t: '手机与权限', sum: '无障碍 · 悬浮窗 · 文件权限' },
  { id: 'about', icon: '🏔', t: '关于', sum: '小丘 1.0.0 · pi 引擎' },
]

/* ═══════════ App 侧能力（/api/*） ═══════════ */
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
const sttLabel = computed(() => sttEngine.value === 'local' ? '本地 SenseVoice' : '云端 GLM-ASR')
const ttsLabel = computed(() => ({ auto: '智能优先', cloud: '仅云端', xiaomi: '小米本地' })[ttsEngine.value] || ttsEngine.value)
const voiceLabel = computed(() => {
  const v = voices.find(x => x.id === ttsVoice.value)
  if (v) return v.name.split('（')[0]
  return cloneId.value === ttsVoice.value ? '🎵 复刻音色' : ttsVoice.value
})
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
  { k: 'accessibility', label: '无障碍（屏幕读取）', type: 'a11y', hint: '读屏幕 · 代替你点按' },
  { k: 'overlay', label: '悬浮窗（悬浮球）', type: 'overlay', hint: '任意界面快速唤出' },
  { k: 'allFiles', label: '所有文件（环境引擎）', type: 'allfiles', hint: '引擎读写存储' },
]

/* ═══════════ 提示词子页 ═══════════ */
const pmSeg = ref('sys')                       // sys=系统提示词 agents=全局指令
const promptMode = ref('append')
const promptDraft = ref('')
const effOpen = ref(false)
let promptSynced = false
function syncPromptDraft() {
  const s = chat.settings
  if (!s || promptSynced) return
  promptSynced = true
  promptMode.value = s.promptMode || 'append'
  promptDraft.value = s.customSystemPrompt || ''
}
function resetPrompt() { promptSynced = false; syncPromptDraft() }
const promptDirty = computed(() => {
  const s = chat.settings
  if (!s) return false
  return promptMode.value !== (s.promptMode || 'append') || promptDraft.value !== (s.customSystemPrompt || '')
})
function fillDefault() { if (chat.settings?.defaultSystemPrompt) promptDraft.value = chat.settings.defaultSystemPrompt }
function savePrompt() {
  engineApi.setSettings({ promptMode: promptMode.value, customSystemPrompt: promptDraft.value })
  promptSynced = false
  setTimeout(syncPromptDraft, 600)
}
function copyEff() {
  try { navigator.clipboard.writeText(chat.settings?.effectiveSystemPrompt || ''); promptCopied.value = true; setTimeout(() => promptCopied.value = false, 1500) } catch {}
}
const promptCopied = ref(false)

/* 全局指令 AGENTS.md */
const AG_PATH = '.pi/agent/AGENTS.md'
const agDraft = ref('')
const agState = ref(0) // 0未载 1载入中 2已载 3读取失败
async function loadAgents() {
  agState.value = 1
  try {
    const m = await engineApi.readFile(AG_PATH)
    agDraft.value = m && !m.binary ? m.text : ''
    agLoadedText.value = agDraft.value
    agState.value = 2
  } catch { agState.value = 3 }
}
const agDirty = computed(() => agState.value === 2 && agDraft.value !== agLoadedText.value)
const agLoadedText = ref('')
function saveAgents() { engineApi.writeFile(AG_PATH, agDraft.value) }
function resetAgents() { agDraft.value = agLoadedText.value }
function agTemplate() {
  agDraft.value = `# 小丘全局指令

## 身份
你是小丘，跑在用户手机上的随身工作台。

## 工作习惯
- 全程使用中文
- 动手前先说计划，重大操作先确认
- 文件改动走 ~/工程档案

## 常驻规则
- （补充你的规则…）
`
}
function pmSegGo(s) {
  pmSeg.value = s // 两段草稿各自独立保存，切换不丢失
  if (s === 'agents' && agState.value === 0) loadAgents()
}

/* ═══════════ 技能与扩展子页 ═══════════ */
const skq = ref('')
const skillView = ref(null)
const skillsFiltered = computed(() => {
  const q = skq.value.trim().toLowerCase()
  return (chat.settings?.skills || []).filter(s => !q || s.name.toLowerCase().includes(q) || (s.description || '').toLowerCase().includes(q))
})
const extsFiltered = computed(() => {
  const q = skq.value.trim().toLowerCase()
  return (chat.settings?.extensions || []).filter(e => !q || e.name.toLowerCase().includes(q) || (e.id || '').toLowerCase().includes(q))
})
function toggleList(listName, item, key) {
  const s = chat.settings
  if (!s) return
  const cur = new Set(s[listName] || [])
  item.enabled ? cur.add(item[key]) : cur.delete(item[key])
  engineApi.setSettings({ [listName]: [...cur] })
}
function toggleSkill(s) { toggleList('disabledSkills', s, 'name') }
function toggleExt(e) { toggleList('disabledExtensions', e, 'id') }
async function viewSkill(s) {
  skillView.value = { name: s.name, desc: s.description, text: '加载中…', enabled: s.enabled, mode: 'load' }
  try {
    const m = await engineApi.readFile('.pi/agent/skills/' + s.name + '/SKILL.md')
    skillView.value.text = m && !m.binary ? m.text : '（二进制或读取失败）'
    skillView.value.mode = 'ok'
  } catch { skillView.value.text = '读取失败'; skillView.value.mode = 'err' }
}
function skillViewToggle() {
  const sv = skillView.value
  if (!sv) return
  toggleSkill({ name: sv.name, enabled: sv.enabled })
  sv.enabled = !sv.enabled
}
const skillMsg = ref('')
function reloadExts() { engineApi.reloadExtensions(); skillMsg.value = '已请求重载…'; setTimeout(() => skillMsg.value = '', 2000) }
function chipColor(name) {
  const cs = ['#3E7C59', '#E8853D', '#7A5CA8', '#3D6BE8', '#B85C3E', '#3E7C8A']
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return cs[h % cs.length]
}

/* ═══════════ P3：模型大脑 ═══════════ */
const mq = ref('')
const modelGroups = computed(() => {
  const q = mq.value.trim().toLowerCase()
  const list = q ? chat.models.filter(m => (m.name + ' ' + m.provider).toLowerCase().includes(q)) : chat.models
  const g = {}
  for (const m of list) (g[m.provider] = g[m.provider] || []).push(m)
  return g
})
const curModelId = computed(() => chat.state?.model?.id)
const THINK_BASE = [
  { v: 'off', t: '关' }, { v: 'minimal', t: '极简' }, { v: 'low', t: '低' },
  { v: 'medium', t: '中' }, { v: 'high', t: '高' }, { v: 'xhigh', t: '极高' }, { v: 'max', t: '最大' },
]
// 支持档位亮色可点，不支持灰显禁点（availableThinkingLevels 与 Chat.vue 同源）
const thinkLevels = computed(() => {
  const avail = chat.state?.availableThinkingLevels
  const ok = Array.isArray(avail) && avail.length ? new Set(avail) : null
  return THINK_BASE.map(l => ({ ...l, ok: ok ? ok.has(l.v) : true }))
})
const thinkSupported = computed(() => thinkLevels.value.some(l => l.ok && l.v !== 'off'))
const curThink = computed(() => chat.state?.thinkingLevel || 'off')

/* 内置供应商 key 弹层 */
const keyEdit = ref(null)
const keyInput = ref('')
function openKeyEdit(p) { keyEdit.value = p; keyInput.value = '' }
function saveKeyEdit() {
  if (!keyInput.value.trim()) return
  engineApi.setProviderKey(keyEdit.value.id, keyInput.value.trim())
  keyEdit.value = null
  setTimeout(() => engineApi.listProviders(), 600)
}
function clearKeyEdit() {
  engineApi.clearProviderKey(keyEdit.value.id)
  keyEdit.value = null
  setTimeout(() => engineApi.listProviders(), 600)
}

/* 自定义模型服务编辑（二级覆盖页） */
const APIS = [
  { v: 'openai-completions', t: 'OpenAI 兼容（chat/completions）' },
  { v: 'openai-responses', t: 'OpenAI Responses API' },
  { v: 'anthropic-messages', t: 'Anthropic Messages' },
  { v: 'google-generative-ai', t: 'Google Generative AI' },
]
const pEdit = ref(null)
function newProvider() { pEdit.value = { isNew: true, providerId: '', name: '', api: 'openai-completions', baseUrl: '', apiKey: '', authHeader: false, models: [] } }
function editProvider(p) { pEdit.value = { isNew: false, ...p, apiKey: p.apiKey || '', models: (p.models || []).map(m => ({ ...m })) } }
function pidFromName() { pEdit.value.providerId = (pEdit.value.name || '').trim().toLowerCase().replace(/[^a-z0-9-]+/g, '-') || ('svc-' + Date.now().toString(36)) }
function saveProvider() {
  const d = pEdit.value
  if (!d.providerId) pidFromName()
  engineApi.saveModelConfig(d.providerId, { name: d.name, api: d.api, baseUrl: d.baseUrl, apiKey: d.apiKey || undefined, authHeader: d.authHeader, models: d.models })
  pEdit.value = null
  setTimeout(() => engineApi.listModelsConfig(), 600)
}
const pDelConfirm = ref(false)
function delProvider() {
  if (!pDelConfirm.value) { pDelConfirm.value = true; setTimeout(() => pDelConfirm.value = false, 2500); return }
  engineApi.deleteModelConfig(pEdit.value.providerId)
  pEdit.value = null; pDelConfirm.value = false
  setTimeout(() => engineApi.listModelsConfig(), 600)
}
const mAddId = ref(''), mAddName = ref('')
function addModelRow() {
  const id = mAddId.value.trim()
  if (!id || !pEdit.value) return
  pEdit.value.models.push({ id, name: mAddName.value.trim() || undefined })
  mAddId.value = ''; mAddName.value = ''
}
function rmModelRow(i) { pEdit.value.models.splice(i, 1) }
const fetching = ref(false), fetchList = ref(null), fetchErr = ref('')
async function fetchModelList() {
  const d = pEdit.value
  if (!d || !d.baseUrl.trim()) { fetchErr.value = '先填 baseUrl'; return }
  fetching.value = true; fetchList.value = null; fetchErr.value = ''
  try {
    const r = await engineApi.fetchModels(d.baseUrl.trim(), d.apiKey || '', d.authHeader, d.api)
    if (r && r.ok) fetchList.value = r.models || []
    else fetchErr.value = (r && r.error) || '获取失败'
  } catch { fetchErr.value = '获取失败' }
  fetching.value = false
}
function pickFetch(m) {
  if (!pEdit.value.models.some(x => x.id === m.id)) pEdit.value.models.push({ id: m.id, name: m.name || undefined })
  fetchList.value = null
}

/* ═══════════ P4：终端工具 / 视觉桥 / 预设 ═══════════ */
const stTt = computed(() => chat.settings ? chat.settings.terminalToolsEnabled !== false : true)
const stBash = computed(() => !!chat.settings?.terminalBash)
const idleDraft = ref('15')
watch(() => chat.settings?.terminalBashIdleMs, v => { if (v != null) idleDraft.value = String(Math.round(v / 1000)) }, { immediate: true })
function saveIdle() {
  const s = Math.max(1, Math.min(120, parseInt(idleDraft.value) || 15))
  engineApi.setSettings({ terminalBashIdleMs: s * 1000 })
  idleMsg.value = '已设为 ' + s + ' 秒'
  setTimeout(() => idleMsg.value = '', 2000)
}
const idleMsg = ref('')

const stVision = computed(() => chat.settings ? chat.settings.visionBridgeEnabled !== false : true)
const visionOptions = computed(() => (chat.settings?.visionModels || []).map(m => ({ v: m.provider + '/' + m.id, t: m.label || m.id })))
const vModel = ref('')
const vModelLabel = computed(() => {
  if (!vModel.value) return '智能选择'
  return (visionOptions.value.find(o => o.v === vModel.value) || {}).t || vModel.value
})
const vMode = ref('append')
const vDraft = ref('')
let vSynced = false
function syncVision() {
  const s = chat.settings
  if (!s || vSynced) return
  vSynced = true
  vModel.value = s.visionBridgeModel || ''
  vMode.value = s.visionBridgePromptMode || 'append'
  vDraft.value = s.visionBridgePrompt || ''
}
const vDirty = computed(() => {
  const s = chat.settings
  if (!s) return false
  return vModel.value !== (s.visionBridgeModel || '') || vMode.value !== (s.visionBridgePromptMode || 'append') || vDraft.value !== (s.visionBridgePrompt || '')
})
function saveVision() {
  engineApi.setSettings({ visionBridgeModel: vModel.value || null, visionBridgePromptMode: vMode.value, visionBridgePrompt: vDraft.value })
  vSynced = false
  setTimeout(syncVision, 600)
}
function resetVision() { vSynced = false; syncVision() }

/* 预设（整套设置快照） */
const presetName = ref('')
const presetNaming = ref(false)
const presetDel = ref('')
function doSavePreset() {
  const n = presetName.value.trim()
  if (!n) return
  engineApi.sendSafe({ type: 'save_preset', name: n })
  presetNaming.value = false; presetName.value = ''
  setTimeout(() => engineApi.getSettings(), 600)
}
function applyPreset(p) {
  engineApi.sendSafe({ type: 'apply_preset', name: p.name })
  setTimeout(() => engineApi.getSettings(), 800)
  presetMsg.value = '已应用「' + p.name + '」'
  setTimeout(() => presetMsg.value = '', 2500)
}
function delPreset(p) {
  if (presetDel.value !== p.name) { presetDel.value = p.name; setTimeout(() => presetDel.value = '', 2500); return }
  engineApi.sendSafe({ type: 'delete_preset', name: p.name })
  presetDel.value = ''
  setTimeout(() => engineApi.getSettings(), 600)
}
const presetMsg = ref('')

/* ═══ 通用底部弹层选择器（替代原生 select 下拉）═══ */
const picker = ref(null) // { title, options:[{v,t}], cur, cb }
function openPicker(title, options, cur, cb) { picker.value = { title, options, cur, cb } }
function pickOption(o) { picker.value.cb(o.v); picker.value = null }

/* ═══════════ 启动 ═══════════ */
watch(() => chat.settings, () => {
  if (!promptDirty.value) { promptSynced = false; syncPromptDraft() }
  if (!vDirty.value) { vSynced = false; syncVision() }
})
watch(page, p => { if (p === 'models') { engineApi.listProviders(); engineApi.listModelsConfig() } })
onMounted(() => {
  syncPromptDraft()
  window.addEventListener('popstate', onPop)
  if (chat.status !== 'open' && chat.status !== 'connecting') connect()
  if (chat.status === 'open') engineApi.getSettings()
  loadPerm(); loadCfg()
})
onUnmounted(() => {
  window.removeEventListener('popstate', onPop)
  if (subPushed) { try { history.back() } catch {} }
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
  <!-- ═══════════ 首页 ═══════════ -->
  <div v-show="!page">
    <div class="homehead">
      <QiuLogo :size="36" />
      <div>
        <div class="homehead-t">设置</div>
        <div class="homehead-s">你说，我来办。</div>
      </div>
    </div>

    <div class="secl">引 擎</div>
    <div class="grp-card">
      <div v-for="g in engineGroups" :key="g.id" class="grp tap" @click="openPage(g.id)">
        <span class="grp-ic">{{ g.icon }}</span>
        <span class="grp-txt">
          <span class="grp-t">{{ g.t }}</span>
          <span class="grp-s">{{ g.sum }}</span>
        </span>
        <span class="grp-ar">›</span>
      </div>
    </div>

    <div class="secl">小 丘</div>
    <div class="grp-card">
      <div v-for="g in qiuGroups" :key="g.id" class="grp tap" @click="openPage(g.id)">
        <span class="grp-ic">{{ g.icon }}</span>
        <span class="grp-txt">
          <span class="grp-t">{{ g.t }}</span>
          <span class="grp-s">{{ g.sum }}</span>
        </span>
        <span class="grp-ar">›</span>
      </div>
    </div>

    <div style="height:20px;"></div>
  </div>

  <!-- ═══════════ 子页覆盖层 ═══════════ -->
  <div v-if="page" class="subp">
    <div class="subbar">
      <button class="backb tap" @click="back">‹</button>
      <span class="subbar-t">{{ pages[page] }}<span v-if="pmSeg === 'sys' && page === 'prompt' && promptDirty" class="dot"></span><span v-if="pmSeg === 'agents' && page === 'prompt' && agDirty" class="dot"></span></span>
      <span class="subbar-r">
        <button v-if="page === 'skills'" class="minib tap" @click="engineApi.getSettings()">↻</button>
      </span>
    </div>

    <!-- ── 📝 提示词 ── -->
    <template v-if="page === 'prompt'">
      <div class="segbar">
        <button :class="['segb', 'tap', { on: pmSeg === 'sys' }]" @click="pmSegGo('sys')">系统提示词</button>
        <button :class="['segb', 'tap', { on: pmSeg === 'agents' }]" @click="pmSegGo('agents')">全局指令</button>
      </div>

      <template v-if="pmSeg === 'sys'">
        <!-- 模式选择卡 -->
        <div class="modecard tap" :class="{ on: promptMode === 'append' }" @click="promptMode = 'append'">
          <div class="mode-ic">➕</div>
          <div class="mode-txt">
            <div class="mode-t">追加模式 <span class="pill ok-pill">推荐</span></div>
            <div class="mode-d">你的内容接在默认系统提示词之后，基础能力不受影响</div>
          </div>
          <span class="radio" :class="{ on: promptMode === 'append' }"></span>
        </div>
        <div class="modecard tap" :class="{ on: promptMode === 'replace' }" @click="promptMode = 'replace'">
          <div class="mode-ic">♻️</div>
          <div class="mode-txt">
            <div class="mode-t">替换模式 <span class="pill dim-pill">高级</span></div>
            <div class="mode-d">整体替换默认提示词，完全自定义（可能影响工具使用）</div>
          </div>
          <span class="radio" :class="{ on: promptMode === 'replace' }"></span>
        </div>

        <!-- 编辑器 -->
        <div class="card">
          <div class="edhead">
            <span class="edlabel">{{ promptMode === 'append' ? '追加内容' : '完整系统提示词' }}</span>
            <span class="edcount">{{ promptDraft.length }} 字</span>
          </div>
          <textarea v-model="promptDraft" class="edbox" rows="9"
            :placeholder="promptMode === 'append' ? '例：回复保持简洁；优先使用中文；手机环境注意省电…' : '替换后的完整系统提示词（建议先「填入默认」再改）'"></textarea>
          <div class="btn-line">
            <button v-if="promptMode === 'replace'" class="mini-btn tap" @click="fillDefault">📄 填入默认</button>
            <button class="mini-btn tap" @click="effOpen = !effOpen">{{ effOpen ? '▴ 收起生效预览' : '👁 查看当前生效' }}</button>
          </div>
          <div v-if="effOpen" class="effbox">
            <div class="effbox-t">
              <span>当前生效提示词（默认＋你的修改）</span>
              <button class="minib tap" @click="copyEff">{{ promptCopied ? '✓' : '复制' }}</button>
            </div>
            <div class="effbox-b">{{ chat.settings?.effectiveSystemPrompt || '（连接后显示）' }}</div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="card">
          <div class="hint">📋 全局指令文件（AGENTS.md）——对所有会话生效：身份设定、工作习惯、常驻规则。</div>
          <div v-if="agState === 1" class="muted" style="padding:20px 0;text-align:center;">加载中…</div>
          <div v-else-if="agState === 3" class="msg bad">读取失败（引擎未就绪？）</div>
          <template v-else>
            <div class="edhead">
              <span class="edlabel">.pi/agent/AGENTS.md</span>
              <span class="edcount">{{ agDraft.length }} 字</span>
            </div>
            <textarea v-model="agDraft" class="edbox mono" rows="14" placeholder="（文件为空）"></textarea>
            <div class="btn-line" v-if="!agDraft.trim()">
              <button class="mini-btn tap" @click="agTemplate">✨ 插入模板骨架</button>
            </div>
          </template>
        </div>
      </template>

      <!-- 底部悬浮保存条（有改动才出现） -->
      <div v-if="pmSeg === 'sys' && promptDirty" class="savebar">
        <button class="savebar-g tap" @click="resetPrompt">放弃修改</button>
        <button class="savebar-p tap" @click="savePrompt">💾 保存并生效</button>
      </div>
      <div v-else-if="pmSeg === 'agents' && agDirty" class="savebar">
        <button class="savebar-g tap" @click="resetAgents">放弃修改</button>
        <button class="savebar-p tap" @click="saveAgents">💾 保存</button>
      </div>
    </template>

    <!-- ── 🧩 技能与扩展 ── -->
    <template v-else-if="page === 'skills'">
      <div class="searchbox">
        <span class="search-ic">🔍</span>
        <input v-model="skq" placeholder="搜索技能 / 扩展" class="search-in">
        <button v-if="skq" class="search-x tap" @click="skq = ''">✕</button>
      </div>

      <div class="secl">技能 <em>{{ skillsFiltered.length }}/{{ (chat.settings?.skills || []).length }} · 已开 {{ skillsFiltered.filter(s => s.enabled).length }}</em>
        <span class="secl-r"></span>
      </div>
      <div class="grp-card">
        <div v-for="s in skillsFiltered" :key="s.name" class="srow">
          <div class="srow-txt tap" @click="viewSkill(s)">
            <div class="srow-l">
              <span class="srow-chip" :style="{ background: chipColor(s.name) }">{{ s.name.slice(0, 1).toUpperCase() }}</span>
              <span class="srow-t">{{ s.name }}</span>
            </div>
            <div class="srow-d">{{ s.description || '（无描述）' }}</div>
          </div>
          <div :class="['sw', 'tap', { on: s.enabled }]" @click="toggleSkill(s)"><div class="knob"></div></div>
        </div>
        <div v-if="chat.settings && !skillsFiltered.length" class="empty">没有匹配「{{ skq }}」的技能</div>
        <div v-if="!chat.settings" class="empty">连接引擎中…</div>
      </div>

      <div class="secl">扩展 <em>{{ extsFiltered.length }}/{{ (chat.settings?.extensions || []).length }}</em>
        <span class="secl-r"><button class="minib tap" @click="reloadExts">↻ 重载</button></span>
      </div>
      <div class="grp-card">
        <div v-for="e in extsFiltered" :key="e.id" class="srow">
          <div class="srow-txt">
            <div class="srow-l">
              <span class="srow-chip" :style="{ background: chipColor(e.name || e.id) }">⚡</span>
              <span class="srow-t">{{ e.name }}</span>
            </div>
            <div class="srow-d">{{ e.id }}</div>
          </div>
          <div :class="['sw', 'tap', { on: e.enabled }]" @click="toggleExt(e)"><div class="knob"></div></div>
        </div>
        <div v-if="chat.settings && !extsFiltered.length" class="empty">暂无扩展</div>
      </div>
      <div v-if="skillMsg" class="msg ok" style="margin-top:8px;">{{ skillMsg }}</div>
    </template>

    <!-- ── 🎙 语音与唤醒 ── -->
    <template v-else-if="page === 'voice'">
      <div class="secl">语音识别</div>
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#3E7C8A;">👂</span><span class="srow-t">识别引擎</span></div>
            <div class="srow-d">本地即时免费 · 云端更准需额度</div>
          </div>
          <button class="pickv tap" @click="openPicker('识别引擎', [{ v: 'local', t: '本地 SenseVoice（离线免费）' }, { v: 'cloud', t: '云端 GLM-ASR（更准需额度）' }], sttEngine, v => { sttEngine = v; pickStt() })">{{ sttLabel }} ›</button>
        </div>
      </div>

      <div class="secl">语音播报</div>
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#B85C3E;">🔊</span><span class="srow-t">播报引擎</span></div>
            <div class="srow-d">智能优先 = 云端可用用云端，否则小米本地</div>
          </div>
          <button class="pickv tap" @click="openPicker('播报引擎', [{ v: 'auto', t: '智能优先（推荐）' }, { v: 'cloud', t: '仅云端 GLM-TTS' }, { v: 'xiaomi', t: '小米本地 TTS' }], ttsEngine, v => { ttsEngine = v; pickEngine() })">{{ ttsLabel }} ›</button>
        </div>
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#7A5CA8;">🎵</span><span class="srow-t">云端音色</span></div>
            <div class="srow-d">GLM-TTS · 需智谱额度</div>
          </div>
          <button class="pickv tap" @click="openPicker('云端音色', [...voices.map(v => ({ v: v.id, t: v.name })), ...(cloneId ? [{ v: cloneId, t: '🎵 我的复刻音色' }] : [])], ttsVoice, v => { ttsVoice = v; pickVoice() })">{{ voiceLabel }} ›</button>
        </div>
        <div class="srow" style="flex-direction:column;align-items:stretch;gap:8px;">
          <div class="srow-l"><span class="srow-chip" style="background:#3D6BE8;">🎤</span><span class="srow-t">复刻音色 ID <em class="mini-hint">语音复刻上传录音后获得</em></span></div>
          <div style="display:flex;gap:8px;">
            <input v-model="cloneId" type="text" placeholder="粘贴 voice_id（选填）" style="font-size:12px;">
            <button class="mini-btn tap" @click="saveClone">保存</button>
          </div>
        </div>
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#E8853D;">💬</span><span class="srow-t">口语化改写</span></div>
            <div class="srow-d">长回复先改写成自然口语再朗读</div>
          </div>
          <div :class="['sw', 'tap', { on: voiceRewrite }]" @click="toggleRewrite"><div class="knob"></div></div>
        </div>
        <div class="srow-act">
          <button class="mini-btn tap" @click="testVoice(ttsEngine === 'xiaomi' ? 'xiaomi' : 'cloud')">{{ testing ? '播放中…' : '🔊 试听' }}</button>
          <button class="mini-btn tap" @click="testVoice('xiaomi')">🔊 小米本地</button>
        </div>
        <div v-if="voiceMsg" :class="['msg', voiceOk ? 'ok' : 'bad']" style="margin-top:8px;">{{ voiceMsg }}</div>
      </div>

      <div class="secl">全局唤醒</div>
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l">
              <span class="srow-chip" style="background:#3E7C59;">喊</span><span class="srow-t">「小丘」随时唤醒</span>
              <span :class="['pill', wakeOn ? 'ok-pill' : 'dim-pill']">{{ wakeOn ? '待命中' : '已关闭' }}</span>
            </div>
            <div class="srow-d" style="white-space:normal;">任意界面/息屏喊「小丘」→ 回应后直接下指令</div>
          </div>
          <div :class="['sw', 'tap', { on: wakeOn }]" @click="toggleWake"><div class="knob"></div></div>
        </div>
        <div v-if="wakeMsg" class="msg ok" style="margin-top:8px;">{{ wakeMsg }}</div>
        <div class="footnote">⚠ 持续使用麦克风与少量电量 · 需允许「自启动」与「后台运行」</div>
      </div>
    </template>

    <!-- ── 📱 手机与权限 ── -->
    <template v-else-if="page === 'phone'">
      <div class="secl">权限中心</div>
      <div class="grp-card">
        <div v-for="r in permRows" :key="r.k" class="srow">
          <div class="srow-txt">
            <div class="srow-t">{{ r.label }}</div>
            <div class="srow-d">{{ r.hint }}</div>
          </div>
          <button :class="['minib', 'tap', perm && perm[r.k] ? 'minib-ok' : 'minib-bad']" @click="r.type && openPerm(r.type)">
            {{ perm ? (perm[r.k] ? '✓ 已授权' : '去授权') : '…' }}
          </button>
        </div>
      </div>

      <div class="secl">悬浮球</div>
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-t">悬浮球</div>
            <div class="srow-d" style="white-space:normal;">单击开小丘 · <b>双击开语音对话</b> · 拖动贴边</div>
          </div>
          <button class="minib tap" @click="toggleBall(); loadPerm()">切换</button>
        </div>
      </div>
    </template>

    <!-- ── ℹ️ 关于 ── -->
    <template v-else-if="page === 'about'">
      <div class="grp-card" style="padding:18px 16px;">
        <div style="display:flex;align-items:center;gap:12px;">
          <QiuLogo :size="44" />
          <div>
            <div style="font-weight:700;font-size:17px;">小丘</div>
            <div class="muted" style="font-size:12px;">山间工作台 · 1.0.0-dev</div>
          </div>
        </div>
      </div>
      <div class="grp-card">
        <div class="kv"><span>执行引擎</span><b>pi coding-agent</b></div>
        <div class="kv"><span>快脑</span><b>GLM-5.3-flash</b></div>
        <div class="kv"><span>语音识别</span><b>SenseVoice / GLM-ASR</b></div>
        <div class="kv"><span>语音合成</span><b>GLM-TTS / 小米</b></div>
        <div class="kv"><span>开源协议</span><b>GPL v3</b></div>
      </div>
    </template>

    <!-- ── 🤖 模型大脑（P3） ── -->
    <template v-else-if="page === 'models'">
      <!-- 当前模型卡 -->
      <div class="grp-card" style="padding:14px 14px 12px;">
        <div class="curm">
          <span class="srow-chip big" :style="{ background: chipColor(curModelId || '?') }">AI</span>
          <div style="flex:1;min-width:0;">
            <div class="curm-n">{{ chat.state?.model?.name || chat.state?.model?.id || '—' }}
              <span v-if="chat.state?.model?.vision" class="pill dim-pill">👁 视觉</span>
              <span v-if="chat.state?.model?.reasoning" class="pill dim-pill">🧠 思考</span>
            </div>
            <div class="curm-p">{{ chat.state?.model?.provider || '' }}</div>
          </div>
        </div>
        <div class="thinkline">
          <span class="thinklabel">思考</span>
          <div v-if="thinkSupported" class="thinkpills">
            <button v-for="l in thinkLevels" :key="l.v" :class="['tp', 'tap', { on: curThink === l.v, dis: !l.ok }]"
              :title="l.ok ? '' : '当前模型不支持此档位'" @click="l.ok && engineApi.setThinking(l.v)">{{ l.t }}</button>
          </div>
          <span v-else class="muted" style="font-size:12px;">当前模型不支持思考档</span>
        </div>
      </div>

      <!-- 默认模型 -->
      <div class="secl">默认模型</div>
      <div class="searchbox">
        <span class="search-ic">🔍</span>
        <input v-model="mq" placeholder="搜索模型" class="search-in">
        <button v-if="mq" class="search-x tap" @click="mq = ''">✕</button>
      </div>
      <template v-for="(list, prov) in modelGroups" :key="prov">
        <div class="prow-h">{{ prov }}<em>{{ list.length }}</em></div>
        <div class="grp-card">
          <div v-for="m in list" :key="m.id" class="srow tap" @click="engineApi.setModel(m.id)">
            <div class="srow-txt">
              <div class="srow-t">{{ m.name }}<span v-if="m.vision || m.reasoning" class="mbadges"><i v-if="m.vision">👁</i><i v-if="m.reasoning">🧠</i></span></div>
            </div>
            <span v-if="m.id === curModelId" class="pill ok-pill">当前 ✓</span>
          </div>
        </div>
      </template>
      <div v-if="!chat.models.length" class="empty">模型清单载入中…</div>

      <!-- 内置供应商 -->
      <div class="secl">内置供应商 <em>填 Key 即用</em></div>
      <div class="grp-card">
        <div v-for="p in chat.providers" :key="p.id" class="srow tap" @click="openKeyEdit(p)">
          <div class="srow-txt">
            <div class="srow-t">{{ p.name || p.id }}</div>
            <div class="srow-d">{{ p.source ? '来源：' + p.source : p.id }}</div>
          </div>
          <span :class="['pill', p.configured ? 'ok-pill' : 'dim-pill']">{{ p.configured ? '已配置' : '未配置' }}</span>
          <span class="grp-ar">›</span>
        </div>
        <div v-if="!chat.providers.length" class="empty">载入中…</div>
      </div>

      <!-- 自定义服务 -->
      <div class="secl">自定义模型服务 <em>models.json</em>
        <span class="secl-r"><button class="minib tap" @click="newProvider">＋ 新建</button></span>
      </div>
      <div class="grp-card">
        <div v-for="p in chat.modelsCfg" :key="p.providerId" class="srow tap" @click="editProvider(p)">
          <div class="srow-txt">
            <div class="srow-t">{{ p.name || p.providerId }}</div>
            <div class="srow-d">{{ (p.models || []).length }} 模型 · {{ p.baseUrl || p.api }}</div>
          </div>
          <span class="grp-ar">›</span>
        </div>
        <div v-if="!chat.modelsCfg.length" class="empty">暂无自定义服务 · 点「新建」接入任意 OpenAI 兼容接口</div>
      </div>

      <!-- 预设 -->
      <div class="secl">预设 <em>整套设置快照（模型+思考+提示词+开关）</em>
        <span class="secl-r"><button class="minib tap" @click="presetNaming = true">＋ 存当前</button></span>
      </div>
      <div v-if="presetNaming" class="card" style="padding:12px;">
        <div style="display:flex;gap:8px;">
          <input v-model="presetName" placeholder="预设名（如：极速省电）" style="font-size:13px;" @keydown.enter="doSavePreset">
          <button class="mini-btn tap" @click="doSavePreset">保存</button>
          <button class="minib tap" @click="presetNaming = false">✕</button>
        </div>
      </div>
      <div class="grp-card">
        <div v-for="p in (chat.settings?.presets || [])" :key="p.name" class="srow">
          <div class="srow-txt">
            <div class="srow-t">{{ p.name }}</div>
          </div>
          <button class="minib tap" @click="applyPreset(p)">应用</button>
          <button class="minib tap" :style="presetDel === p.name ? { background:'var(--bad)',color:'#fff',borderColor:'var(--bad)' } : { color:'var(--bad)' }" @click="delPreset(p)">{{ presetDel === p.name ? '确认?' : '🗑' }}</button>
        </div>
        <div v-if="chat.settings && !(chat.settings.presets || []).length" class="empty">暂无预设 · 调好设置后点「存当前」</div>
      </div>
      <div v-if="presetMsg" class="msg ok" style="margin-top:8px;">{{ presetMsg }}</div>
    </template>

    <!-- ── ⌨ 终端工具（P4） ── -->
    <template v-else-if="page === 'termtools'">
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#3D6BE8;">⌨</span><span class="srow-t">终端工具</span></div>
            <div class="srow-d" style="white-space:normal;">允许 AI 使用终端跑命令</div>
          </div>
          <div :class="['sw', 'tap', { on: stTt }]" @click="engineApi.setSettings({ terminalToolsEnabled: !stTt })"><div class="knob"></div></div>
        </div>
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#B85C3E;">🤖</span><span class="srow-t">bash 接管</span></div>
            <div class="srow-d" style="white-space:normal;">AI 的命令在终端页「🤖 AI 命令」区实时可见，环境保留（cd / venv 不丢）</div>
          </div>
          <div :class="['sw', 'tap', { on: stBash }]" @click="engineApi.setSettings({ terminalBash: !stBash })"><div class="knob"></div></div>
        </div>
        <div class="srow" style="flex-direction:column;align-items:stretch;gap:8px;">
          <div class="srow-l"><span class="srow-chip" style="background:#7A5CA8;">⏱</span><span class="srow-t">空闲判定 <em class="mini-hint">命令静止多久算"跑完"</em></span></div>
          <div style="display:flex;gap:8px;align-items:center;">
            <input v-model="idleDraft" type="number" min="1" max="120" style="font-size:13px;flex:1;" @blur="saveIdle" @keydown.enter="saveIdle">
            <span style="font-size:12px;color:var(--muted);">秒</span>
            <button class="mini-btn tap" @click="saveIdle">保存</button>
          </div>
          <div v-if="idleMsg" class="msg ok" style="margin-top:0;">{{ idleMsg }}</div>
        </div>
        <div class="footnote">💡 开关改动即时保存；若 AI 正在回复中，下一回合生效。bash 接管打开后，去终端页看 AI 跑命令。</div>
      </div>
    </template>

    <!-- ── 👁 视觉桥（P4） ── -->
    <template v-else-if="page === 'vision'">
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#3E7C8A;">👁</span><span class="srow-t">视觉桥</span></div>
            <div class="srow-d" style="white-space:normal;">发给 AI 的图片先由视觉模型转成文字描述（主模型不识图时的桥接）</div>
          </div>
          <div :class="['sw', 'tap', { on: stVision }]" @click="engineApi.setSettings({ visionBridgeEnabled: !stVision })"><div class="knob"></div></div>
        </div>
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-l"><span class="srow-chip" style="background:#E8853D;">🎯</span><span class="srow-t">视觉模型</span></div>
            <div class="srow-d">{{ vModel ? '已指定' : '智能选择（自动找识图模型）' }}</div>
          </div>
          <button class="pickv tap" @click="openPicker('视觉模型', [{ v: '', t: '智能选择（自动找识图模型）' }, ...visionOptions], vModel, v => vModel = v)">{{ vModelLabel }} ›</button>
        </div>
      </div>
      <div class="card">
        <div class="edhead">
          <span class="edlabel">视觉桥提示词</span>
          <div class="segbar" style="margin:0;width:150px;">
            <button :class="['segb', 'tap', { on: vMode === 'append' }]" style="padding:6px 2px;font-size:12px;" @click="vMode = 'append'">追加</button>
            <button :class="['segb', 'tap', { on: vMode === 'replace' }]" style="padding:6px 2px;font-size:12px;" @click="vMode = 'replace'">替换</button>
          </div>
        </div>
        <textarea v-model="vDraft" class="edbox" rows="6" placeholder="控制视觉模型如何描述图片（留空用默认）"></textarea>
      </div>
      <div v-if="vDirty" class="savebar">
        <button class="savebar-g tap" @click="resetVision">放弃</button>
        <button class="savebar-p tap" @click="saveVision">💾 保存</button>
      </div>
    </template>
    <div style="height:24px;"></div>
  </div>

  <!-- 通用选择器（底部弹层） -->
  <div v-if="picker" class="skview" @click="picker = null">
    <div class="skview-b" @click.stop style="max-height:68vh;overflow-y:auto;padding-bottom:12px;">
      <div class="skview-h">
        <div class="skview-tt" style="flex:1;"><b>{{ picker.title }}</b></div>
        <button class="minib tap" @click="picker = null">✕</button>
      </div>
      <div v-for="o in picker.options" :key="String(o.v)" :class="['pk-row', 'tap', { on: o.v === picker.cur }]" @click="pickOption(o)">
        <span>{{ o.t }}</span>
        <span v-if="o.v === picker.cur" class="ok">✓</span>
      </div>
    </div>
  </div>

  <!-- 供应商 Key 弹层 -->
  <div v-if="keyEdit" class="skview" @click="keyEdit = null">
    <div class="skview-b" @click.stop style="padding-bottom:20px;">
      <div class="skview-h">
        <div class="skview-tt" style="flex:1;">
          <b>{{ keyEdit.name || keyEdit.id }}</b>
          <div class="muted" style="font-size:11px;">{{ keyEdit.configured ? '已配置（' + (keyEdit.source || '已保存') + '）' : '未配置' }}</div>
        </div>
        <button class="minib tap" @click="keyEdit = null">✕</button>
      </div>
      <label style="margin:10px 0 6px;">API Key</label>
      <input v-model="keyInput" type="password" placeholder="粘贴 Key（保存后引擎即时生效）" style="font-size:13px;">
      <div style="display:flex;gap:8px;margin-top:12px;">
        <button v-if="keyEdit.configured" class="savebar-g tap" style="flex:1;padding:11px;font-size:13px;" @click="clearKeyEdit">清除密钥</button>
        <button class="savebar-p tap" style="flex:2;padding:11px;font-size:14px;" @click="saveKeyEdit">保存</button>
      </div>
    </div>
  </div>

  <!-- 自定义服务编辑（二级覆盖页） -->
  <div v-if="pEdit" class="subp p2">
    <div class="subbar">
      <button class="backb tap" @click="pEdit = null">‹</button>
      <span class="subbar-t">{{ pEdit.isNew ? '新建模型服务' : (pEdit.name || pEdit.providerId) }}</span>
      <span class="subbar-r"></span>
    </div>
    <div class="card">
      <label>服务名称</label>
      <input v-model="pEdit.name" placeholder="例：智谱开放平台" @blur="pEdit.isNew && pidFromName()">
      <label>API 类型</label>
      <select v-model="pEdit.api">
        <option v-for="a in APIS" :key="a.v" :value="a.v">{{ a.t }}</option>
      </select>
      <label>Base URL</label>
      <input v-model="pEdit.baseUrl" placeholder="https://open.bigmodel.cn/api/paas/v4">
      <label>API Key（可选，仅存本机）</label>
      <input v-model="pEdit.apiKey" type="password" placeholder="sk-…">
      <div class="srow" style="padding:10px 0 0;">
        <div class="srow-txt">
          <div class="srow-t" style="font-size:14px;">Key 放 Authorization 头</div>
          <div class="srow-d">关闭时按 OpenAI 惯例放 x-api-key（LM Studio 等需要开）</div>
        </div>
        <div :class="['sw', 'tap', { on: pEdit.authHeader }]" @click="pEdit.authHeader = !pEdit.authHeader"><div class="knob"></div></div>
      </div>
    </div>

    <div class="secl">模型列表 <em>{{ pEdit.models.length }} 个</em>
      <span class="secl-r"><button class="minib tap" :disabled="fetching" @click="fetchModelList">{{ fetching ? '获取中…' : '⤓ 在线获取' }}</button></span>
    </div>
    <div v-if="fetchErr" class="msg bad" style="margin:0 0 8px;">{{ fetchErr }}</div>
    <div class="grp-card">
      <div v-for="(m, i) in pEdit.models" :key="m.id + i" class="srow">
        <div class="srow-txt">
          <div class="srow-t">{{ m.id }}</div>
          <div class="srow-d">{{ m.name || '' }}</div>
        </div>
        <button class="minib tap" style="color:var(--bad);" @click="rmModelRow(i)">✕</button>
      </div>
      <div v-if="!pEdit.models.length" class="empty">手动添加或在线获取</div>
    </div>
    <div class="card" style="padding:12px;">
      <div style="display:flex;gap:8px;">
        <input v-model="mAddId" placeholder="模型 id（如 glm-4.7）" style="font-size:13px;">
        <input v-model="mAddName" placeholder="显示名（可选）" style="font-size:13px;">
        <button class="minib tap" @click="addModelRow">＋</button>
      </div>
    </div>

    <!-- 在线获取结果 -->
    <template v-if="fetchList">
      <div class="secl">在线列表 <em>点选加入</em></div>
      <div class="grp-card">
        <div v-for="m in fetchList" :key="m.id" class="srow tap" @click="pickFetch(m)">
          <div class="srow-txt">
            <div class="srow-t">{{ m.id }}</div>
            <div class="srow-d">{{ m.name || '' }}</div>
          </div>
          <span class="pill dim-pill">＋ 加入</span>
        </div>
      </div>
    </template>

    <div style="display:flex;gap:10px;margin-top:14px;">
      <button v-if="!pEdit.isNew" class="savebar-g tap" style="flex:1;padding:13px;" :style="pDelConfirm ? { background:'var(--bad)', color:'#fff', borderColor:'var(--bad)' } : {}" @click="delProvider">{{ pDelConfirm ? '再点确认删除' : '删除服务' }}</button>
      <button class="savebar-p tap" style="flex:2;" @click="saveProvider">💾 保存服务</button>
    </div>
    <div style="height:24px;"></div>
  </div>

  <!-- 技能说明弹层 -->
  <div v-if="skillView" class="skview" @click="skillView = null">
    <div class="skview-b" @click.stop>
      <div class="skview-h">
        <span class="srow-chip big" :style="{ background: chipColor(skillView.name) }">{{ skillView.name.slice(0, 1).toUpperCase() }}</span>
        <div class="skview-tt">
          <b>{{ skillView.name }}</b>
          <div class="muted" style="font-size:11px;">{{ skillView.desc || '技能说明' }}</div>
        </div>
        <div :class="['sw', 'tap', { on: skillView.enabled }]" @click="skillViewToggle"><div class="knob"></div></div>
        <button class="minib tap" @click="skillView = null">✕</button>
      </div>
      <div class="skview-c">{{ skillView.text }}</div>
      <div class="skview-f">只读 · 编辑功能后续开放</div>
    </div>
  </div>
</template>

<style scoped>
/* ═══ 首页 ═══ */
.homehead { display:flex; align-items:center; gap:12px; margin:10px 4px 14px; }
.homehead-t { font-size:22px; font-weight:800; letter-spacing:.5px; }
.homehead-s { font-size:12px; color:var(--muted); }
.secl { margin:16px 4px 7px; font-size:12px; font-weight:700; color:var(--muted); letter-spacing:2px; display:flex; align-items:center; gap:6px; }
.secl em { font-style:normal; font-weight:400; letter-spacing:0; font-size:11px; opacity:.85; }
.secl-r { margin-left:auto; }

.grp-card { background:var(--card); border:1px solid var(--line); border-radius:18px; overflow:hidden; box-shadow:var(--shadow); margin-bottom:2px; }
.grp { display:flex; align-items:center; gap:12px; padding:13px 14px; border-bottom:1px solid var(--line); background:#fff; }
.grp:last-child { border-bottom:0; }
.grp:active { background:var(--bg); }
.grp-ic { font-size:19px; width:38px; height:38px; display:flex; align-items:center; justify-content:center; background:var(--hill-soft); border-radius:11px; }
.grp-txt { flex:1; min-width:0; display:flex; flex-direction:column; }
.grp-t { font-size:15px; font-weight:700; }
.grp-s { font-size:12px; color:var(--muted); margin-top:2px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.grp-ar { color:#B9B4A6; font-size:20px; font-weight:300; }

.lgcy-h { display:flex; justify-content:space-between; align-items:center; padding:13px 14px; font-size:14px; font-weight:600; }
.lgcy-h em { font-style:normal; font-size:11px; color:var(--muted); font-weight:400; }
.lgcy-b { padding:0 14px 14px; }

/* ═══ 子页壳 ═══ */
.subp { position:fixed; inset:0; z-index:60; background:var(--bg); overflow-y:auto; padding:0 12px 60px; animation:slidein .22s ease; }
@keyframes slidein { from { transform:translateX(100%); } to { transform:none; } }
.subbar { position:sticky; top:0; z-index:2; display:flex; align-items:center; gap:4px; padding:8px 2px; background:var(--bg); border-bottom:1px solid var(--line); margin-bottom:8px; min-height:46px; }
.backb { border:0; background:var(--card); color:var(--ink); font-size:20px; font-weight:700; width:34px; height:34px; border-radius:11px; border:1px solid var(--line); }
.subbar-t { font-size:17px; font-weight:800; margin:0 auto; display:flex; align-items:center; gap:6px; }
.subbar-r { min-width:34px; display:flex; justify-content:flex-end; }
.dot { width:8px; height:8px; border-radius:50%; background:var(--dawn); display:inline-block; }

/* ═══ 通用小组件 ═══ */
.mini-btn { border:1px solid var(--line); background:var(--card); border-radius:10px; padding:7px 13px; font-size:12px; font-weight:600; color:var(--ink); }
.mini-btn:active { background:var(--hill); border-color:var(--hill); color:#fff; }
.minib { border:1px solid var(--line); background:var(--card); border-radius:9px; padding:5px 10px; font-size:12px; font-weight:600; color:var(--ink); }
.minib-ok { background:var(--hill-soft); border-color:var(--hill); color:var(--hill); }
.minib-bad { background:var(--dawn); border-color:var(--dawn); color:#fff; }
.msg { margin-top:10px; font-size:13px; text-align:center; min-height:18px; }
.msg.ok { color:var(--hill); } .msg.bad { color:var(--bad); }
.pill { font-size:10px; padding:2px 8px; border-radius:99px; font-weight:700; }
.ok-pill { background:var(--hill-soft); color:var(--hill); }
.dim-pill { background:#EFEDE6; color:var(--muted); }
.muted { color:var(--muted); }
.kv { display:flex; justify-content:space-between; font-size:13px; padding:10px 14px; border-bottom:1px solid var(--line); }
.kv:last-child { border:0; }
.kv b { font-weight:600; }
.slim { max-width:150px; font-size:13px; padding:8px 10px; }
.empty { padding:22px 16px; text-align:center; font-size:13px; color:var(--muted); }
.hint { font-size:12px; color:var(--muted); line-height:1.65; margin-bottom:8px; }
.footnote { font-size:11px; color:var(--muted); padding:10px 14px 12px; line-height:1.6; border-top:1px dashed var(--line); }
.card { background:var(--card); border:1px solid var(--line); border-radius:18px; padding:14px; margin-bottom:12px; box-shadow:var(--shadow); }
.btn { border:0; border-radius:14px; background:var(--hill); color:#fff; font-size:15px; font-weight:700; padding:12px; width:100%; }
.btn:active { opacity:.85; }
label { display:block; font-size:13px; color:var(--muted); margin:12px 0 6px; }

/* ═══ 提示词页 ═══ */
.segbar { display:flex; gap:8px; margin:4px 0 12px; }
.segb { flex:1; border:1px solid var(--line); background:var(--card); color:var(--muted); border-radius:13px; padding:10px 4px; font-size:14px; font-weight:700; transition:all .15s; }
.segb.on { background:var(--hill); border-color:var(--hill); color:#fff; box-shadow:0 3px 10px rgba(62,124,89,.3); }

.modecard { display:flex; align-items:center; gap:12px; background:var(--card); border:2px solid var(--line); border-radius:16px; padding:13px 14px; margin-bottom:10px; transition:all .15s; }
.modecard.on { border-color:var(--hill); background:#F4FAF6; }
.mode-ic { font-size:22px; }
.mode-txt { flex:1; }
.mode-t { font-size:15px; font-weight:700; display:flex; align-items:center; gap:6px; }
.mode-d { font-size:12px; color:var(--muted); margin-top:3px; line-height:1.5; }
.radio { width:20px; height:20px; border-radius:50%; border:2px solid var(--line); flex-shrink:0; transition:all .15s; }
.radio.on { border-color:var(--hill); background:var(--hill); box-shadow:inset 0 0 0 4px #fff; }

.edhead { display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; }
.edlabel { font-size:13px; font-weight:700; color:var(--muted); }
.edcount { font-size:11px; color:var(--muted); background:var(--bg); border-radius:8px; padding:2px 8px; }
.edbox { width:100%; border:1px solid var(--line); border-radius:12px; background:var(--bg); padding:12px; font-size:14px; line-height:1.7; resize:vertical; min-height:150px; font-family:inherit; }
.edbox.mono { font:13px ui-monospace,monospace; }
.btn-line { display:flex; gap:8px; margin-top:10px; flex-wrap:wrap; }

.effbox { background:var(--bg); border:1px dashed var(--line); border-radius:12px; margin-top:10px; overflow:hidden; }
.effbox-t { display:flex; justify-content:space-between; align-items:center; padding:8px 12px; font-size:11px; color:var(--muted); border-bottom:1px dashed var(--line); }
.effbox-b { padding:10px 12px; font:11px ui-monospace,monospace; white-space:pre-wrap; max-height:220px; overflow-y:auto; color:var(--muted); line-height:1.6; }

.savebar { position:fixed; left:12px; right:12px; bottom:calc(14px + env(safe-area-inset-bottom)); z-index:65; display:flex; gap:10px; animation:upin .25s ease; }
.savebar-g { flex:1; border:1px solid var(--line); background:var(--card); border-radius:14px; padding:13px; font-size:14px; font-weight:600; color:var(--muted); }
.savebar-p { flex:2; border:0; background:var(--hill); color:#fff; border-radius:14px; padding:13px; font-size:15px; font-weight:700; box-shadow:0 4px 14px rgba(62,124,89,.35); }
@keyframes upin { from { transform:translateY(70px); opacity:0; } }

/* ═══ 技能页 ═══ */
.searchbox { display:flex; align-items:center; gap:8px; background:var(--card); border:1px solid var(--line); border-radius:13px; padding:0 12px; margin:2px 0 4px; box-shadow:var(--shadow); }
.search-ic { font-size:13px; }
.search-in { border:0; background:none; padding:11px 0; font-size:14px; flex:1; }
.search-in:focus { border:0; }
.search-x { border:0; background:#EFEDE6; border-radius:50%; width:20px; height:20px; font-size:11px; color:var(--muted); }

.srow { display:flex; align-items:center; gap:10px; padding:11px 14px; border-bottom:1px solid var(--line); }
.srow:last-child { border-bottom:0; }
.srow-txt { flex:1; min-width:0; }
.srow-l { display:flex; align-items:center; gap:8px; }
.srow-t { font-size:14px; font-weight:700; }
.srow-chip { width:26px; height:26px; border-radius:8px; color:#fff; font-size:13px; font-weight:800; display:inline-flex; align-items:center; justify-content:center; flex-shrink:0; }
.srow-chip.big { width:34px; height:34px; border-radius:10px; font-size:16px; }
.srow-d { font-size:12px; color:var(--muted); margin-top:3px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.srow-act { display:flex; gap:8px; padding:10px 14px 12px; border-top:1px dashed var(--line); }
.mini-hint { font-style:normal; font-size:11px; color:var(--muted); font-weight:400; }

.pickv { border:1px solid var(--line); background:var(--bg); border-radius:10px; padding:8px 12px; font-size:13px; font-weight:600; color:var(--ink); max-width:160px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; flex-shrink:0; }
.pk-row { display:flex; justify-content:space-between; align-items:center; padding:13px 16px; border-bottom:1px solid var(--line); font-size:14px; }
.pk-row:last-child { border-bottom:0; }
.pk-row.on { color:var(--hill); font-weight:700; background:var(--hill-soft); }

/* 技能弹层 */
.p2 { z-index:68; }
.curm { display:flex; align-items:center; gap:12px; }
.curm-n { font-size:16px; font-weight:800; }
.curm-p { font-size:12px; color:var(--muted); margin-top:2px; }
.thinkline { display:flex; align-items:center; gap:8px; margin-top:12px; padding-top:11px; border-top:1px dashed var(--line); }
.thinklabel { font-size:12px; color:var(--muted); font-weight:700; flex-shrink:0; }
.thinkpills { display:flex; gap:5px; flex-wrap:wrap; }
.tp { border:1px solid var(--line); background:var(--card); border-radius:99px; padding:5px 11px; font-size:12px; font-weight:600; color:var(--muted); }
.tp.on { background:var(--hill); border-color:var(--hill); color:#fff; }
.tp.dis { opacity:.32; }
.mbadges { margin-left:5px; font-size:11px; font-style:normal; }
.mbadges i { font-style:normal; margin-left:2px; }
.prow-h { font-size:12px; font-weight:700; color:var(--muted); margin:12px 4px 6px; letter-spacing:.5px; }
.prow-h em { font-style:normal; font-weight:400; margin-left:6px; opacity:.7; }

.skview { position:fixed; inset:0; z-index:70; background:rgba(24,30,20,.5); display:flex; align-items:flex-end; animation:fadein .15s ease; }
.skview-b { background:var(--card); border-radius:20px 20px 0 0; width:100%; max-height:80vh; display:flex; flex-direction:column; padding:14px 14px 10px; animation:upin .2s ease; }
.skview-h { display:flex; align-items:center; gap:10px; padding-bottom:10px; border-bottom:1px solid var(--line); }
.skview-tt { flex:1; min-width:0; }
.skview-tt b { font-size:15px; }
.skview-c { flex:1; overflow-y:auto; background:var(--bg); border-radius:12px; padding:12px 14px; margin:10px 0; font:12px ui-monospace,monospace; white-space:pre-wrap; line-height:1.7; max-height:60vh; }
.skview-f { text-align:center; font-size:11px; color:var(--muted); padding:4px 0 8px; }
@keyframes fadein { from { opacity:0; } }
</style>
