<template>
  <section class="grid knowledge-layout">
    <div class="panel ai-agent-panel">
      <h3>AI 知识库智能体</h3>
      <div class="toolbar">
        <input v-model="state.knowledgeQuestion" class="input wide" placeholder="输入供应链风险问题" @keydown.enter="actions.askKnowledge" />
        <button class="btn" :disabled="state.knowledgeLoading" @click="actions.askKnowledge">{{ state.knowledgeLoading ? '检索中' : '提问' }}</button>
      </div>
      <div v-if="state.knowledgeAnswer" class="agent-answer">
        <p>{{ state.knowledgeAnswer.answer }}</p>
        <div class="agent-trace">
          <span v-for="step in state.knowledgeAnswer.trace || []" :key="step">{{ step }}</span>
        </div>
        <div class="cards compact-cards">
          <article v-for="item in state.knowledgeAnswer.citations || []" :key="item.id" class="card-option">
            <b>{{ item.source }}</b>
            <p>{{ item.title }}</p>
            <a class="text-link" :href="item.sourceUrl" target="_blank" rel="noreferrer">引用原文</a>
          </article>
        </div>
        <p class="muted">{{ state.knowledgeAnswer.model }} · {{ state.knowledgeAnswer.modelStatus }}</p>
      </div>
      <p v-else class="muted">智能体会先检索知识库公开源记录，再基于引用生成回答。</p>
    </div>

    <div class="panel">
      <div class="toolbar">
        <input v-model="state.knowledgeQuery" class="input wide" placeholder="Ctrl+K 搜索知识库" @keydown.enter="actions.searchKnowledge" />
        <button class="btn" @click="actions.searchKnowledge">检索</button>
      </div>
      <div class="cards">
        <article v-for="item in state.knowledge.results || []" :key="item.id" class="card-option">
          <b>{{ item.title }}</b><p>{{ item.summary }}</p><p class="success">Similarity: {{ item.similarity }}%</p>
          <a v-if="item.url" class="text-link" :href="item.url" target="_blank" rel="noreferrer">查看公开源</a>
        </article>
        <article v-if="!(state.knowledge.results || []).length" class="card-option">
          <b>暂无公开源结果</b><p class="muted">请检查 data-service 是否运行，或更换关键词后重新检索。</p>
        </article>
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
