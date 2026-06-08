<template>
  <section class="grid">
    <div class="toolbar">
      <button class="btn" :class="{ secondary: state.analysisWindow !== '24h' }" @click="actions.loadRiskAnalysis('24h')">近24小时</button>
      <button class="btn" :class="{ secondary: state.analysisWindow !== '7d' }" @click="actions.loadRiskAnalysis('7d')">近7天</button>
      <button class="btn" :class="{ secondary: state.analysisWindow !== '30d' }" @click="actions.loadRiskAnalysis('30d')">近30天</button>
      <span class="muted">当前切片：{{ state.analysis.windowLabel || state.analysis.window || state.analysisWindow }}</span>
    </div>
    <div class="grid cols-3">
      <div class="panel analysis-score">
        <h3>系统评分</h3>
        <strong :class="riskClass(state.analysis.score)">{{ state.analysis.score || 0 }}</strong>
        <p class="muted">{{ state.analysis.level || '待采集' }}</p>
      </div>
      <div class="panel"><h3>核心研判</h3><p>{{ state.analysis.summary }}</p></div>
      <div class="panel"><h3>推理链</h3><p v-for="r in state.analysis.reasoning || []" :key="r">{{ r }}</p></div>
    </div>

    <div class="grid cols-4">
      <div class="panel metric-card"><span>窗口信号</span><strong>{{ state.analysis.metrics?.signalCount || 0 }}</strong></div>
      <div class="panel metric-card"><span>高危信号</span><strong class="high">{{ state.analysis.metrics?.highCount || 0 }}</strong></div>
      <div class="panel metric-card"><span>平均评分</span><strong>{{ state.analysis.metrics?.avgScore || 0 }}</strong></div>
      <div class="panel metric-card"><span>数据来源</span><strong>{{ state.analysis.metrics?.sourceCount || 0 }}</strong></div>
    </div>

    <div class="grid cols-2">
      <div class="panel">
        <h3>风险维度</h3>
        <div v-for="item in state.analysis.dimensions || []" :key="item.name" class="bar-row">
          <span>{{ item.name }}</span>
          <meter min="0" max="100" :value="item.value || item.index"></meter>
          <strong>{{ item.value || item.index }}</strong>
        </div>
      </div>
      <div class="panel">
        <h3>来源分布</h3>
        <div v-for="item in state.analysis.sources || []" :key="item.name" class="source-row">
          <span>{{ item.name }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </div>
    </div>

    <div class="cards">
      <div v-for="s in state.analysis.solutions || []" :key="s.name" class="card-option">
        <b>{{ s.name }}</b>
        <p class="success">{{ s.feasibility }}% 可行</p>
        <p class="muted">{{ s.owner }} · {{ s.deadline }}</p>
      </div>
    </div>

    <div class="panel">
      <h3>时间线</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>时间</th><th>来源</th><th>维度</th><th>评分</th><th>事件</th></tr></thead>
          <tbody>
            <tr v-for="item in state.analysis.timeline || []" :key="`${item.time}-${item.title}`">
              <td>{{ item.time }}</td>
              <td>{{ item.source }}</td>
              <td>{{ item.dimension }}</td>
              <td><span class="badge" :class="riskClass(item.score)">{{ item.score }}</span></td>
              <td><a v-if="item.url" class="text-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a><span v-else>{{ item.title }}</span></td>
            </tr>
            <tr v-if="!(state.analysis.timeline || []).length"><td colspan="5" class="muted">当前时间窗口暂无公开源信号。</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

function riskClass(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}
</script>
