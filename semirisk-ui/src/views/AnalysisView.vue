<template>
  <section class="grid">
    <div class="toolbar">
      <button class="btn" :class="{ secondary: state.analysisWindow !== '24h' }" @click="actions.loadRiskAnalysis('24h')">近 24 小时</button>
      <button class="btn" :class="{ secondary: state.analysisWindow !== '7d' }" @click="actions.loadRiskAnalysis('7d')">近 7 天</button>
      <button class="btn" :class="{ secondary: state.analysisWindow !== '30d' }" @click="actions.loadRiskAnalysis('30d')">近 30 天</button>
      <span class="muted">{{ state.analysis.windowLabel || state.analysisWindow }}</span>
    </div>

    <div class="grid cols-3">
      <div class="panel analysis-score">
        <h3>综合评分</h3>
        <strong class="score-number" :class="riskClass(state.analysis.score)">{{ state.analysis.score || 0 }}</strong>
        <div class="score-level-badge">
          <span class="badge" :class="riskClass(state.analysis.score)">{{ state.analysis.level || '待采集' }}</span>
        </div>
      </div>
      <div class="panel">
        <h3>核心研判</h3>
        <p style="line-height:1.65">{{ state.analysis.summary || '暂无研判数据' }}</p>
      </div>
      <div class="panel">
        <h3>推理链路</h3>
        <div class="reasoning-chain">
          <div v-for="(r, i) in state.analysis.reasoning || []" :key="r" class="reasoning-step">
            <span class="step-num">{{ i + 1 }}</span>
            <span>{{ r }}</span>
          </div>
          <p v-if="!(state.analysis.reasoning || []).length" class="muted">暂无推理数据</p>
        </div>
      </div>
    </div>

    <div class="grid cols-4">
      <div v-for="m in metricCards" :key="m.label" class="panel metric-card">
        <span class="metric-label">{{ m.label }}</span>
        <strong class="metric-value" :class="m.cls">{{ m.value }}</strong>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="panel">
        <h3>风险维度分布</h3>
        <div v-for="item in state.analysis.dimensions || []" :key="item.name" class="bar-row">
          <span class="bar-label">{{ item.name }}</span>
          <div class="bar-track">
            <div class="bar-fill" :class="riskClass(item.value || item.index)" :style="{ width: (item.value || item.index || 0) + '%' }" />
          </div>
          <strong class="bar-value" :class="riskClass(item.value || item.index)">{{ item.value || item.index }}</strong>
        </div>
        <p v-if="!(state.analysis.dimensions || []).length" class="muted">暂无维度数据</p>
      </div>
      <div class="panel">
        <h3>来源分布</h3>
        <div v-for="item in state.analysis.sources || []" :key="item.name" class="source-row">
          <span>{{ item.name }}</span>
          <strong>{{ item.value }}</strong>
        </div>
        <p v-if="!(state.analysis.sources || []).length" class="muted">暂无来源数据</p>
      </div>
    </div>

    <div v-if="(state.analysis.solutions || []).length" class="cards">
      <div v-for="s in state.analysis.solutions" :key="s.name" class="card-option">
        <b>{{ s.name }}</b>
        <p class="success">{{ s.feasibility }}% 可行</p>
        <p class="muted">{{ s.owner }} · {{ s.deadline }}</p>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar compact-toolbar">
        <h3>事件时间线</h3>
        <span class="badge low">{{ (state.analysis.timeline || []).length }} 条</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>时间</th><th>来源</th><th>维度</th><th>评分</th><th>事件</th></tr></thead>
          <tbody>
            <tr v-for="item in state.analysis.timeline || []" :key="`${item.time}-${item.title}`">
              <td class="muted" style="white-space:nowrap;font-size:12px">{{ item.time }}</td>
              <td>{{ item.source }}</td>
              <td>{{ item.dimension }}</td>
              <td><span class="badge" :class="riskClass(item.score)">{{ item.score }}</span></td>
              <td>
                <a v-if="item.url" class="text-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a>
                <span v-else>{{ item.title }}</span>
              </td>
            </tr>
            <tr v-if="!(state.analysis.timeline || []).length">
              <td colspan="5" class="muted" style="text-align:center;padding:20px">当前时间窗口暂无公开源信号。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const metricCards = computed(() => [
  { label: '窗口信号', value: props.state.analysis.metrics?.signalCount || 0, cls: '' },
  { label: '高危信号', value: props.state.analysis.metrics?.highCount || 0, cls: 'high' },
  { label: '平均评分', value: props.state.analysis.metrics?.avgScore || 0, cls: riskClass(props.state.analysis.metrics?.avgScore) },
  { label: '数据来源', value: props.state.analysis.metrics?.sourceCount || 0, cls: '' }
]);

function riskClass(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}
</script>
