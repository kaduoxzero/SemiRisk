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
        <span class="badge low">{{ tpl.format || 'PDF/DOCX/PPTX' }}</span>
      </button>
    </div>

    <div class="panel report-form-panel">
      <div class="toolbar">
        <label class="config-label" style="min-width:120px">
          语言
          <select v-model="state.reportForm.language" class="select">
            <option>中文</option>
            <option>English</option>
            <option>日本語</option>
          </select>
        </label>
        <label class="config-label" style="min-width:120px">
          格式
          <select v-model="state.reportForm.format" class="select">
            <option value="PDF">PDF</option>
            <option value="WORD">Word (.docx)</option>
            <option value="PPT">PowerPoint (.pptx)</option>
          </select>
        </label>
        <button class="btn" :disabled="generating" @click="actions.startReport">
          {{ generating ? '生成中...' : '立即生成' }}
        </button>
        <button v-if="canDownload" class="btn secondary" :disabled="downloading" @click="handleDownload">
          {{ downloading ? '正在下载...' : '下载 ' + (state.reportForm.format || 'PDF') }}
        </button>
      </div>

      <div v-if="state.reportJob" class="report-progress-wrap">
        <div class="report-progress-bar" :style="{ width: (state.reportJob.progress || 0) + '%' }" :class="progressClass" />
        <span class="muted" style="font-size:12px">{{ state.reportJob.progress || 0 }}% | {{ state.reportJob.step || '等待任务' }}</span>
      </div>

      <p class="muted report-type-note">
        <template v-if="state.reportForm.template === 'risk-assessment'">
          风险评估报告：聚焦评分、公开源事实、影响范围、处置优先级和闭环指标。
        </template>
        <template v-else-if="state.reportForm.template === 'supply-chain'">
          供应链分析报告：聚焦物流路径、供应商韧性、库存影响、替代方案和协同动作。
        </template>
        <template v-else>
          企业尽调报告：聚焦企业主体、信用风险、公开源事件、合作建议和需人工核验事项。
        </template>
      </p>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue';
import { downloadFile } from '../api/client';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const generating = computed(() => {
  const p = props.state.reportJob?.progress;
  return p !== undefined && p < 100;
});

const canDownload = computed(() => props.state.reportJob?.progress >= 100 && props.state.reportJob?.id);

const progressClass = computed(() => {
  const p = props.state.reportJob?.progress || 0;
  return p >= 100 ? 'done' : p > 0 ? 'active' : '';
});

const downloading = ref(false);

function handleDownload() {
  const id = props.state.reportJob?.id;
  if (!id) {
    return;
  }
  downloading.value = true;
  const ext = (props.state.reportForm.format || 'PDF').toLowerCase().replace('word', 'docx').replace('ppt', 'pptx');
  downloadFile(`/api/reports/${id}/download`, `${id}-report.${ext}`)
    .finally(() => {
      downloading.value = false;
    });
}
</script>
