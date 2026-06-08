const API_BASE = "/prod-api";

const state = {
  currentView: "dashboard",
  eventPage: 1,
  eventPageSize: 20,
  eventTotal: 0,
  trendRows: [],
  gisRows: []
};

const titles = {
  dashboard: "总览",
  events: "风险事件",
  analysis: "趋势分析",
  gis: "GIS 分布",
  enterprise: "企业画像",
  reports: "AI 报告",
  knowledge: "知识库",
  sources: "数据源"
};

const viewLoaders = {
  dashboard: loadDashboard,
  events: loadEvents,
  analysis: loadAnalysis,
  gis: loadGis,
  enterprise: loadEnterprise,
  reports: loadReports,
  knowledge: loadKnowledge,
  sources: loadSources
};

function $(selector) {
  return document.querySelector(selector);
}

function $all(selector) {
  return [...document.querySelectorAll(selector)];
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[char]);
}

function valueOrDash(value) {
  if (value === null || value === undefined || value === "") return "--";
  return value;
}

function formatNumber(value) {
  if (value === null || value === undefined || value === "") return "--";
  const num = Number(value);
  return Number.isFinite(num) ? num.toLocaleString("zh-CN") : escapeHtml(value);
}

function statusText(status) {
  return {
    UNRESOLVED: "未处理",
    RESOLVING: "处理中",
    RESOLVED: "已闭环",
    FINISHED: "完成",
    FAILED: "失败",
    PENDING: "等待"
  }[status] || valueOrDash(status);
}

function levelBadge(level) {
  const normalized = level || "INFO";
  const cls = normalized === "CRITICAL" ? "danger" : normalized === "WARNING" ? "warning" : "info";
  return `<span class="badge ${cls}">${escapeHtml(normalized || "未分级")}</span>`;
}

function statusBadge(status) {
  const cls = status === "RESOLVED" || status === "FINISHED" ? "success" : status === "FAILED" ? "danger" : "info";
  return `<span class="badge ${cls}">${escapeHtml(statusText(status))}</span>`;
}

function setStatus(message, type = "info") {
  const el = $("#status");
  if (!message) {
    el.className = "status hidden";
    el.textContent = "";
    return;
  }
  el.className = `status ${type === "error" ? "error" : ""}`;
  el.textContent = message;
}

function touchUpdated() {
  $("#last-updated").textContent = `已刷新 ${new Date().toLocaleString("zh-CN", { hour12: false })}`;
}

async function api(path, options = {}) {
  const headers = options.body instanceof FormData ? {} : { "Content-Type": "application/json" };
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) }
  });
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) {
    throw new Error(typeof body === "string" ? body : body.msg || `HTTP ${response.status}`);
  }
  if (body && typeof body === "object" && body.code && body.code !== 200) {
    throw new Error(body.msg || `接口返回 ${body.code}`);
  }
  return body;
}

function dataOf(result, fallback = null) {
  if (!result || typeof result !== "object") return fallback;
  return result.data ?? fallback;
}

function rowsOf(result) {
  if (!result || typeof result !== "object") return [];
  return result.rows || result.data?.rows || result.data || [];
}

function totalOf(result, rows) {
  if (!result || typeof result !== "object") return rows.length;
  return Number(result.total ?? result.data?.total ?? rows.length);
}

function empty(message) {
  return `<div class="empty">${escapeHtml(message)}</div>`;
}

