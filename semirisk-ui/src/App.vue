<template>
  <main v-if="!session" class="login">
    <section class="panel login-card">
      <h1>供应链风险智能管理系统</h1>
      <p class="muted">前后端分离 · Vue · 微服务 API</p>
      <div class="grid">
        <input v-model="loginForm.username" class="input" placeholder="账号" />
        <input v-model="loginForm.password" class="input" placeholder="密码" type="password" />
        <label class="muted"><input v-model="loginForm.rememberMe" type="checkbox" /> 记住密码</label>
        <button class="btn" @click="login">进入系统</button>
        <button class="btn secondary" @click="resetPassword">忘记密码</button>
      </div>
    </section>
  </main>

  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">SR</span><span>SemiRisk</span></div>
      <nav class="nav">
        <button v-for="item in allowedNavItems" :key="item.key" :class="{ active: view === item.key }" @click="view = item.key">
          {{ item.label }}
        </button>
      </nav>
    </aside>

    <section class="main">
      <header class="topbar">
        <h2 class="page-title">{{ currentTitle }}</h2>
        <div class="toolbar">
          <span class="muted">当前用户：{{ session.displayName || session.username }} · {{ session.role }} · {{ now }}</span>
          <button class="btn secondary" @click="logout">退出</button>
        </div>
      </header>

      <section v-if="view === 'dashboard'" class="grid">
        <div class="grid cols-4">
          <div v-for="kpi in dashboard.kpis || []" :key="kpi.name" class="panel kpi">
            <div><span class="muted">{{ kpi.name }}</span><br /><strong>{{ kpi.value }}</strong></div>
            <span class="success">{{ kpi.trend }}</span>
          </div>
        </div>
        <div class="grid cols-2">
          <div class="panel">
            <h3>AI 本日风险测算</h3>
            <p>{{ dashboard.aiSummary }}</p>
            <p class="muted">刷新时间：{{ dashboard.refreshedAt }}</p>
            <button class="btn" @click="recalculateRisk">立即重新测算</button>
          </div>
          <div class="panel">
            <h3>爬虫信号</h3>
            <table>
              <tbody>
                <tr v-for="signal in dashboard.dailyRisk?.signals || []" :key="signal.id">
                  <td>{{ signal.source }}</td>
                  <td>{{ signal.title }}</td>
                  <td><span class="badge mid">{{ signal.riskScore }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section v-if="view === 'upload'" class="grid cols-2">
        <div class="panel">
          <h3>数据上传与清洗</h3>
          <label class="drop">
            <span>点击选择 Excel / CSV / PDF / ZIP，单文件 50MB 内</span>
            <input hidden type="file" @change="uploadFile" />
          </label>
          <div class="toolbar">
            <button class="btn secondary" @click="downloadTemplate">下载模板</button>
            <button class="btn" @click="parseUploads">开始校验并导入</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>文件</th><th>状态</th><th>行数</th></tr></thead>
              <tbody><tr v-for="task in uploads" :key="task.id"><td>{{ task.filename }}</td><td>{{ task.status }}</td><td>{{ task.rows }}</td></tr></tbody>
            </table>
          </div>
        </div>
        <div class="panel">
          <h3>AI 清洗日志</h3>
          <div class="console">
            <p v-for="line in logs" :key="line">{{ line }}</p>
          </div>
        </div>
      </section>

      <section v-if="view === 'analysis'" class="grid">
        <div class="toolbar">
          <button class="btn" @click="loadRiskAnalysis('24h')">近24小时</button>
          <button class="btn secondary" @click="loadRiskAnalysis('7d')">近7天</button>
          <button class="btn secondary" @click="loadRiskAnalysis('30d')">近30天</button>
        </div>
        <div class="grid cols-3">
          <div class="panel"><h3>系统评分</h3><strong class="warning">{{ analysis.score }}</strong></div>
          <div class="panel"><h3>核心研判</h3><p>{{ analysis.summary }}</p></div>
          <div class="panel"><h3>推理链</h3><p v-for="r in analysis.reasoning || []" :key="r">{{ r }}</p></div>
        </div>
        <div class="cards">
          <div v-for="s in analysis.solutions || []" :key="s.name" class="card-option">
            <b>{{ s.name }}</b><p class="success">{{ s.feasibility }}% 可行</p>
          </div>
        </div>
      </section>

      <section v-if="view === 'detail'" class="grid cols-2">
        <div class="panel">
          <h3>风险详情</h3>
          <p>编号：{{ riskDetail.id }}</p>
          <p>类型：{{ riskDetail.type }}</p>
          <p>状态：{{ riskDetail.status }}</p>
          <p>预计周损失：{{ riskDetail.weeklyLoss }}</p>
          <button class="btn" @click="assignRisk">指派负责人</button>
          <button class="btn secondary" @click="view = 'report'">生成处置报告</button>
        </div>
        <div class="panel">
          <h3>SOP</h3>
          <p v-for="step in riskDetail.sop || []" :key="step">{{ step }}</p>
        </div>
      </section>

      <section v-if="view === 'report'" class="grid">
        <div class="cards">
          <button v-for="tpl in reportTemplates" :key="tpl.id" class="card-option" :class="{ active: reportForm.template === tpl.id }" @click="reportForm.template = tpl.id">
            <b>{{ tpl.name }}</b><p class="muted">{{ tpl.scenario }}</p>
          </button>
        </div>
        <div class="panel">
          <div class="toolbar">
            <select v-model="reportForm.language" class="select"><option>中文</option><option>English</option><option>日本語</option></select>
            <select v-model="reportForm.format" class="select"><option>PDF</option><option>Word</option><option>PPT</option></select>
            <button class="btn" @click="startReport">立即生成</button>
          </div>
          <p>进度：{{ reportJob?.progress || 0 }}% · {{ reportJob?.step || '等待任务' }}</p>
        </div>
      </section>

      <section v-if="view === 'alerts'" class="panel">
        <div class="toolbar">
          <input v-model="alertFilter.keyword" class="input" placeholder="标题/来源" @input="loadAlerts" />
          <select v-model="alertFilter.level" class="select" @change="loadAlerts"><option value="">所有等级</option><option>高危</option><option>中危</option><option>低危</option></select>
          <button class="btn" @click="batchProcess">批量处理</button>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>时间</th><th>等级</th><th>标题</th><th>来源</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="alert in alerts" :key="alert.id">
                <td>{{ formatTime(alert.time) }}</td>
                <td><span class="badge" :class="badgeClass(alert.level)">{{ alert.level }}</span></td>
                <td>{{ alert.title }}</td><td>{{ alert.source }}</td><td>{{ alert.status }}</td>
                <td><button class="btn secondary" @click="openRisk(alert.id)">详情</button><button class="btn danger" @click="ignoreAlert(alert.id)">忽略</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="view === 'gis'" class="grid cols-2">
        <div class="panel">
          <h3>图层管理</h3>
          <label v-for="layer in layers" :key="layer"><input v-model="activeLayers" :value="layer" type="checkbox" @change="loadGis" /> {{ layer }}</label>
        </div>
        <div class="panel">
          <h3>风险点位</h3>
          <p v-for="point in gis.points || []" :key="point.name">{{ point.name }} · 风险指数 {{ point.riskIndex }} · {{ point.analysis }}</p>
        </div>
      </section>

      <section v-if="view === 'enterprise'" class="grid">
        <div class="toolbar">
          <input v-model="enterpriseKeyword" class="input" placeholder="企业名称或统一社会信用代码" />
          <button class="btn" @click="loadEnterprise">搜索画像</button>
        </div>
        <div class="grid cols-3">
          <div class="panel"><h3>{{ enterprise.name }}</h3><p>{{ enterprise.creditCode }}</p><p>信用：{{ enterprise.creditLevel }}</p></div>
          <div class="panel"><h3>风险评分</h3><strong class="warning">{{ enterprise.riskScore }}</strong></div>
          <div class="panel"><h3>拓扑</h3><p>{{ (enterprise.topology || []).join(' -> ') }}</p></div>
        </div>
      </section>

      <section v-if="view === 'knowledge'" class="grid">
        <div class="toolbar">
          <input v-model="knowledgeQuery" class="input" placeholder="Ctrl+K 搜索知识库" @keydown.enter="searchKnowledge" />
          <button class="btn" @click="searchKnowledge">检索</button>
        </div>
        <div class="cards">
          <article v-for="item in knowledge.results || []" :key="item.id" class="card-option">
            <b>{{ item.title }}</b><p>{{ item.summary }}</p><p class="success">Similarity: {{ item.similarity }}%</p>
          </article>
        </div>
      </section>

      <section v-if="view === 'system'" class="grid">
        <div class="panel">
          <h3>AI 模型 API Key 配置</h3>
          <div class="toolbar">
            <input v-model="aiConfig.model" class="input" placeholder="模型名称，如 GPT-4o" />
            <input v-model="aiConfig.endpoint" class="input" placeholder="Endpoint" />
            <input v-model="aiConfig.apiKey" class="input" placeholder="API Key" type="password" />
            <button class="btn" @click="saveAiConfig">保存配置</button>
          </div>
        </div>
        <div class="grid cols-2">
          <div class="panel"><h3>用户</h3><p v-for="u in system.users || []" :key="u.id">{{ u.username }} · {{ u.role }} · {{ u.status }}</p></div>
          <div class="panel"><h3>系统日志</h3><p v-for="log in system.logs || []" :key="log" class="muted">{{ log }}</p></div>
        </div>
      </section>
    </section>
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { request } from './api';

const navItems = [
  { key: 'dashboard', label: '首页风险总览' },
  { key: 'upload', label: '数据上传' },
  { key: 'analysis', label: '风险分析' },
  { key: 'detail', label: '风险详情' },
  { key: 'report', label: '报告生成' },
  { key: 'alerts', label: '预警中心' },
  { key: 'gis', label: 'GIS地图' },
  { key: 'enterprise', label: '企业画像' },
  { key: 'knowledge', label: '知识库' },
  { key: 'system', label: '系统管理' }
];

const view = ref('dashboard');
const now = ref(new Date().toLocaleString('zh-CN', { hour12: false }));
const toast = ref('');
const session = ref(JSON.parse(localStorage.getItem('semiriskUser') || 'null'));
const loginForm = reactive({ username: 'admin', password: 'password', rememberMe: true });
const dashboard = ref({});
const uploads = ref([]);
const logs = ref([]);
const analysis = ref({});
const riskDetail = ref({});
const reportTemplates = ref([]);
const reportForm = reactive({ template: 'risk-assessment', language: '中文', format: 'PDF' });
const reportJob = ref(null);
const alerts = ref([]);
const alertFilter = reactive({ keyword: '', level: '' });
const layers = ['heatmap', 'suppliers', 'ports', 'routes'];
const activeLayers = ref(['heatmap', 'suppliers']);
const gis = ref({});
const enterpriseKeyword = ref('安芯半导体供应链有限公司');
const enterprise = ref({});
const knowledgeQuery = ref('半导体物流中断');
const knowledge = ref({});
const system = ref({});
const aiConfig = reactive({ model: 'GPT-4o', endpoint: 'https://api.openai.com/v1', apiKey: '' });

const currentTitle = computed(() => navItems.find(item => item.key === view.value)?.label || 'SemiRisk');
const allowedNavItems = computed(() => {
  const modules = session.value?.modules;
  if (!modules || !Array.isArray(modules)) return navItems;
  return navItems.filter(item => modules.includes(item.key));
});
let clock;

function notify(message) {
  toast.value = message;
  setTimeout(() => (toast.value = ''), 2600);
}

async function login() {
  const data = await request('/api/auth/login', { method: 'POST', body: JSON.stringify({ ...loginForm, captchaToken: 'vue-slider-ok' }) });
  localStorage.setItem('semiriskUser', JSON.stringify(data.user));
  session.value = data.user;
  if (!allowedNavItems.value.some(item => item.key === view.value)) {
    view.value = allowedNavItems.value[0]?.key || 'dashboard';
  }
  notify('登录成功');
  await loadDashboard();
}

async function resetPassword() {
  const data = await request('/api/auth/password-reset/request', { method: 'POST', body: JSON.stringify({ email: 'admin@risk.com' }) });
  notify(`重置 Token 已生成：${data.token.slice(0, 8)}...`);
}

async function logout() {
  await request('/api/auth/logout', { method: 'POST' });
  localStorage.removeItem('semiriskUser');
  session.value = null;
  view.value = 'dashboard';
}

async function loadDashboard() { dashboard.value = await request('/api/dashboard/overview'); }
async function recalculateRisk() { await request('/api/risk-score/recalculate', { method: 'POST' }); await loadDashboard(); notify('AI 风险测算已刷新'); }
function downloadTemplate() { window.open('/api/data/templates/supplier', '_blank'); }
async function loadUploads() { uploads.value = await request('/api/data/uploads'); }
async function uploadFile(event) {
  const file = event.target.files[0];
  if (!file) return;
  const form = new FormData();
  form.append('file', file);
  await request('/api/data/uploads', { method: 'POST', body: form });
  notify('文件已进入清洗队列');
  await loadUploads();
  streamLogs();
}
async function parseUploads() {
  await Promise.all(uploads.value.map(task => request(`/api/data/uploads/${task.id}/parse`, { method: 'POST' })));
  notify('AI 校验完成');
  await loadUploads();
}
function streamLogs() {
  logs.value = [];
  const source = new EventSource('/api/data/uploads/logs');
  source.addEventListener('log', event => logs.value.push(JSON.parse(event.data).message));
  source.onerror = () => source.close();
}
async function loadRiskAnalysis(windowName = '24h') { analysis.value = await request(`/api/risk/analysis?window=${windowName}`); }
async function loadRiskDetail(id = 'RA-20260603-001') { riskDetail.value = await request(`/api/risk/events/${id}`); }
async function assignRisk() { await request(`/api/risk/events/${riskDetail.value.id}/assign`, { method: 'POST', body: JSON.stringify({ owner: 'Vue 指派专员' }) }); notify('负责人已指派'); await loadRiskDetail(riskDetail.value.id); }
async function loadReportTemplates() { reportTemplates.value = await request('/api/reports/templates'); }
async function startReport() {
  reportJob.value = await request('/api/reports/jobs', { method: 'POST', body: JSON.stringify({ ...reportForm, threshold: 70 }) });
  pollReport(reportJob.value.id);
}
async function pollReport(id) {
  reportJob.value = await request(`/api/reports/jobs/${id}`);
  if (reportJob.value.progress < 100) setTimeout(() => pollReport(id), 900);
  else notify('报告已生成，可通过后端下载接口获取');
}
async function loadAlerts() {
  const params = new URLSearchParams();
  if (alertFilter.keyword) params.set('keyword', alertFilter.keyword);
  if (alertFilter.level) params.set('level', alertFilter.level);
  alerts.value = await request(`/api/alerts?${params}`);
}
async function ignoreAlert(id) { await request(`/api/alerts/${id}/ignore`, { method: 'PUT' }); await loadAlerts(); notify('告警已忽略'); }
async function batchProcess() { await request('/api/alerts/batch-process', { method: 'POST', body: JSON.stringify({ ids: alerts.value.map(a => a.id) }) }); await loadAlerts(); notify('批量处理完成'); }
function openRisk(id) { view.value = 'detail'; loadRiskDetail(id); }
async function loadGis() { gis.value = await request(`/api/gis/map?layers=${activeLayers.value.join(',')}`); }
async function loadEnterprise() { enterprise.value = await request(`/api/enterprises/profile?keyword=${encodeURIComponent(enterpriseKeyword.value)}`); }
async function searchKnowledge() { knowledge.value = await request(`/api/knowledge/search?query=${encodeURIComponent(knowledgeQuery.value)}`); }
async function loadSystem() { system.value = await request('/api/system/overview'); }
async function saveAiConfig() {
  await request('/api/system/models/config', { method: 'POST', body: JSON.stringify(aiConfig) });
  await loadSystem();
  notify('AI API Key 已保存并脱敏展示');
}
function badgeClass(level) { return level === '高危' ? 'high' : level === '中危' ? 'mid' : 'low'; }
function formatTime(time) { return new Date(time).toLocaleString('zh-CN', { hour12: false }); }

watch(view, async key => {
  if (key === 'dashboard') await loadDashboard();
  if (key === 'upload') await loadUploads();
  if (key === 'analysis') await loadRiskAnalysis();
  if (key === 'detail') await loadRiskDetail();
  if (key === 'report') await loadReportTemplates();
  if (key === 'alerts') await loadAlerts();
  if (key === 'gis') await loadGis();
  if (key === 'enterprise') await loadEnterprise();
  if (key === 'knowledge') await searchKnowledge();
  if (key === 'system') await loadSystem();
});

onMounted(async () => {
  clock = setInterval(() => (now.value = new Date().toLocaleString('zh-CN', { hour12: false })), 1000);
  document.addEventListener('keydown', event => {
    if (event.ctrlKey && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      view.value = 'knowledge';
    }
  });
  if (session.value) {
    try {
      const me = await request('/api/auth/me');
      session.value = { ...session.value, ...me };
      localStorage.setItem('semiriskUser', JSON.stringify(session.value));
    } catch {
      localStorage.removeItem('semiriskUser');
      session.value = null;
      return;
    }
    if (!allowedNavItems.value.some(item => item.key === view.value)) {
      view.value = allowedNavItems.value[0]?.key || 'dashboard';
    }
    await loadDashboard();
  }
});

onUnmounted(() => clearInterval(clock));
</script>
