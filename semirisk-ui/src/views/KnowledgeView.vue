<template>
  <section class="grid knowledge-layout">
    <div class="panel ai-agent-panel tall-agent">
      <h3>AI 知识库智能体</h3>
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
  </section>
</template>

<script setup>
import { computed } from 'vue';

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
</script>
