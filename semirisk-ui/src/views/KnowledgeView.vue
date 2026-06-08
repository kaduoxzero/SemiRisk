<template>
  <section class="grid knowledge-layout">
    <div class="panel ai-agent-panel tall-agent">
      <h3>AI 知识库智能体</h3>
      <div class="toolbar">
        <input
          v-model="state.knowledgeQuestion"
          class="input wide"
          placeholder="输入供应链风险问题，如：当前半导体出口管制最新动态？"
          @keydown.enter="actions.askKnowledge"
        />
        <button class="btn" :disabled="state.knowledgeLoading" @click="actions.askKnowledge">
          {{ state.knowledgeLoading ? '生成中…' : '提问' }}
        </button>
      </div>

      <div v-if="state.knowledgeLoading" class="knowledge-loading">
        <div class="loading-dots"><span /><span /><span /></div>
        <span class="muted">正在检索知识库并调用 AI…</span>
      </div>

      <div v-else-if="state.knowledgeAnswer" class="agent-answer">
        <!-- 回答正文 -->
        <div class="structured-answer">
          <section v-for="section in answerSections" :key="section.title" class="answer-section">
            <h4>{{ section.title }}</h4>
            <ul>
              <li v-for="item in section.items" :key="item">{{ item }}</li>
            </ul>
          </section>
        </div>

        <!-- 推理链路 -->
        <div v-if="(state.knowledgeAnswer.trace || []).length" class="agent-trace">
          <span v-for="step in state.knowledgeAnswer.trace" :key="step">{{ step }}</span>
        </div>

        <!-- 建议操作 -->
        <div v-if="(state.knowledgeAnswer.nextActions || []).length" class="next-actions">
          <span v-for="item in state.knowledgeAnswer.nextActions" :key="item">→ {{ item }}</span>
        </div>

        <!-- AI 调用状态 -->
        <div class="usage-row">
          <span class="badge" :class="state.knowledgeAnswer.aiCalled !== false ? 'low' : 'mid'">
            {{ state.knowledgeAnswer.aiCalled !== false ? 'AI · ' + (state.knowledgeAnswer.model || 'DeepSeek') : '本地检索' }}
          </span>
          <span v-if="state.knowledgeAnswer.modelStatus" class="muted">{{ state.knowledgeAnswer.modelStatus }}</span>
          <span v-if="state.knowledgeAnswer.usage?.totalTokens" class="muted">{{ state.knowledgeAnswer.usage.totalTokens }} tokens</span>
        </div>

        <!-- 引用来源 -->
        <div v-if="(state.knowledgeAnswer.citations || []).length" class="citations-panel">
          <div class="toolbar compact-toolbar" style="margin-bottom:8px">
            <span class="muted" style="font-size:12px">引用来源 · {{ state.knowledgeAnswer.citations.length }} 条</span>
          </div>
          <div class="cards compact-cards citations-grid">
            <article v-for="item in state.knowledgeAnswer.citations" :key="item.id" class="card-option citation-card">
              <span class="badge low" style="font-size:11px">{{ item.source }}</span>
              <p>{{ item.title }}</p>
              <a v-if="item.sourceUrl" class="text-link" :href="item.sourceUrl" target="_blank" rel="noreferrer">原文 →</a>
            </article>
          </div>
        </div>
      </div>

      <p v-else class="muted knowledge-hint">
        输入问题后，智能体先检索本地知识库与公开源，再调用已配置 AI 生成回答，并附引用与推理链路。
      </p>
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
