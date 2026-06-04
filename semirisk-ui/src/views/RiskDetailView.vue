<template>
  <section class="grid cols-2">
    <div class="panel">
      <h3>风险详情</h3>
      <p>编号：{{ state.riskDetail.id }}</p>
      <p>类型：{{ state.riskDetail.type }}</p>
      <p>状态：{{ state.riskDetail.status }}</p>
      <p>预计周损失：{{ state.riskDetail.weeklyLoss }}</p>
      <button class="btn" @click="actions.assignRisk">指派负责人</button>
      <button class="btn secondary" @click="actions.setView('report')">生成处置报告</button>
    </div>
    <div class="panel">
      <h3>SOP</h3>
      <p v-for="step in state.riskDetail.sop || []" :key="step">{{ step }}</p>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
