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
      <div class="panel">
        <h3>公开源爬虫信号</h3>
        <table>
          <tbody>
            <tr v-for="signal in state.dashboard.dailyRisk?.signals || []" :key="signal.id">
              <td>
                <a v-if="signal.sourceUrl" class="text-link" :href="signal.sourceUrl" target="_blank" rel="noreferrer">{{ signal.source }}</a>
                <span v-else>{{ signal.source }}</span>
              </td>
              <td>{{ signal.title }}</td>
              <td><span class="badge" :class="riskBadge(signal.riskScore)">{{ signal.status === 'OK' ? signal.riskScore : signal.status }}</span></td>
            </tr>
            <tr v-if="!(state.dashboard.dailyRisk?.signals || []).length">
              <td colspan="3" class="muted">暂无公开源采集记录，请确认 data-service 已启动并可访问公开网站。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
import AuthPanel from '../components/AuthPanel.vue';

defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

function riskBadge(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}
</script>
