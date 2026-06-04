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
        <p class="muted">刷新时间：{{ state.dashboard.refreshedAt }}</p>
        <button class="btn" @click="actions.recalculateRisk">立即重新测算</button>
      </div>
      <div class="panel">
        <h3>爬虫信号</h3>
        <table>
          <tbody>
            <tr v-for="signal in state.dashboard.dailyRisk?.signals || []" :key="signal.id">
              <td>{{ signal.source }}</td>
              <td>{{ signal.title }}</td>
              <td><span class="badge mid">{{ signal.riskScore }}</span></td>
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
</script>
