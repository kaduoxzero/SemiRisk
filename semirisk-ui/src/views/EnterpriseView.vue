<template>
  <section class="grid">
    <div class="toolbar">
      <input v-model="state.enterpriseKeyword" class="input" placeholder="企业名称或统一社会信用代码" />
      <button class="btn" @click="actions.loadEnterprise">搜索画像</button>
    </div>
    <div class="grid cols-3">
      <div class="panel"><h3>{{ state.enterprise.name }}</h3><p>{{ state.enterprise.creditCode }}</p><p>信用：{{ state.enterprise.creditLevel }}</p></div>
      <div class="panel"><h3>风险评分</h3><strong class="warning">{{ state.enterprise.riskScore }}</strong></div>
      <div class="panel"><h3>拓扑</h3><p>{{ (state.enterprise.topology || []).join(' -> ') }}</p></div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