function renderEventRows(rows, target, withActions = false) {
  const tbody = $(target);
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="${withActions ? 8 : 7}" class="muted">暂无真实风险事件</td></tr>`;
    return;
  }
  tbody.innerHTML = rows.map((item) => `
    <tr>
      <td>${escapeHtml(valueOrDash(item.occurredAt || item.createTime))}</td>
      <td>${levelBadge(item.riskLevel)}</td>
      <td>${escapeHtml(valueOrDash(item.enterpriseName))}</td>
      <td>${escapeHtml(valueOrDash(item.eventTitle))}</td>
      <td>${escapeHtml(valueOrDash(item.sourceName))}</td>
      <td>${statusBadge(item.status)}</td>
      <td>${formatNumber(item.riskScore)}</td>
      ${withActions ? `<td><div class="row-actions">
        <button class="link-btn" data-resolving="${item.eventId}" type="button">处理</button>
        <button class="link-btn" data-resolved="${item.eventId}" type="button">闭环</button>
      </div></td>` : ""}
    </tr>
  `).join("");

  if (withActions) {
    $all("[data-resolving]").forEach((button) => {
      button.onclick = () => handleEvent(button.dataset.resolving, "RESOLVING");
    });
    $all("[data-resolved]").forEach((button) => {
      button.onclick = () => handleEvent(button.dataset.resolved, "RESOLVED");
    });
  }
}

async function loadDashboard() {
  const [kpisRes, eventRes, trendRes, gisRes] = await Promise.all([
    api("/risk/event/kpis"),
    api("/risk/event/list?pageNum=1&pageSize=8"),
    api("/risk/event/trend"),
    api("/risk/event/gis/nodes")
  ]);

  const kpis = dataOf(kpisRes, {});
  const events = rowsOf(eventRes);
  state.trendRows = dataOf(trendRes, []);
  state.gisRows = dataOf(gisRes, []);

  $("#metric-total").textContent = formatNumber(kpis.total);
  $("#metric-today").textContent = formatNumber(kpis.today);
  $("#metric-resolved").textContent = formatNumber(kpis.resolved);
  $("#metric-index").textContent = formatNumber(kpis.currentRiskIndex);
  renderTrend("#trend-chart", state.trendRows);
  renderTopRisks(events);
  renderEventRows(events, "#latest-events");
}

function renderTopRisks(rows) {
  const sorted = [...rows]
    .sort((a, b) => Number(b.riskScore || 0) - Number(a.riskScore || 0))
    .slice(0, 5);
  $("#top-risk-count").textContent = `${sorted.length} 条`;
  $("#top-risk-list").innerHTML = sorted.length
    ? sorted.map((item) => `
      <article class="list-item">
        <h4>${escapeHtml(valueOrDash(item.eventTitle))}</h4>
        <p>${escapeHtml(valueOrDash(item.enterpriseName))} · ${levelBadge(item.riskLevel)} · 风险分 ${formatNumber(item.riskScore)}</p>
      </article>
    `).join("")
    : empty("暂无真实风险事件");
}

async function loadEvents() {
  const params = new URLSearchParams({
    pageNum: String(state.eventPage),
    pageSize: String(state.eventPageSize)
  });
  const eventTitle = $("#event-keyword").value.trim();
  const enterpriseName = $("#event-enterprise").value.trim();
  const riskLevel = $("#event-level").value;
  const status = $("#event-status").value;
  if (eventTitle) params.set("eventTitle", eventTitle);
  if (enterpriseName) params.set("enterpriseName", enterpriseName);
  if (riskLevel) params.set("riskLevel", riskLevel);
  if (status) params.set("status", status);

  const result = await api(`/risk/event/list?${params.toString()}`);
  const rows = rowsOf(result);
  state.eventTotal = totalOf(result, rows);
  renderEventRows(rows, "#event-table", true);
  $("#event-total").textContent = `共 ${formatNumber(state.eventTotal)} 条`;
  $("#event-page").textContent = String(state.eventPage);
  $("#event-prev").disabled = state.eventPage <= 1;
  $("#event-next").disabled = state.eventPage * state.eventPageSize >= state.eventTotal;
}

async function handleEvent(id, status) {
  await api(`/risk/event/handle/${id}`, {
    method: "PUT",
    body: JSON.stringify({ status })
  });
  setStatus("事件状态已更新");
  await loadEvents();
}

async function submitEvent(event) {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const payload = Object.fromEntries(form.entries());
  ["riskScore", "longitude", "latitude"].forEach((key) => {
    if (payload[key] === "") {
      delete payload[key];
    } else if (payload[key] !== undefined) {
      payload[key] = Number(payload[key]);
    }
  });
  payload.status = "UNRESOLVED";
  payload.occurredAt = new Date().toISOString();
  await api("/risk/event", { method: "POST", body: JSON.stringify(payload) });
  event.currentTarget.reset();
  state.eventPage = 1;
  setStatus("真实风险事件已写入");
  await Promise.all([loadEvents(), loadDashboard()]);
}

async function loadAnalysis() {
  const result = await api("/risk/event/trend");
  state.trendRows = dataOf(result, []);
  renderTrend("#analysis-chart", state.trendRows, true);
}

function renderTrend(selector, rows, showBars = false) {
  const host = $(selector);
  if (!rows.length) {
    host.innerHTML = empty("暂无真实趋势数据");
    return;
  }
  const width = 900;
  const height = showBars ? 390 : 280;
  const pad = { top: 22, right: 22, bottom: 42, left: 48 };
  const values = rows.map((row) => Number(row.riskScore || 0));
  const counts = rows.map((row) => Number(row.count || 0));
  const max = Math.max(...values, ...counts, 1);
  const step = rows.length > 1 ? (width - pad.left - pad.right) / (rows.length - 1) : 0;
  const y = (value) => height - pad.bottom - (Number(value || 0) / max) * (height - pad.top - pad.bottom);
  const x = (index) => pad.left + index * step;
  const line = rows.map((row, index) => `${x(index)},${y(row.riskScore)}`).join(" ");
  const bars = showBars ? rows.map((row, index) => {
    const barWidth = Math.max(8, Math.min(28, (width - pad.left - pad.right) / rows.length - 8));
    const barHeight = height - pad.bottom - y(row.count);
    return `<rect x="${x(index) - barWidth / 2}" y="${y(row.count)}" width="${barWidth}" height="${barHeight}" rx="3" fill="#dbe7ff"></rect>`;
  }).join("") : "";
  const labels = rows.map((row, index) => {
    if (rows.length > 10 && index % Math.ceil(rows.length / 8) !== 0) return "";
    return `<text x="${x(index)}" y="${height - 16}" text-anchor="middle" font-size="11" fill="#6b778c">${escapeHtml(row.date || "")}</text>`;
  }).join("");

  host.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" role="img" aria-label="真实风险趋势">
      <line x1="${pad.left}" y1="${height - pad.bottom}" x2="${width - pad.right}" y2="${height - pad.bottom}" stroke="#d2d8e4"></line>
      <line x1="${pad.left}" y1="${pad.top}" x2="${pad.left}" y2="${height - pad.bottom}" stroke="#d2d8e4"></line>
      ${bars}
      <polyline points="${line}" fill="none" stroke="#1f6feb" stroke-width="3"></polyline>
      ${rows.map((row, index) => `<circle cx="${x(index)}" cy="${y(row.riskScore)}" r="4" fill="#1f6feb"><title>${escapeHtml(row.date || "")}: ${formatNumber(row.riskScore)}</title></circle>`).join("")}
      ${labels}
    </svg>
  `;
}

