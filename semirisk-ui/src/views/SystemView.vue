<template>
  <section class="grid">
    <div class="panel">
      <h3>AI 模型 API Key 配置</h3>
      <p class="muted">保存后，知识库智能体会优先调用该模型；返回中会展示 `aiCalled`、模型状态和 Token 使用量。</p>
      <div class="toolbar">
        <input v-model="state.aiConfig.model" class="input" placeholder="模型名称，如 deepseekv4-pro" />
        <input v-model="state.aiConfig.endpoint" class="input" placeholder="Endpoint" />
        <input v-model="state.aiConfig.apiKey" class="input" placeholder="API Key" type="password" />
        <button class="btn" @click="actions.saveAiConfig">保存并用于 AI 问答</button>
      </div>
    </div>
    <div class="grid cols-2">
      <div class="panel fixed-panel">
        <h3>用户</h3>
        <p v-for="u in state.system.users || []" :key="u.id">{{ u.username }} · {{ u.role }} · {{ u.status }}</p>
      </div>
      <div class="panel fixed-panel">
        <div class="toolbar compact-toolbar">
          <h3>系统日志</h3>
          <input v-model="state.systemLogDate" class="input date-input" type="date" />
        </div>
        <p v-for="log in visibleLogs" :key="log" class="muted">{{ log }}</p>
        <p v-if="!visibleLogs.length" class="muted">当日暂无日志。</p>
        <div class="pager">
          <button class="btn secondary" :disabled="state.systemLogPage <= 1" @click="state.systemLogPage--">上一页</button>
          <span>{{ state.systemLogPage }} / {{ totalPages }}</span>
          <button class="btn secondary" :disabled="state.systemLogPage >= totalPages" @click="state.systemLogPage++">下一页</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, watch } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const pageSize = 8;
const logs = computed(() => props.state.system.logs || []);
const filteredLogs = computed(() => {
  const date = props.state.systemLogDate;
  if (!date) return logs.value;
  return logs.value.filter(log => String(log).includes(date) || !/^\\d{4}-\\d{2}-\\d{2}/.test(String(log)));
});
const totalPages = computed(() => Math.max(1, Math.ceil(filteredLogs.value.length / pageSize)));
const visibleLogs = computed(() => filteredLogs.value.slice((props.state.systemLogPage - 1) * pageSize, props.state.systemLogPage * pageSize));

watch(() => props.state.systemLogDate, () => {
  props.state.systemLogPage = 1;
});
</script>
