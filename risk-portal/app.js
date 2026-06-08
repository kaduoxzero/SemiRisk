const API_BASE = "/prod-api";

const state = {
  currentView: "dashboard",
  eventPage: 1,
  eventPageSize: 20,
  eventTotal: 0,
  eventRows: [],
  trendRows: [],
  gisRows: [],
  switching: false
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

function arrayOf(value) {
  if (Array.isArray(value)) return value;
  if (value && Array.isArray(value.rows)) return value.rows;
  return [];
}

function formatNumber(value) {
  if (value === null || value === undefined || value === "") return "--";
  const num = Number(value);
  return Number.isFinite(num) ? num.toLocaleString("zh-CN") : escapeHtml(value);
}

function formatDate(value) {
  if (!value) return "--";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? valueOrDash(value) : date.toLocaleString("zh-CN", { hour12: false });
}

function statusText(status) {
  return {
    UNRESOLVED: "未处理",
    RESOLVING: "处理中",
    RESOLVED: "已闭环",
    FINISHED: "完成",
    GENERATING: "生成中",
    FAILED: "失败",
    PENDING: "等待",
    ACTIVE: "启用",
    INACTIVE: "停用"
  }[status] || valueOrDash(status);
}

function levelBadge(level) {
  if (!level) return `<span class="badge info">未分级</span>`;
  const normalized = level;
  const cls = normalized === "CRITICAL" ? "danger" : normalized === "WARNING" ? "warning" : "info";
  return `<span class="badge ${cls}">${escapeHtml(normalized)}</span>`;
}

function statusBadge(status) {
  const cls = status === "RESOLVED" || status === "FINISHED" || status === "ACTIVE" ? "success" : status === "FAILED" || status === "INACTIVE" ? "danger" : "info";
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

function setButtonBusy(button, busy, busyText = "处理中") {
  if (!button) return;
  if (!button.dataset.label) button.dataset.label = button.textContent;
  button.disabled = busy;
  button.textContent = busy ? busyText : button.dataset.label;
}

async function withBusy(button, busyText, task) {
  if (button?.disabled) return undefined;
  setButtonBusy(button, true, busyText);
  try {
    return await task();
  } finally {
    setButtonBusy(button, false);
  }
}

async function runTask(task) {
  setStatus("");
  try {
    await task();
  } catch (error) {
    setStatus(error.message || "操作失败", "error");
  }
}

function bindSubmit(handler) {
  return (event) => {
    event.preventDefault();
    runTask(() => handler(event));
  };
}

async function api(path, options = {}) {
  const headers = options.body && !(options.body instanceof FormData) ? { "Content-Type": "application/json" } : {};
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) }
  });
  const contentType = response.headers.get("content-type") || "";
  let body;
  try {
    body = contentType.includes("application/json") ? await response.json() : await response.text();
  } catch (error) {
    body = "";
  }
  if (!response.ok) {
    throw new Error(typeof body === "string" ? body : body.msg || `HTTP ${response.status}`);
  }
  if (body && typeof body === "object" && body.code && ![0, 200].includes(Number(body.code))) {
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
  return arrayOf(result.rows ?? result.data?.rows ?? result.data);
}

function totalOf(result, rows) {
  if (!result || typeof result !== "object") return rows.length;
  return Number(result.total ?? result.data?.total ?? rows.length);
}

function empty(message) {
  return `<div class="empty">${escapeHtml(message)}</div>`;
}

function renderMetric(selector, value) {
  $(selector).textContent = formatNumber(value);
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
        <button class="link-btn" data-detail="${item.eventId}" type="button">详情</button>
        <button class="link-btn" data-resolving="${item.eventId}" type="button">处理</button>
        <button class="link-btn" data-resolved="${item.eventId}" type="button">闭环</button>
      </div></td>` : ""}
    </tr>
  `).join("");

  if (withActions) {
    $all("[data-detail]").forEach((button) => {
      button.onclick = () => runTask(() => loadEventDetail(button.dataset.detail, button));
    });
    $all("[data-resolving]").forEach((button) => {
      button.onclick = () => runTask(() => handleEvent(button.dataset.resolving, "RESOLVING", button));
    });
    $all("[data-resolved]").forEach((button) => {
      button.onclick = () => runTask(() => handleEvent(button.dataset.resolved, "RESOLVED", button));
    });
  }
}

