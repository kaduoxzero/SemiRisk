<template>
  <section class="grid knowledge-layout">
    <div class="panel ai-agent-panel tall-agent">
      <h3>AI 本地知识库智能体</h3>
      <p class="muted">仅保留问答入口。后端会先检索本地知识库、公开源和 Elasticsearch，再调用已配置 AI 生成回答。</p>
      <div class="toolbar">
        <input v-model="state.knowledgeQuestion" class="input wide" placeholder="输入供应链风险问题" @keydown.enter="actions.askKnowledge" />
        <button class="btn" :disabled="state.knowledgeLoading" @click="actions.askKnowledge">{{ state.knowledgeLoading ? '生成中' : '提问' }}</button>
      </div>
      <div v-if="state.knowledgeAnswer" class="agent-answer">
        <p>{{ state.knowledgeAnswer.answer }}</p>
        <div class="agent-trace">
          <span v-for="step in state.knowledgeAnswer.trace || []" :key="step">{{ step }}</span>
        </div>
        <div class="usage-row">
          <span>{{ state.knowledgeAnswer.model }}</span>
          <span>{{ state.knowledgeAnswer.modelStatus }}</span>
          <span v-if="state.knowledgeAnswer.usage?.totalTokens">Token: {{ state.knowledgeAnswer.usage.totalTokens }}</span>
        </div>
        <div class="cards compact-cards">
          <article v-for="item in state.knowledgeAnswer.citations || []" :key="item.id" class="card-option">
            <b>{{ item.source }}</b>
            <p>{{ item.title }}</p>
            <a class="text-link" :href="item.sourceUrl" target="_blank" rel="noreferrer">引用原文</a>
          </article>
        </div>
      </div>
      <p v-else class="muted">输入问题后，智能体会返回答案、检索链路、引用和实际 AI 调用状态。</p>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
