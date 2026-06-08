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
        <div class="structured-answer">
          <section v-for="section in answerSections" :key="section.title" class="answer-section">
            <h4>{{ section.title }}</h4>
            <ul>
              <li v-for="item in section.items" :key="item">{{ item }}</li>
            </ul>
          </section>
        </div>
        <div class="agent-trace">
          <span v-for="step in state.knowledgeAnswer.trace || []" :key="step">{{ step }}</span>
        </div>
        <div v-if="(state.knowledgeAnswer.nextActions || []).length" class="next-actions">
          <span v-for="item in state.knowledgeAnswer.nextActions" :key="item">{{ item }}</span>
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

    <div class="panel knowledge-search-panel">
      <div class="toolbar compact-toolbar">
        <h3>知识库检索</h3>
        <span class="muted">{{ (state.knowledge.searchEngine) || '本地检索' }}</span>
      </div>
      <div class="toolbar">
        <input v-model="state.knowledgeQuery" class="input wide" placeholder="检索公开情报 / 政策法规 / 内部知识库" @keydown.enter="actions.searchKnowledge" />
        <button class="btn" @click="actions.searchKnowledge">检索</button>
      </div>
      <div class="category-chips">
        <span v-for="cat in state.knowledge.categories || []" :key="cat" class="chip">{{ cat }}</span>
      </div>
      <div class="cards compact-cards knowledge-results">
        <article v-for="item in state.knowledge.results || []" :key="item.id" class="card-option">
          <span class="badge" :class="categoryClass(item.category)">{{ item.category || item.size || 'WEB' }}</span>
          <b>{{ item.title }}</b>
          <p class="muted">{{ item.summary }}</p>
          <div class="card-links">
            <a v-if="item.url" class="text-link" :href="item.url" target="_blank" rel="noreferrer">原文</a>
            <a class="text-link" :href="previewUrl(item.id)" target="_blank" rel="noreferrer">预览</a>
          </div>
        </article>
        <p v-if="!(state.knowledge.results || []).length" class="muted">暂无检索结果，请确认公开源已采集或调整关键词。</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue';
import { authenticatedUrl } from '../api/client';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const answerSections = computed(() => {
  const sections = props.state.knowledgeAnswer?.sections;
  if (Array.isArray(sections) && sections.length) return sections;
  const answer = props.state.knowledgeAnswer?.answer || '';
  return [{
    title: '回答',
    items: answer.split(/\n+|(?<=[。！？；])/).map(item => item.trim()).filter(Boolean)
  }];
});

function previewUrl(id) {
  return authenticatedUrl(`/api/knowledge/preview/${id}`);
}

function categoryClass(category) {
  if (category === '政策法规') return 'high';
  if (category === '内部知识库') return 'mid';
  return 'low';
}
</script>
