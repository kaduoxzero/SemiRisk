<template>
  <section class="grid">
    <div class="toolbar">
      <button class="btn" @click="actions.loadRiskAnalysis('24h')">近24小时</button>
      <button class="btn secondary" @click="actions.loadRiskAnalysis('7d')">近7天</button>
      <button class="btn secondary" @click="actions.loadRiskAnalysis('30d')">近30天</button>
    </div>
    <div class="grid cols-3">
      <div class="panel"><h3>系统评分</h3><strong class="warning">{{ state.analysis.score }}</strong></div>
      <div class="panel"><h3>核心研判</h3><p>{{ state.analysis.summary }}</p></div>
      <div class="panel"><h3>推理链</h3><p v-for="r in state.analysis.reasoning || []" :key="r">{{ r }}</p></div>
    </div>
    <div class="cards">
      <div v-for="s in state.analysis.solutions || []" :key="s.name" class="card-option">
        <b>{{ s.name }}</b><p class="success">{{ s.feasibility }}% 可行</p>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