async function loadGis() {
  const result = await api("/risk/event/gis/nodes");
  state.gisRows = dataOf(result, []);
  renderMap("#gis-map", state.gisRows);
}

function renderMap(selector, rows) {
  const valid = rows.filter((row) => row.longitude !== null && row.longitude !== undefined && row.latitude !== null && row.latitude !== undefined);
  const host = $(selector);
  if (!valid.length) {
    host.innerHTML = empty("暂无带经纬度的真实风险事件");
    return;
  }
  const width = 960;
  const height = 440;
  const x = (lon) => ((Number(lon) + 180) / 360) * width;
  const y = (lat) => ((90 - Number(lat)) / 180) * height;
  host.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" role="img" aria-label="真实风险地理分布">
      <rect x="0" y="0" width="${width}" height="${height}" fill="#f8fafc"></rect>
      ${[-120, -60, 0, 60, 120].map((lon) => `<line x1="${x(lon)}" y1="0" x2="${x(lon)}" y2="${height}" stroke="#e4e8f0"></line>`).join("")}
      ${[-60, -30, 0, 30, 60].map((lat) => `<line x1="0" y1="${y(lat)}" x2="${width}" y2="${y(lat)}" stroke="#e4e8f0"></line>`).join("")}
      ${valid.map((row) => {
        const score = Number(row.riskScore || 0);
        const radius = Math.max(5, Math.min(18, score / 6));
        const color = row.riskLevel === "CRITICAL" ? "#d92d20" : row.riskLevel === "WARNING" ? "#b7791f" : "#1f6feb";
        return `<circle cx="${x(row.longitude)}" cy="${y(row.latitude)}" r="${radius}" fill="${color}" fill-opacity="0.78">
          <title>${escapeHtml(row.eventTitle || "")} / ${escapeHtml(row.enterpriseName || "")} / ${score}</title>
        </circle>`;
      }).join("")}
    </svg>
  `;
}

async function loadEnterprise() {
  const keyword = $("#enterprise-keyword").value.trim();
  const result = await api(`/risk/enterprise/profile${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ""}`);
  const profile = dataOf(result, {});
  renderEnterprise(profile);
}

function renderEnterprise(profile) {
  const enterprise = profile?.enterprise;
  const events = profile?.events || [];
  const radar = profile?.radar || {};
  const host = $("#enterprise-profile");
  if (!enterprise) {
    host.innerHTML = empty("暂无真实企业画像数据");
    return;
  }
  host.innerHTML = `
    <div class="profile-grid">
      <article class="list-item">
        <h4>${escapeHtml(valueOrDash(enterprise.enterpriseName))}</h4>
        <p>${escapeHtml(valueOrDash(enterprise.creditCode))}</p>
        <p>${escapeHtml(valueOrDash(enterprise.industry))} · ${escapeHtml(valueOrDash(enterprise.region))}</p>
      </article>
      <article class="list-item">
        <h4>风险评分</h4>
        <p>${formatNumber(enterprise.riskScore)}</p>
      </article>
      <article class="list-item">
        <h4>关联事件</h4>
        <p>${formatNumber(events.length)}</p>
      </article>
    </div>
    <div class="layout-grid" style="margin-top:12px">
      <section class="panel span-5">
        <div class="panel-header"><h3>风险维度</h3></div>
        <div class="list">${Object.keys(radar).length ? Object.entries(radar).map(([name, value]) => `
          <div class="list-item"><h4>${escapeHtml(name)}</h4><p>${formatNumber(value)}</p></div>
        `).join("") : empty("暂无真实维度数据")}</div>
      </section>
      <section class="panel span-7">
        <div class="panel-header"><h3>关联风险事件</h3></div>
        <div class="table-wrap"><table><tbody>
          ${events.length ? events.map((item) => `<tr><td>${escapeHtml(valueOrDash(item.eventTitle))}</td><td>${levelBadge(item.riskLevel)}</td><td>${formatNumber(item.riskScore)}</td><td>${statusBadge(item.status)}</td></tr>`).join("") : `<tr><td class="muted">暂无真实关联事件</td></tr>`}
        </tbody></table></div>
      </section>
    </div>
  `;
}

async function loadReports() {
  const result = await api("/risk/report/list?pageNum=1&pageSize=20");
  const rows = rowsOf(result);
  $("#report-list").innerHTML = rows.length ? rows.map((item) => `
    <article class="list-item">
      <h4>${escapeHtml(valueOrDash(item.reportTitle))}</h4>
      <p>${escapeHtml(valueOrDash(item.templateType))} · ${statusBadge(item.status)} · ${escapeHtml(valueOrDash(item.createTime))}</p>
      <button class="link-btn" data-report="${item.reportId}" type="button">查看内容</button>
    </article>
  `).join("") : empty("暂无真实报告记录");
  $all("[data-report]").forEach((button) => {
    button.onclick = () => loadReportDetail(button.dataset.report);
  });
}

async function submitReport(event) {
  event.preventDefault();
  const payload = Object.fromEntries(new FormData(event.currentTarget).entries());
  await api("/risk/event/report/generate", {
    method: "POST",
    body: JSON.stringify(payload)
  });
  setStatus("AI 报告生成任务已提交");
  await loadReports();
}

async function loadReportDetail(id) {
  const result = await api(`/risk/report/${id}`);
  const report = dataOf(result, {});
  $("#report-detail-panel").classList.remove("hidden");
  $("#report-title").textContent = report.reportTitle || "报告内容";
  $("#report-content").textContent = report.content || report.errorMessage || "";
}

async function loadKnowledge() {
  const query = $("#knowledge-keyword").value.trim();
  const result = await api(`/risk/knowledge/list?pageNum=1&pageSize=50${query ? `&query=${encodeURIComponent(query)}` : ""}`);
  const rows = rowsOf(result);
  $("#knowledge-list").innerHTML = rows.length ? rows.map((item) => `
    <article class="list-item">
      <h4>${escapeHtml(valueOrDash(item.title))}</h4>
      <p>${escapeHtml(valueOrDash(item.content))}</p>
      <p>${escapeHtml(valueOrDash(item.category))} · ${escapeHtml(valueOrDash(item.sourceName))} · ${escapeHtml(valueOrDash(item.keywords))}</p>
    </article>
  `).join("") : empty("暂无真实知识库数据");
}

async function submitKnowledge(event) {
  event.preventDefault();
  const payload = Object.fromEntries(new FormData(event.currentTarget).entries());
  await api("/risk/knowledge", { method: "POST", body: JSON.stringify(payload) });
  event.currentTarget.reset();
  setStatus("知识已写入");
  await loadKnowledge();
}

async function loadSources() {
  const result = await api("/risk/source/list?pageNum=1&pageSize=50");
  const rows = rowsOf(result);
  $("#source-table").innerHTML = rows.length ? rows.map((item) => `
    <tr>
      <td>${escapeHtml(valueOrDash(item.sourceName))}</td>
      <td>${escapeHtml(valueOrDash(item.sourceType))}</td>
      <td>${escapeHtml(valueOrDash(item.accessMode))}</td>
      <td>${escapeHtml(valueOrDash(item.endpoint))}</td>
      <td>${statusBadge(item.status)}</td>
    </tr>
  `).join("") : `<tr><td colspan="5" class="muted">暂无真实数据源配置</td></tr>`;
}

async function submitSource(event) {
  event.preventDefault();
  const payload = Object.fromEntries(new FormData(event.currentTarget).entries());
  await api("/risk/source", { method: "POST", body: JSON.stringify(payload) });
  event.currentTarget.reset();
  setStatus("数据源已写入");
  await loadSources();
}

async function switchView(view) {
  state.currentView = view;
  setStatus("");
  $all(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.view === view));
  $all(".view").forEach((panel) => panel.classList.toggle("active", panel.id === view));
  $("#view-title").textContent = titles[view] || view;
  try {
    await viewLoaders[view]();
    touchUpdated();
  } catch (error) {
    setStatus(error.message, "error");
  }
}

function bindEvents() {
  $all(".nav-item").forEach((button) => {
    button.onclick = () => switchView(button.dataset.view);
  });
  $all("[data-view-jump]").forEach((button) => {
    button.onclick = () => switchView(button.dataset.viewJump);
  });
  $("#refresh-btn").onclick = () => switchView(state.currentView);
  $("#event-search").onclick = () => {
    state.eventPage = 1;
    switchView("events");
  };
  $("#event-reset").onclick = () => {
    $("#event-keyword").value = "";
    $("#event-enterprise").value = "";
    $("#event-level").value = "";
    $("#event-status").value = "";
    state.eventPage = 1;
    switchView("events");
  };
  $("#event-prev").onclick = () => {
    if (state.eventPage > 1) {
      state.eventPage -= 1;
      switchView("events");
    }
  };
  $("#event-next").onclick = () => {
    if (state.eventPage * state.eventPageSize < state.eventTotal) {
      state.eventPage += 1;
      switchView("events");
    }
  };
  $("#event-form").onsubmit = submitEvent;
  $("#enterprise-search").onclick = () => switchView("enterprise");
  $("#enterprise-keyword").onkeydown = (event) => {
    if (event.key === "Enter") switchView("enterprise");
  };
  $("#report-form").onsubmit = submitReport;
  $("#knowledge-search").onclick = () => switchView("knowledge");
  $("#knowledge-keyword").onkeydown = (event) => {
    if (event.key === "Enter") switchView("knowledge");
  };
  $("#knowledge-form").onsubmit = submitKnowledge;
  $("#source-form").onsubmit = submitSource;
}

bindEvents();
switchView("dashboard");
