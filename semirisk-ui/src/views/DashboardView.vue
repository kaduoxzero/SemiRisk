<template>
  <section class="grid">
    <AuthPanel v-if="!state.session" :state="state" :actions="actions" />
    <div class="grid cols-4">
      <div v-for="kpi in state.dashboard.kpis || []" :key="kpi.name" class="panel kpi">
        <div><span class="muted">{{ kpi.name }}</span><br /><strong>{{ kpi.value }}</strong></div>
        <span class="success">{{ kpi.trend }}</span>
      </div>
    </div>
    <div class="grid cols-2">
      <div class="panel">
        <h3>AI 本日风险测算</h3>
        <p>{{ state.dashboard.aiSummary }}</p>
        <p class="muted">数据来源：{{ state.dashboard.dataSource || '等待后端返回' }} · {{ state.dashboard.dataMode || 'UNKNOWN' }}</p>
        <p class="muted">刷新时间：{{ state.dashboard.refreshedAt }}</p>
        <button class="btn" @click="actions.recalculateRisk">立即重新测算</button>
      </div>
      <div class="panel fixed-panel dashboard-signal-panel">
        <h3>公开源爬虫信号</h3>
        <table class="dense-table">
          <tbody>
            <tr v-for="signal in visibleSignals" :key="signal.id">
              <td>
                <a v-if="signal.sourceUrl" class="text-link" :href="signal.sourceUrl" target="_blank" rel="noreferrer">{{ signal.source }}</a>
                <span v-else>{{ signal.source }}</span>
              </td>
              <td>{{ signal.title }}</td>
              <td><span class="badge" :class="riskBadge(signal.riskScore)">{{ signal.status === 'OK' ? signal.riskScore : signal.status }}</span></td>
            </tr>
            <tr v-if="!signals.length">
              <td colspan="3" class="muted">暂无公开源采集记录，请确认 data-service 已启动并可访问公开网站。</td>
            </tr>
          </tbody>
        </table>
        <div class="pager">
          <button class="btn secondary" :disabled="state.dashboardPage <= 1" @click="state.dashboardPage--">上一页</button>
          <span>第 {{ state.dashboardPage }} / {{ totalPages }} 页</span>
          <button class="btn secondary" :disabled="state.dashboardPage >= totalPages" @click="state.dashboardPage++">下一页</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, watch } from 'vue';
import AuthPanel from '../components/AuthPanel.vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const pageSize = 8;
const signals = computed(() => props.state.dashboard.dailyRisk?.signals || []);
const totalPages = computed(() => Math.max(1, Math.ceil(signals.value.length / pageSize)));
const visibleSignals = computed(() => signals.value.slice((props.state.dashboardPage - 1) * pageSize, props.state.dashboardPage * pageSize));

watch(signals, () => {
  props.state.dashboardPage = 1;
});

function riskBadge(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}
</script>
