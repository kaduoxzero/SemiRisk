<template>
  <section class="grid">
    <div class="grid cols-4">
      <div v-for="kpi in state.dashboard.kpis || []" :key="kpi.name" class="panel kpi">
        <div>
          <span class="muted kpi-label">{{ kpi.name }}</span>
          <strong class="kpi-value">{{ kpi.value }}</strong>
        </div>
        <span class="kpi-trend" :class="trendClass(kpi.trend)">{{ kpi.trend }}</span>
      </div>
      <div v-if="!(state.dashboard.kpis || []).length" v-for="i in 4" :key="i" class="panel kpi skeleton" />
    </div>

    <div class="grid cols-2">
      <div class="panel">
        <div class="toolbar compact-toolbar">
          <h3>AI 本日风险测算</h3>
          <span class="badge" :class="riskBadge(state.dashboard.dailyRisk?.score)">
            {{ state.dashboard.dailyRisk?.level || '待测算' }}
          </span>
        </div>
        <p class="muted" style="font-size:13px;line-height:1.65;">{{ state.dashboard.aiSummary }}</p>
        <div class="meta-row">
          <span>数据源：{{ state.dashboard.dataSource || '公开 RSS/Atom' }}</span>
          <span>{{ state.dashboard.dataMode || '' }}</span>
          <span>刷新：{{ state.dashboard.refreshedAt || '等待' }}</span>
        </div>
        <button class="btn" style="margin-top:10px" :disabled="recalcLoading" @click="triggerRecalc">
          {{ recalcLoading ? '测算中…' : '立即重新测算' }}
        </button>
      </div>

      <div class="panel fixed-panel dashboard-signal-panel">
        <div class="toolbar compact-toolbar">
          <h3>公开源爬虫信号</h3>
          <span class="badge low">{{ signals.length }} 条</span>
        </div>
        <table class="dense-table">
          <tbody>
            <tr v-for="signal in visibleSignals" :key="signal.id">
              <td>
                <a v-if="signal.sourceUrl" class="text-link" :href="signal.sourceUrl" target="_blank" rel="noreferrer">{{ signal.source }}</a>
                <span v-else>{{ signal.source }}</span>
              </td>
              <td>{{ truncate(signal.title, 52) }}</td>
              <td><span class="badge" :class="riskBadge(signal.riskScore)">{{ signal.status === 'OK' ? signal.riskScore : signal.status }}</span></td>
            </tr>
            <tr v-if="!signals.length">
              <td colspan="3" class="muted">暂无采集记录，请确认 data-service 已启动。</td>
            </tr>
          </tbody>
        </table>
        <div class="pager">
          <button class="btn secondary" :disabled="state.dashboardPage <= 1" @click="state.dashboardPage--">上一页</button>
          <span>{{ state.dashboardPage }} / {{ totalPages }}</span>
          <button class="btn secondary" :disabled="state.dashboardPage >= totalPages" @click="state.dashboardPage++">下一页</button>
        </div>
      </div>
    </div>

    <div v-if="report" class="panel ai-report-panel">
      <div class="toolbar compact-toolbar">
        <h3>AI 本日风险分析报告</h3>
        <div style="display:flex;gap:8px;align-items:center">
          <span class="badge" :class="report.aiCalled ? 'low' : 'mid'">
            {{ report.aiCalled ? 'DeepSeek · ' + report.model : (report.pending ? '生成中…' : '本地聚合') }}
          </span>
          <span v-if="report.usage?.totalTokens" class="muted" style="font-size:12px">{{ report.usage.totalTokens }} tokens</span>
        </div>
      </div>
      <p class="muted" style="font-size:12px;margin:0 0 10px">{{ report.modelStatus }}</p>
      <p class="report-summary">{{ report.summary }}</p>
      <ul v-if="(report.sections || []).length" class="report-sections">
        <li v-for="(line, idx) in report.sections" :key="idx">{{ line }}</li>
      </ul>
      <div class="meta-row" style="margin-top:10px">
        <span v-if="report.recommendation">处置建议：{{ report.recommendation }}</span>
        <span>生成：{{ report.generatedAt }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const recalcLoading = ref(false);

async function triggerRecalc() {
  recalcLoading.value = true;
  try {
    await props.actions.recalculateRisk();
  } finally {
    recalcLoading.value = false;
  }
}

const pageSize = 8;
const signals = computed(() => props.state.dashboard.dailyRisk?.signals || []);
const report = computed(() => props.state.dashboard.aiReport || null);
const totalPages = computed(() => Math.max(1, Math.ceil(signals.value.length / pageSize)));
const visibleSignals = computed(() => signals.value.slice((props.state.dashboardPage - 1) * pageSize, props.state.dashboardPage * pageSize));

function riskBadge(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}

function trendClass(trend) {
  if (!trend) return 'muted';
  if (String(trend).startsWith('+') || String(trend).includes('↑')) return 'success';
  if (String(trend).startsWith('-') || String(trend).includes('↓')) return 'danger';
  return 'muted';
}

function truncate(text, n) {
  return text && text.length > n ? text.slice(0, n) + '…' : text;
}
</script>
