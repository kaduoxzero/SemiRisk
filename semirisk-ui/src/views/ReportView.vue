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
        <b>{{ tpl.name }}</b><p class="muted">{{ tpl.scenario }}</p>
      </button>
    </div>
    <div class="panel">
      <div class="toolbar">
        <select v-model="state.reportForm.language" class="select"><option>中文</option><option>English</option><option>日本語</option></select>
        <select v-model="state.reportForm.format" class="select"><option>PDF</option><option>Word</option><option>PPT</option></select>
        <button class="btn" @click="actions.startReport">立即生成</button>
        <button v-if="state.reportJob?.downloadUrl" class="btn secondary" @click="actions.downloadReport">下载报告</button>
      </div>
      <p>进度：{{ state.reportJob?.progress || 0 }}% · {{ state.reportJob?.step || '等待任务' }}</p>
      <p v-if="state.reportJob?.downloadUrl" class="muted">导出格式：{{ state.reportJob.format }}，文件由后端按对应格式生成。</p>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
