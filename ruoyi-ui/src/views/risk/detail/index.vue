<template>
  <div class="risk-page space-y-6">
    <div class="hud-card p-5 flex flex-wrap gap-3">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <input v-model="eventId" class="cyber-input w-72 px-3 py-2 text-xs" placeholder="输入风险事件 ID" @keyup.enter="loadData" />
      <button class="rounded bg-primary px-4 py-2 text-xs font-bold text-white" @click="loadData">读取详情</button>
    </div>

    <div v-if="!detail" class="hud-card p-12 text-center text-sm text-slate-500">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      请从预警中心选择真实事件，或输入事件 ID
    </div>

    <div v-else class="grid gap-6 lg:grid-cols-3">
      <div class="hud-card p-5 lg:col-span-2">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h2 class="text-lg font-bold text-white">{{ detail.eventTitle }}</h2>
        <div class="mt-4 grid gap-3 text-xs text-slate-300 md:grid-cols-2">
          <div>事件编号：{{ detail.eventCode || detail.eventId }}</div>
          <div>企业：{{ detail.enterpriseName || '-' }}</div>
          <div>分类：{{ detail.category || '-' }}</div>
          <div>等级：{{ detail.riskLevel || '-' }}</div>
          <div>状态：{{ detail.status || '-' }}</div>
          <div>风险分：{{ detail.riskScore || 0 }}</div>
          <div>来源：{{ detail.sourceName || '-' }}</div>
          <div>发生时间：{{ detail.occurredAt || '-' }}</div>
        </div>
        <p class="mt-5 whitespace-pre-wrap text-sm leading-7 text-slate-300">{{ detail.description || '该真实记录暂无描述' }}</p>
      </div>
      <div class="hud-card p-5">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-sm font-bold text-white">处置建议</h3>
        <textarea v-model="suggestion" class="cyber-textarea mt-4 h-40 w-full p-3 text-xs" placeholder="写入真实处置意见"></textarea>
        <button class="mt-3 w-full rounded bg-success px-4 py-2 text-xs font-bold text-white" @click="closeEvent">标记闭环</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getRiskEvent, handleRiskEvent } from '@/api/risk/event';

const route = useRoute();
const eventId = ref(String(route.query.id || ''));
const detail = ref<any>(null);
const suggestion = ref('');

const loadData = async () => {
  if (!eventId.value) return;
  const res = await getRiskEvent(eventId.value);
  detail.value = res.data;
  suggestion.value = detail.value?.disposalSuggestion || '';
};

const closeEvent = async () => {
  if (!detail.value?.eventId) return;
  await handleRiskEvent(detail.value.eventId, 'RESOLVED', suggestion.value);
  ElMessage.success('处置结果已写入真实业务表');
  loadData();
};

onMounted(loadData);
</script>
