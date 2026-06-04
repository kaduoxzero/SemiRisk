<template>
  <section class="grid">
    <div class="panel">
      <h3>AI 模型 API Key 配置</h3>
      <div class="toolbar">
        <input v-model="state.aiConfig.model" class="input" placeholder="模型名称，如 deepseekv4-pro" />
        <input v-model="state.aiConfig.endpoint" class="input" placeholder="Endpoint" />
        <input v-model="state.aiConfig.apiKey" class="input" placeholder="API Key" type="password" />
        <button class="btn" @click="actions.saveAiConfig">保存配置</button>
      </div>
    </div>
    <div class="grid cols-2">
      <div class="panel"><h3>用户</h3><p v-for="u in state.system.users || []" :key="u.id">{{ u.username }} · {{ u.role }} · {{ u.status }}</p></div>
      <div class="panel"><h3>系统日志</h3><p v-for="log in state.system.logs || []" :key="log" class="muted">{{ log }}</p></div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
