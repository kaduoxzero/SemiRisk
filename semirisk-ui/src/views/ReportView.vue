<template>
  <section class="grid">
    <div class="cards">
      <button
        v-for="tpl in state.reportTemplates"
        :key="tpl.id"
        class="card-option"
        :class="{ active: state.reportForm.template === tpl.id }"
        @click="state.reportForm.template = tpl.id"
      >
        <b>{{ tpl.name }}</b>
        <p class="muted">{{ tpl.scenario }}</p>
        <span class="badge low">{{ tpl.format || 'PDF' }}</span>
      </button>
    </div>
    <div class="panel">
      <div class="toolbar">
        <select v-model="state.reportForm.language" class="select"><option>中文</option><option>English</option><option>日本語</option></select>
        <span class="format-lock">PDF</span>
        <button class="btn" @click="actions.startReport">立即生成</button>
        <button v-if="state.reportJob?.downloadUrl" class="btn secondary" @click="actions.downloadReport">下载报告</button>
      </div>
      <p>进度：{{ state.reportJob?.progress || 0 }}% · {{ state.reportJob?.step || '等待任务' }}</p>
      <p class="muted">导出格式固定为 PDF。后端会按所选报告类型组织不同上下文，并结合 AI 生成正文。</p>
      <div class="report-type-note">
        <p v-if="state.reportForm.template === 'risk-assessment'">风险评估报告：聚焦评分、公开源事实、影响范围、处置优先级和闭环指标。</p>
        <p v-else-if="state.reportForm.template === 'supply-chain'">供应链分析报告：聚焦物流路径、供应商韧性、库存影响、替代方案和协同动作。</p>
        <p v-else>企业尽调报告：聚焦企业主体、信用风险、公开源事件、合作建议和需人工核验事项。</p>
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
