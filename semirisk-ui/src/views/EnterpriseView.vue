<template>
  <section class="grid">
    <div class="toolbar">
      <input v-model="state.enterpriseKeyword" class="input wide" placeholder="企业名称或统一社会信用代码" />
      <button class="btn" @click="actions.loadEnterprise">搜索画像</button>
    </div>
    <div class="enterprise-layout">
      <div class="panel">
        <h3>{{ state.enterprise.name }}</h3>
        <p class="muted">{{ state.enterprise.creditCode }}</p>
        <div class="score-circle">{{ state.enterprise.riskScore || 0 }}</div>
        <p>信用等级：{{ state.enterprise.creditLevel }}</p>
        <p>行业：{{ state.enterprise.industry }}</p>
      </div>
      <div class="panel fixed-panel">
        <div class="toolbar compact-toolbar">
          <h3>{{ pages[state.enterprisePage - 1]?.title }}</h3>
          <span class="muted">{{ state.enterprisePage }} / {{ pages.length }}</span>
        </div>
        <div v-if="state.enterprisePage === 1" class="kv-list">
          <p v-for="(value, key) in state.enterprise.business || {}" :key="key"><span>{{ key }}</span><strong>{{ value }}</strong></p>
        </div>
        <div v-else-if="state.enterprisePage === 2" class="radar-list">
          <p v-for="(value, index) in state.enterprise.radar || []" :key="index"><span>{{ radarLabels[index] }}</span><meter min="0" max="100" :value="value"></meter><strong>{{ value }}</strong></p>
        </div>
        <div v-else-if="state.enterprisePage === 3">
          <p>{{ (state.enterprise.topology || []).join(' -> ') }}</p>
        </div>
        <div v-else>
          <p v-for="event in state.enterprise.events || []" :key="event">{{ event }}</p>
          <p v-if="!(state.enterprise.events || []).length" class="muted">暂无公开源关联事件。</p>
        </div>
        <div class="pager">
          <button class="btn secondary" :disabled="state.enterprisePage <= 1" @click="state.enterprisePage--">上一页</button>
          <button class="btn secondary" :disabled="state.enterprisePage >= pages.length" @click="state.enterprisePage++">下一页</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const pages = [
  { title: '工商基础数据' },
  { title: '多维风险评估' },
  { title: '上下游拓扑' },
  { title: '历史事件' }
];
const radarLabels = ['财务状况', '运营能力', '合规风险', '技术实力', '供应链稳定性'];
</script>