async function loadDashboard() {
  const [kpisRes, eventRes, trendRes, gisRes] = await Promise.allSettled([
    api("/risk/event/kpis"),
    api("/risk/event/list?pageNum=1&pageSize=8"),
    api("/risk/event/trend"),
    api("/risk/event/gis/nodes")
  ]);
  const warnings = [];
  const valueOf = (settled, label) => {
    if (settled.status === "fulfilled") return settled.value;
    warnings.push(label);
    return null;
  };

  const kpis = dataOf(valueOf(kpisRes, "KPI"), {});
  const events = rowsOf(valueOf(eventRes, "最新事件"));
  state.trendRows = arrayOf(dataOf(valueOf(trendRes, "趋势"), []));
  state.gisRows = arrayOf(dataOf(valueOf(gisRes, "GIS"), []));

  renderMetric("#metric-total", kpis.total);
  renderMetric("#metric-today", kpis.today);
  renderMetric("#metric-resolved", kpis.resolved);
  renderMetric("#metric-index", kpis.currentRiskIndex);
  renderTrend("#trend-chart", state.trendRows);
  renderTopRisks(events);
  renderEventRows(events, "#latest-events");
  if (warnings.length) {
    setStatus(`部分总览数据加载失败：${warnings.join("、")}`, "error");
  }
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
  state.eventRows = rows;
  state.eventTotal = totalOf(result, rows);
  renderEventRows(rows, "#event-table", true);
  $("#event-total").textContent = `共 ${formatNumber(state.eventTotal)} 条`;
  $("#event-page").textContent = String(state.eventPage);
  $("#event-prev").disabled = state.eventPage <= 1;
  $("#event-next").disabled = state.eventPage * state.eventPageSize >= state.eventTotal;
}

async function loadEventDetail(id, button) {
  if (!id) return;
  await withBusy(button, "读取中", async () => {
    const result = await api(`/risk/event/${encodeURIComponent(id)}`);
    renderEventDetail(dataOf(result, {}));
  });
}

function detailItem(label, value, wide = false) {
  return `
    <div class="detail-item ${wide ? "wide-detail" : ""}">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(valueOrDash(value))}</strong>
    </div>
  `;
}

function renderEventDetail(item) {
  $("#event-detail-panel").classList.remove("hidden");
  $("#event-detail-title").textContent = item.eventTitle || "事件详情";
  $("#event-detail-body").innerHTML = [
    detailItem("企业", item.enterpriseName),
    detailItem("分类", item.category),
    detailItem("等级", item.riskLevel),
    detailItem("状态", statusText(item.status)),
    detailItem("风险分", formatNumber(item.riskScore)),
    detailItem("来源", item.sourceName),
    detailItem("发生时间", formatDate(item.occurredAt)),
    detailItem("创建时间", formatDate(item.createTime)),
    detailItem("经纬度", item.longitude || item.latitude ? `${valueOrDash(item.longitude)}, ${valueOrDash(item.latitude)}` : "--"),
    detailItem("事件编码", item.eventCode),
    detailItem("事件描述", item.description, true),
    detailItem("处置建议", item.disposalSuggestion, true)
  ].join("");
}

function closeEventDetail() {
  $("#event-detail-panel").classList.add("hidden");
  $("#event-detail-title").textContent = "事件详情";
  $("#event-detail-body").innerHTML = "";
}

