(function () {
  const page = location.pathname.split('/').pop() || 'index.html';

  async function api(path, options = {}) {
    const response = await fetch(path, {
      headers: options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
      ...options
    });
    const contentType = response.headers.get('content-type') || '';
    const body = contentType.includes('application/json') ? await response.json() : await response.text();
    if (!response.ok || (body && body.success === false)) {
      throw new Error(body.message || body || `HTTP ${response.status}`);
    }
    return body.data === undefined ? body : body.data;
  }

  function toast(message, type = 'info') {
    if (window.showToast) showToast(message, type);
  }

  function first(selector) {
    return document.querySelector(selector);
  }

  function ensurePanel(title) {
    let panel = document.getElementById('api-live-panel');
    if (panel) return panel;
    const host = first('main .overflow-y-auto') || first('main') || document.body;
    panel = document.createElement('section');
    panel.id = 'api-live-panel';
    panel.className = 'hud-card p-4 mb-4 text-xs border-primary/40';
    panel.innerHTML = `<div class="flex items-center justify-between mb-3">
      <h3 class="font-bold text-primary">${title}</h3>
      <span class="text-slate-500 font-mono" data-live-time></span>
    </div><div data-live-content class="grid gap-3"></div>`;
    host.prepend(panel);
    return panel;
  }

  function setLive(title, html) {
    const panel = ensurePanel(title);
    panel.querySelector('[data-live-time]').textContent = new Date().toLocaleString('zh-CN', { hour12: false });
    panel.querySelector('[data-live-content]').innerHTML = html;
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, s => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[s]));
  }

  function bindAuth() {
    document.addEventListener('submit', async event => {
      const form = event.target;
      if (page === 'index.html' || page === 'login.html') {
        event.preventDefault();
        event.stopImmediatePropagation();
        const inputs = form.querySelectorAll('input');
        const username = inputs[0]?.value || 'admin';
        const password = inputs[1]?.value || 'password';
        const rememberMe = !!form.querySelector('input[type="checkbox"]')?.checked;
        try {
          const data = await api('/api/auth/login', {
            method: 'POST',
            body: JSON.stringify({ username, password, rememberMe, captchaToken: 'slider-ok' })
          });
          localStorage.setItem('semiriskToken', data.token);
          localStorage.setItem('semiriskUser', JSON.stringify(data.user));
          toast('登录成功，正在进入风险总览', 'success');
          setTimeout(() => location.href = 'dashboard.html', 350);
        } catch (error) {
          toast(error.message, 'danger');
        }
      }
      if (page === 'forgot-password.html') {
        event.preventDefault();
        event.stopImmediatePropagation();
        const email = form.querySelector('input[type="email"], input[type="text"]')?.value || 'admin@risk.com';
        try {
          const data = await api('/api/auth/password-reset/request', {
            method: 'POST',
            body: JSON.stringify({ email })
          });
          toast(`重置链接已发送，Token: ${data.token.slice(0, 8)}...`, 'success');
          setTimeout(() => location.href = 'index.html', 1200);
        } catch (error) {
          toast(error.message, 'danger');
        }
      }
    }, true);
  }

  async function initDashboard() {
    async function load() {
      const data = await api('/api/dashboard/overview');
      setLive('实时风险总览 API', `<div class="grid grid-cols-4 gap-3">${data.kpis.map(k => `
        <div class="bg-white/5 rounded p-3"><p class="text-slate-500">${escapeHtml(k.name)}</p><p class="text-lg text-white font-bold">${escapeHtml(k.value)}</p><p class="text-success">${escapeHtml(k.trend)}</p></div>`).join('')}</div>
        <p class="text-slate-300 leading-relaxed">${escapeHtml(data.aiSummary)}</p>
        <div class="text-slate-500 font-mono">refreshedAt: ${escapeHtml(data.refreshedAt)}</div>`);
    }
    await load();
    setInterval(load, 30000);
  }

  async function initUpload() {
    const uploadCard = first('.border-dashed');
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.xls,.xlsx,.csv,.pdf,.zip';
    input.hidden = true;
    document.body.appendChild(input);

    async function uploadFile(file) {
      const formData = new FormData();
      formData.append('file', file);
      const task = await api('/api/data/uploads', { method: 'POST', body: formData });
      toast(`${task.filename} 已进入 AI 清洗队列`, 'success');
      await renderTasks();
      streamLogs();
    }

    uploadCard?.addEventListener('click', () => input.click());
    uploadCard?.addEventListener('dragover', event => {
      event.preventDefault();
      uploadCard.classList.add('border-primary');
    });
    uploadCard?.addEventListener('drop', event => {
      event.preventDefault();
      uploadCard.classList.remove('border-primary');
      [...event.dataTransfer.files].forEach(file => uploadFile(file).catch(err => toast(err.message, 'danger')));
    });
    input.addEventListener('change', () => {
      [...input.files].forEach(file => uploadFile(file).catch(err => toast(err.message, 'danger')));
      input.value = '';
    });

    document.querySelectorAll('button').forEach(button => {
      if (button.textContent.includes('下载数据模板')) {
        button.onclick = () => { location.href = '/api/data/templates/supplier'; };
      }
      if (button.textContent.includes('开始校验')) {
        button.onclick = async () => {
          const tasks = await api('/api/data/uploads');
          await Promise.all(tasks.map(task => api(`/api/data/uploads/${task.id}/parse`, { method: 'POST' })));
          toast('AI 校验和导入完成', 'success');
          await renderTasks();
        };
      }
    });

    async function renderTasks() {
      const tasks = await api('/api/data/uploads');
      const html = tasks.length ? tasks.map(task => `
        <div class="flex items-center justify-between p-3 bg-white/5 border border-white/5 rounded-lg">
          <div><p class="text-sm font-medium">${escapeHtml(task.filename)}</p>
          <p class="text-[10px] text-slate-500">${Math.round(task.size / 1024)} KB · ${escapeHtml(task.status)} · ${task.rows || 0} 条</p></div>
          <button data-parse="${task.id}" class="text-primary hover:underline">解析</button>
        </div>`).join('') : '<p class="text-slate-500 text-xs">暂无上传任务，点击或拖拽文件开始。</p>';
      setLive('上传任务队列 API', html);
      document.querySelectorAll('[data-parse]').forEach(btn => btn.onclick = async () => {
        await api(`/api/data/uploads/${btn.dataset.parse}/parse`, { method: 'POST' });
        toast('解析完成', 'success');
        renderTasks();
      });
    }

    function streamLogs() {
      const consoleBox = first('.font-mono.text-\\[10px\\], .bg-black\\/40');
      if (!window.EventSource || !consoleBox) return;
      const source = new EventSource('/api/data/uploads/logs');
      source.addEventListener('log', event => {
        const row = JSON.parse(event.data);
        const p = document.createElement('p');
        p.className = row.message.includes('WARN') ? 'text-warning' : 'text-slate-400';
        p.textContent = `[${new Date(row.time).toLocaleTimeString('zh-CN', { hour12: false })}] ${row.message}`;
        consoleBox.appendChild(p);
        consoleBox.scrollTop = consoleBox.scrollHeight;
      });
      source.onerror = () => source.close();
    }

    await renderTasks();
  }

  async function initAlerts() {
    const tbody = first('tbody');
    const search = first('input[placeholder*="搜索告警"]');
    const selects = document.querySelectorAll('select');

    async function load() {
      const params = new URLSearchParams();
      if (search?.value) params.set('keyword', search.value);
      if (selects[0]?.value && selects[0].value !== '所有等级') params.set('level', selects[0].value);
      if (selects[1]?.value && selects[1].value !== '所有状态') params.set('status', selects[1].value);
      const alerts = await api(`/api/alerts?${params}`);
      tbody.innerHTML = alerts.map(alert => {
        const color = alert.level === '高危' ? 'danger' : alert.level === '中危' ? 'warning' : 'primary';
        return `<tr data-alert="${alert.id}" class="hover:bg-white/5 transition-colors border-l-4 border-l-${color}">
          <td class="px-6 py-4 font-mono text-slate-400">${new Date(alert.time).toLocaleTimeString('zh-CN', { hour12: false })}</td>
          <td class="px-6 py-4"><span class="px-2 py-0.5 rounded bg-${color}/20 text-${color} font-bold text-[9px]">${alert.level}</span></td>
          <td class="px-6 py-4 font-bold">${escapeHtml(alert.title)}</td>
          <td class="px-6 py-4">${escapeHtml(alert.source)}</td>
          <td class="px-6 py-4">${escapeHtml(alert.status)}</td>
          <td class="px-6 py-4 text-right space-x-2"><button data-detail="${alert.id}" class="text-primary hover:underline">详情</button><button data-ignore="${alert.id}" class="text-slate-400 hover:text-white">忽略</button></td>
        </tr>`;
      }).join('');
      document.querySelectorAll('[data-ignore]').forEach(btn => btn.onclick = async () => {
        await api(`/api/alerts/${btn.dataset.ignore}/ignore`, { method: 'PUT' });
        const row = document.querySelector(`[data-alert="${btn.dataset.ignore}"]`);
        if (row) {
          row.style.opacity = '0';
          setTimeout(() => row.remove(), 250);
        }
        toast('告警已忽略', 'info');
        loadCounts();
      });
      document.querySelectorAll('[data-detail]').forEach(btn => btn.onclick = () => location.href = `risk-detail.html?id=${btn.dataset.detail}`);
    }

    async function loadCounts() {
      const counts = await api('/api/alerts/counts');
      const spans = document.querySelectorAll('header span.text-slate-400');
      [['高危', '高危'], ['中危', '中危'], ['低危', '低危']].forEach(([key], idx) => {
        if (spans[idx]) spans[idx].textContent = `${key} (${counts[key] || 0})`;
      });
    }

    search?.addEventListener('input', load);
    selects.forEach(select => select.addEventListener('change', load));
    document.querySelectorAll('button').forEach(button => {
      if (button.textContent.includes('批量处理')) {
        button.onclick = async () => {
          const ids = [...document.querySelectorAll('[data-alert]')].map(row => row.dataset.alert);
          await api('/api/alerts/batch-process', { method: 'POST', body: JSON.stringify({ ids }) });
          toast('批量处理指令下发成功', 'success');
          load();
        };
      }
    });
    await load();
    await loadCounts();
    setInterval(load, 30000);
  }

  function initReport() {
    let selectedTemplate = 'risk-assessment';
    let selectedFormat = 'PDF';
    document.querySelectorAll('.grid.grid-cols-3 .hud-card').forEach((card, index) => {
      card.onclick = () => {
        selectedTemplate = ['risk-assessment', 'supply-chain', 'enterprise-dd'][index] || selectedTemplate;
        document.querySelectorAll('.grid.grid-cols-3 .hud-card').forEach(c => c.classList.remove('border-primary/50', 'bg-primary/10'));
        card.classList.add('border-primary/50', 'bg-primary/10');
        toast(`已选择模板：${card.querySelector('h4')?.textContent}`, 'info');
      };
    });
    document.querySelectorAll('button').forEach(button => {
      if (['PDF', 'Word', 'PPT'].includes(button.textContent.trim())) {
        button.onclick = () => {
          selectedFormat = button.textContent.trim();
          toast(`导出格式切换为 ${selectedFormat}`, 'info');
        };
      }
    });
    window.startGeneration = async function () {
      const progress = document.getElementById('gen-progress');
      progress.classList.remove('hidden');
      progress.classList.add('flex');
      progress.scrollIntoView({ behavior: 'smooth' });
      const job = await api('/api/reports/jobs', {
        method: 'POST',
        body: JSON.stringify({ template: selectedTemplate, language: '中文', format: selectedFormat, threshold: 70 })
      });
      pollReport(job.id);
    };

    async function pollReport(id) {
      const job = await api(`/api/reports/jobs/${id}`);
      document.getElementById('progress-bar').style.width = job.progress + '%';
      document.getElementById('progress-text').innerText = `${job.step} [${job.progress}%]`;
      if (job.progress >= 100) {
        toast('报告生成成功，开始下载', 'success');
        location.href = job.downloadUrl;
      } else {
        setTimeout(() => pollReport(id), 900);
      }
    }
  }

  async function initRiskAnalysis() {
    async function load(windowName) {
      const data = await api(`/api/risk/analysis?window=${encodeURIComponent(windowName)}`);
      setLive('AI 深度分析 API', `<p class="text-slate-300">${escapeHtml(data.summary)}</p>
        <div class="grid grid-cols-3 gap-3">${data.solutions.map(s => `<div class="bg-white/5 p-3 rounded"><b>${escapeHtml(s.name)}</b><p class="text-primary">${s.feasibility}% 可行</p></div>`).join('')}</div>`);
    }
    document.querySelectorAll('button').forEach(button => {
      if (button.textContent.includes('近24')) button.onclick = () => load('24h');
      if (button.textContent.includes('近7')) button.onclick = () => load('7d');
      if (button.textContent.includes('近30')) button.onclick = () => load('30d');
    });
    await load('24h');
  }

  function initRiskDetail() {
    const id = new URLSearchParams(location.search).get('id') || 'RA-20260603-001';
    api(`/api/risk/events/${id}`).then(data => setLive('风险详情 API', `<div class="grid grid-cols-2 gap-3">
      <p>风险编号：<b>${escapeHtml(data.id)}</b></p><p>状态：<b>${escapeHtml(data.status)}</b></p>
      <p>波及范围：${escapeHtml(data.scope)}</p><p>预计周损失：${data.weeklyLoss}</p></div>
      <p class="text-slate-400">SOP：${data.sop.map(escapeHtml).join(' -> ')}</p>`));
    document.querySelectorAll('button').forEach(button => {
      if (button.textContent.includes('指派负责人')) {
        button.onclick = async () => {
          await api(`/api/risk/events/${id}/assign`, { method: 'POST', body: JSON.stringify({ owner: '当班处置专员' }) });
          toast('已下发负责人指派工单，状态更新为处理中', 'success');
        };
      }
      if (button.textContent.includes('生成处置报告')) {
        button.onclick = async () => {
          await api(`/api/risk/events/${id}/dispatch-report`, { method: 'POST' });
          location.href = 'report-generation.html';
        };
      }
    });
  }

  async function initEnterprise() {
    const input = first('input[placeholder*="企业"]');
    const button = [...document.querySelectorAll('button')].find(btn => btn.textContent.includes('搜索画像'));
    async function load() {
      const data = await api(`/api/enterprises/profile?keyword=${encodeURIComponent(input?.value || '')}`);
      setLive('企业画像 API', `<div class="grid grid-cols-4 gap-3">
        <p>企业：<b>${escapeHtml(data.name)}</b></p><p>风险分：<b class="text-warning">${data.riskScore}</b></p>
        <p>信用：<b class="text-success">${escapeHtml(data.creditLevel)}</b></p><p>行业：${escapeHtml(data.industry)}</p></div>
        <p class="text-slate-400">拓扑：${data.topology.map(escapeHtml).join(' -> ')}</p>`);
    }
    button && (button.onclick = load);
    input?.addEventListener('keydown', e => { if (e.key === 'Enter') load(); });
    await load();
  }

  async function initKnowledge() {
    const input = first('input[placeholder*="关键词"]');
    const button = [...document.querySelectorAll('button')].find(btn => btn.textContent.includes('检索'));
    async function search() {
      const data = await api(`/api/knowledge/search?query=${encodeURIComponent(input?.value || '')}`);
      setLive('RAG 检索 API', data.results.map(r => `<article class="bg-white/5 p-3 rounded">
        <div class="flex justify-between"><b>${escapeHtml(r.title)}</b><span class="text-success">Similarity: ${r.similarity}%</span></div>
        <p class="text-slate-400 mt-2">${escapeHtml(r.summary)}</p>
        <button data-preview="${r.id}" class="text-primary mt-2">预览文档</button></article>`).join(''));
      document.querySelectorAll('[data-preview]').forEach(btn => btn.onclick = async () => {
        const text = await api(`/api/knowledge/preview/${btn.dataset.preview}`);
        alert(text);
      });
    }
    button && (button.onclick = search);
    input?.addEventListener('keydown', e => { if (e.ctrlKey && e.key.toLowerCase() === 'k') { e.preventDefault(); input.focus(); } if (e.key === 'Enter') search(); });
    document.addEventListener('keydown', e => { if (e.ctrlKey && e.key.toLowerCase() === 'k') { e.preventDefault(); input?.focus(); } });
    document.querySelectorAll('.rounded.text-\\[9px\\]').forEach(tag => tag.onclick = () => { input.value = tag.textContent.replace('#', ''); search(); });
    await search();
  }

  async function initGis() {
    async function load() {
      const layers = [...document.querySelectorAll('input[type="checkbox"]:checked')].map((_, idx) => ['heatmap', 'suppliers', 'ports', 'routes'][idx]).join(',');
      const data = await api(`/api/gis/map?layers=${encodeURIComponent(layers)}`);
      setLive('GIS 图层 API', `<p>当前图层：${escapeHtml(data.layers)}</p>
        <div class="grid grid-cols-2 gap-3">${data.points.map(p => `<button data-risk-point="${p.name}" class="bg-white/5 rounded p-3 text-left"><b>${escapeHtml(p.name)}</b><p>风险指数 ${p.riskIndex}</p><p class="text-slate-500">${escapeHtml(p.analysis)}</p></button>`).join('')}</div>`);
      document.querySelectorAll('[data-risk-point]').forEach(btn => btn.onclick = () => toast(`${btn.dataset.riskPoint} 画像已加载`, 'info'));
    }
    document.querySelectorAll('input[type="checkbox"]').forEach(box => box.addEventListener('change', load));
    document.querySelectorAll('button').forEach(btn => { if (btn.textContent.includes('搜索')) btn.onclick = load; });
    await load();
  }

  async function initSystem() {
    const overview = await api('/api/system/overview');
    setLive('系统管理 API', `<div class="grid grid-cols-3 gap-3">
      <p>用户数：<b>${overview.users.length}</b></p><p>模型数：<b>${overview.models.length}</b></p><p>Agent：<b>${overview.agents.length}</b></p></div>
      <p class="text-slate-400">${overview.logs.slice(-2).map(escapeHtml).join('<br>')}</p>`);
    const originalSubmit = window.submitAddUser;
    window.submitAddUser = async function () {
      const username = document.getElementById('modal-username').value;
      const realname = document.getElementById('modal-realname').value || username;
      const role = document.getElementById('modal-role').value;
      await api('/api/system/users', { method: 'POST', body: JSON.stringify({ username, email: `${username}@risk.com`, role }) });
      originalSubmit ? originalSubmit() : toast(`系统用户 ${realname} 已创建`, 'success');
    };
    const originalToggle = window.toggleUserStatus;
    window.toggleUserStatus = async function (id) {
      await api(`/api/system/users/${id.startsWith('U') ? id : 'U1002'}/status`, { method: 'PUT', body: JSON.stringify({ status: '禁用' }) });
      originalToggle ? originalToggle(id) : toast('用户状态已更新，Session 已踢下线', 'success');
    };
    document.querySelectorAll('button').forEach(btn => {
      if (btn.textContent.includes('连通性测试')) {
        btn.addEventListener('click', async () => {
          const card = btn.closest('.hud-card');
          const model = card?.querySelector('p.text-sm')?.textContent?.trim() || 'deepseekv4-pro';
          const endpoint = card?.querySelector('input[type="text"]')?.value || 'https://api.deepseek.com/v1';
          const apiKey = card?.querySelector('input[type="password"]')?.value || 'sk-placeholder';
          await api('/api/system/models/config', { method: 'POST', body: JSON.stringify({ model, endpoint, apiKey }) });
          const data = await api('/api/system/models/ping', { method: 'POST', body: JSON.stringify({ model }) });
          toast(`${data.model} 已保存并测试成功，延时 ${data.latencyMs}ms`, 'success');
        });
      }
      if (btn.textContent.includes('手动单步触发')) {
        btn.addEventListener('click', async () => {
          await api('/api/system/agents/monitor/trigger', { method: 'POST' });
          toast('Agent 已手动触发', 'success');
        });
      }
      if (btn.textContent.includes('修复网络重联')) {
        btn.addEventListener('click', async () => {
          await api('/api/system/datasources/gis/reconnect', { method: 'POST' });
          toast('数据源重连成功', 'success');
        });
      }
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    bindAuth();
    const initializers = {
      'dashboard.html': initDashboard,
      'data-upload.html': initUpload,
      'alert-center.html': initAlerts,
      'report-generation.html': initReport,
      'risk-analysis.html': initRiskAnalysis,
      'risk-detail.html': initRiskDetail,
      'enterprise-profile.html': initEnterprise,
      'knowledge-base.html': initKnowledge,
      'gis-map.html': initGis,
      'system-management.html': initSystem
    };
    const init = initializers[page];
    if (init) init().catch(error => toast(error.message, 'danger'));
  });
})();
