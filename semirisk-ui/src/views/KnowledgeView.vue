<template>
  <section class="grid">
    <div class="toolbar">
      <input v-model="state.knowledgeQuery" class="input" placeholder="Ctrl+K 搜索知识库" @keydown.enter="actions.searchKnowledge" />
      <button class="btn" @click="actions.searchKnowledge">检索</button>
    </div>
    <div class="cards">
      <article v-for="item in state.knowledge.results || []" :key="item.id" class="card-option">
        <b>{{ item.title }}</b><p>{{ item.summary }}</p><p class="success">Similarity: {{ item.similarity }}%</p>
      </article>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