async function handleEvent(id, status, button) {
  if (!id) return;
  await withBusy(button, "更新中", async () => {
    await api(`/risk/event/handle/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify({ status })
    });
    setStatus("事件状态已更新");
    await loadEvents();
  });
}

async function submitEvent(event) {
  const button = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await withBusy(button, "写入中", async () => {
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
    await loadEvents();
  });
}

async function loadAnalysis() {
  const result = await api("/risk/event/trend");
  state.trendRows = arrayOf(dataOf(result, []));
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
  state.gisRows = arrayOf(dataOf(result, []));
  renderMap("#gis-map", state.gisRows);
}

function renderMap(selector, rows) {
  const valid = rows
    .map((row) => ({ ...row, longitude: Number(row.longitude), latitude: Number(row.latitude) }))
    .filter((row) => Number.isFinite(row.longitude) && Number.isFinite(row.latitude));
  const host = $(selector);
  if (!valid.length) {
    host.innerHTML = empty("暂无带经纬度的真实风险事件");
    return;
  }
  const width = 960;
  const height = 440;
  const pad = 38;
  const lonValues = valid.map((row) => row.longitude);
  const latValues = valid.map((row) => row.latitude);
  let minLon = Math.min(...lonValues);
  let maxLon = Math.max(...lonValues);
  let minLat = Math.min(...latValues);
  let maxLat = Math.max(...latValues);
  if (minLon === maxLon) {
    minLon -= 0.5;
    maxLon += 0.5;
  }
  if (minLat === maxLat) {
    minLat -= 0.5;
    maxLat += 0.5;
  }
  const lonPad = (maxLon - minLon) * 0.12;
  const latPad = (maxLat - minLat) * 0.12;
  minLon -= lonPad;
  maxLon += lonPad;
  minLat -= latPad;
  maxLat += latPad;
  const x = (lon) => pad + ((lon - minLon) / (maxLon - minLon)) * (width - pad * 2);
  const y = (lat) => height - pad - ((lat - minLat) / (maxLat - minLat)) * (height - pad * 2);
  const gridLines = [0, 0.25, 0.5, 0.75, 1];
  host.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" role="img" aria-label="真实风险地理分布">
      <rect x="0" y="0" width="${width}" height="${height}" fill="#f8fafc"></rect>
      ${gridLines.map((ratio) => {
        const px = pad + ratio * (width - pad * 2);
        return `<line x1="${px}" y1="${pad}" x2="${px}" y2="${height - pad}" stroke="#e4e8f0"></line>`;
      }).join("")}
      ${gridLines.map((ratio) => {
        const py = pad + ratio * (height - pad * 2);
        return `<line x1="${pad}" y1="${py}" x2="${width - pad}" y2="${py}" stroke="#e4e8f0"></line>`;
      }).join("")}
      <rect x="${pad}" y="${pad}" width="${width - pad * 2}" height="${height - pad * 2}" fill="none" stroke="#d2d8e4"></rect>
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
    button.onclick = () => runTask(() => loadReportDetail(button.dataset.report));
  });
}

async function submitReport(event) {
  const button = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await withBusy(button, "生成中", async () => {
    const payload = Object.fromEntries(new FormData(event.currentTarget).entries());
    const result = await api("/risk/event/report/generate", {
      method: "POST",
      body: JSON.stringify(payload)
    });
    const report = dataOf(result, {});
    setStatus("AI 报告已生成");
    await loadReports();
    if (report.reportId) await loadReportDetail(report.reportId);
  });
}

async function loadReportDetail(id) {
  const result = await api(`/risk/report/${id}`);
  const report = dataOf(result, {});
  $("#report-detail-panel").classList.remove("hidden");
  $("#report-title").textContent = report.reportTitle || "报告内容";
  $("#report-content").textContent = report.content || report.errorMessage || "暂无报告内容";
}

async function loadKnowledge() {
  const query = $("#knowledge-keyword").value.trim();
  const result = query
    ? await api(`/risk/enterprise/kb/search?query=${encodeURIComponent(query)}`)
    : await api("/risk/knowledge/list?pageNum=1&pageSize=50");
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
  const button = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await withBusy(button, "写入中", async () => {
    const payload = Object.fromEntries(new FormData(event.currentTarget).entries());
    payload.status = "ACTIVE";
    await api("/risk/knowledge", { method: "POST", body: JSON.stringify(payload) });
    event.currentTarget.reset();
    setStatus("知识已写入");
    await loadKnowledge();
  });
}

async function loadSources() {
  const [sourceResult, crawlerResult] = await Promise.allSettled([
    api("/risk/source/list?pageNum=1&pageSize=50"),
    api("/risk/crawler/status")
  ]);
  const result = sourceResult.status === "fulfilled" ? sourceResult.value : null;
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
  if (crawlerResult.status === "fulfilled") {
    const status = dataOf(crawlerResult.value, {});
    $("#crawler-status").textContent = `爬取状态：${statusText(status.lastStatus)}${status.lastRunAt ? ` · ${formatDate(status.lastRunAt)}` : ""}`;
  } else {
    $("#crawler-status").textContent = "爬取状态接口不可用";
  }
}

async function submitSource(event) {
  const button = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await withBusy(button, "写入中", async () => {
    const payload = Object.fromEntries(new FormData(event.currentTarget).entries());
    payload.status = payload.status || "ACTIVE";
    await api("/risk/source", { method: "POST", body: JSON.stringify(payload) });
    event.currentTarget.reset();
    setStatus("数据源已写入");
    await loadSources();
  });
}

async function submitUpload(event) {
  const button = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await withBusy(button, "上传中", async () => {
    const form = new FormData(event.currentTarget);
    const result = await api("/risk/enterprise/report/upload", {
      method: "POST",
      body: form
    });
    const data = dataOf(result, {});
    event.currentTarget.reset();
    setStatus(`上传完成：企业 ${formatNumber(data.enterpriseRows)} 条，事件 ${formatNumber(data.eventRows)} 条`);
    await loadEnterprise();
  });
}

function downloadUploadTemplate() {
  const headers = [
    "enterpriseName",
    "creditCode",
    "industry",
    "region",
    "supplyChainRole",
    "eventTitle",
    "category",
    "riskLevel",
    "status",
    "sourceName",
    "riskScore",
    "longitude",
    "latitude",
    "description",
    "occurredAt"
  ];
  const blob = new Blob([`${headers.join(",")}\n`], { type: "text/csv;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "semirisk-upload-template.csv";
  document.body.appendChild(link);
  link.click();
  URL.revokeObjectURL(link.href);
  link.remove();
}

async function runCrawler(button) {
  await withBusy(button, "爬取中", async () => {
    const result = await api("/risk/crawler/run", { method: "POST" });
    const data = dataOf(result, {});
    const counts = data.counts || {};
    setStatus(`爬取完成：CISA ${formatNumber(counts.cisaKev)} 条，USGS ${formatNumber(counts.usgsEarthquake)} 条`);
    state.eventPage = 1;
    await Promise.all([loadSources(), loadEvents(), loadDashboard()]);
  });
}

async function switchView(view) {
  if (state.switching) return;
  state.currentView = view;
  state.switching = true;
  setStatus("");
  $all(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.view === view));
  $all(".view").forEach((panel) => panel.classList.toggle("active", panel.id === view));
  $("#view-title").textContent = titles[view] || view;
  setButtonBusy($("#refresh-btn"), true, "刷新中");
  try {
    await viewLoaders[view]?.();
    touchUpdated();
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    setButtonBusy($("#refresh-btn"), false);
    state.switching = false;
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
    $("#event-page-size").value = "20";
    state.eventPage = 1;
    state.eventPageSize = 20;
    closeEventDetail();
    switchView("events");
  };
  $("#event-page-size").onchange = () => {
    state.eventPage = 1;
    state.eventPageSize = Number($("#event-page-size").value) || 20;
    switchView("events");
  };
  ["#event-keyword", "#event-enterprise"].forEach((selector) => {
    $(selector).onkeydown = (event) => {
      if (event.key === "Enter") {
        state.eventPage = 1;
        switchView("events");
      }
    };
  });
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
  $("#event-form").onsubmit = bindSubmit(submitEvent);
  $("#event-detail-close").onclick = closeEventDetail;
  $("#enterprise-search").onclick = () => switchView("enterprise");
  $("#enterprise-keyword").onkeydown = (event) => {
    if (event.key === "Enter") switchView("enterprise");
  };
  $("#upload-form").onsubmit = bindSubmit(submitUpload);
  $("#download-template").onclick = downloadUploadTemplate;
  $("#report-form").onsubmit = bindSubmit(submitReport);
  $("#knowledge-search").onclick = () => switchView("knowledge");
  $("#knowledge-keyword").onkeydown = (event) => {
    if (event.key === "Enter") switchView("knowledge");
  };
  $("#knowledge-form").onsubmit = bindSubmit(submitKnowledge);
  $("#source-form").onsubmit = bindSubmit(submitSource);
  $("#crawler-run").onclick = () => runTask(() => runCrawler($("#crawler-run")));
}

bindEvents();
switchView("dashboard");
