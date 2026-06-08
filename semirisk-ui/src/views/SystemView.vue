<template>
  <section class="grid">
    <div class="panel">
      <div class="toolbar compact-toolbar">
        <h3>AI 模型 API Key 配置</h3>
        <span v-if="state.modelPing" class="badge" :class="state.modelPing.reachable ? 'low' : 'high'">
          {{ state.modelPing.reachable ? '可达 ' + state.modelPing.latencyMs + 'ms' : '不可达' }}
        </span>
      </div>
      <p class="muted">保存后知识库智能体与本日报告会优先调用该模型；返回中展示 aiCalled、模型状态与 Token 使用量。</p>
      <div class="toolbar">
        <input v-model="state.aiConfig.model" class="input" placeholder="模型名称，如 deepseek-v4-pro" />
        <input v-model="state.aiConfig.endpoint" class="input" placeholder="Endpoint" />
        <input v-model="state.aiConfig.apiKey" class="input" placeholder="API Key" type="password" />
        <button class="btn" @click="actions.saveAiConfig">保存并用于 AI</button>
        <button class="btn secondary" @click="actions.pingModel">连通性测试</button>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="panel">
        <h3>中间件健康（实时探测）</h3>
        <table class="ops-table">
          <thead><tr><th>数据源</th><th>地址</th><th>状态</th><th>延迟</th><th></th></tr></thead>
          <tbody>
            <tr v-for="ds in state.system.dataSources || []" :key="ds.name">
              <td><strong>{{ ds.name }}</strong><br /><span class="muted">{{ ds.role }}</span></td>
              <td class="muted">{{ ds.host }}</td>
              <td><span class="badge" :class="ds.reachable ? 'low' : 'high'">{{ ds.status }}</span></td>
              <td>{{ ds.reachable ? ds.latencyMs + 'ms' : '-' }}</td>
              <td><button class="btn secondary tiny" @click="actions.reconnectSource(ds.name)">重连</button></td>
            </tr>
            <tr v-if="!(state.system.dataSources || []).length">
              <td colspan="5" class="muted">暂无探测结果。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <h3>调度 Agent（真实定时任务）</h3>
        <article v-for="agent in state.system.agents || []" :key="agent.name" class="list-row agent-row">
          <div>
            <strong>{{ agent.name }}</strong>
            <span class="badge" :class="agent.status === '运行中' ? 'low' : 'mid'">{{ agent.status }}</span>
          </div>
          <p class="muted">cron {{ agent.cron }} · 最近 {{ agent.lastPull }}</p>
          <p class="muted">{{ agent.detail }}</p>
          <button class="btn secondary tiny" @click="actions.triggerAgent(agent.name)">手动触发</button>
        </article>
        <p v-if="!(state.system.agents || []).length" class="muted">暂无 Agent。</p>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="panel fixed-panel">
        <h3>用户</h3>
        <p v-for="u in state.system.users || []" :key="u.id">{{ u.username }} · {{ u.role }} · {{ u.status }}</p>
        <p v-if="!(state.system.users || []).length" class="muted">暂无用户。</p>
      </div>
      <div class="panel fixed-panel">
        <div class="toolbar compact-toolbar">
          <h3>系统日志</h3>
          <input v-model="state.systemLogDate" class="input date-input" type="date" />
        </div>
        <p v-for="log in visibleLogs" :key="log" class="muted log-line">{{ log }}</p>
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
  return logs.value.filter(log => String(log).includes(date) || !/^\d{4}-\d{2}-\d{2}/.test(String(log)));
});
const totalPages = computed(() => Math.max(1, Math.ceil(filteredLogs.value.length / pageSize)));
const visibleLogs = computed(() => filteredLogs.value.slice((props.state.systemLogPage - 1) * pageSize, props.state.systemLogPage * pageSize));

watch(() => props.state.systemLogDate, () => {
  props.state.systemLogPage = 1;
});
</script>
