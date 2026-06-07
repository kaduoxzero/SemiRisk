<template>
  <div class="risk-page space-y-6">
    <div class="hud-card p-4 flex flex-wrap items-center gap-3">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <input v-model="queryText" class="cyber-input flex-1 px-3 py-2 text-xs" placeholder="输入关键词检索真实知识库" @keyup.enter="search" />
      <button class="risk-action px-4 py-2 text-xs" @click="search">检索</button>
      <button class="risk-action px-4 py-2 text-xs" @click="showCreate = !showCreate">{{ showCreate ? '收起' : '新增知识' }}</button>
    </div>
    <div v-if="showCreate" class="hud-card p-4">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <div class="grid gap-3 md:grid-cols-4">
        <input v-model="form.title" class="cyber-input px-3 py-2 text-xs md:col-span-2" placeholder="标题" />
        <input v-model="form.category" class="cyber-input px-3 py-2 text-xs" placeholder="分类" />
        <input v-model="form.keywords" class="cyber-input px-3 py-2 text-xs" placeholder="关键词" />
        <input v-model="form.sourceName" class="cyber-input px-3 py-2 text-xs" placeholder="来源" />
        <textarea v-model="form.content" class="cyber-textarea p-3 text-xs md:col-span-3" placeholder="知识内容"></textarea>
        <button class="risk-action px-4 py-2 text-xs" :disabled="saving" @click="saveKnowledge">{{ saving ? '写入中...' : '写入知识库' }}</button>
      </div>
    </div>
    <div class="grid gap-4">
      <div v-for="item in rows" :key="item.knowledgeId" class="hud-card p-4">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-bold text-white">{{ item.title }}</h3>
          <span class="text-xs text-primary">{{ item.category }}</span>
        </div>
        <p class="mt-2 text-xs leading-6 text-slate-300">{{ item.content }}</p>
        <p class="mt-3 text-[10px] text-slate-500">来源：{{ item.sourceName || '-' }} / 关键词：{{ item.keywords || '-' }}</p>
      </div>
      <div v-if="rows.length === 0" class="hud-card p-12 text-center text-sm text-slate-500">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        暂无真实知识库记录
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { addKnowledge, listKnowledge, searchKnowledgeBase } from '@/api/risk/enterprise';

const queryText = ref('');
const rows = ref<any[]>([]);
const showCreate = ref(false);
const saving = ref(false);
const form = ref({ title: '', category: '', keywords: '', sourceName: '', content: '', status: 'ACTIVE' });

const search = async () => {
  if (queryText.value.trim()) {
    const res = await searchKnowledgeBase(queryText.value);
    rows.value = res.data || [];
  } else {
    const res = await listKnowledge({ pageNum: 1, pageSize: 50 });
    rows.value = res.rows || [];
  }
};

const saveKnowledge = async () => {
  if (!form.value.title || !form.value.content) {
    ElMessage.warning('请填写标题和内容');
    return;
  }
  saving.value = true;
  try {
    await addKnowledge(form.value);
    ElMessage.success('知识条目已写入真实业务表');
    form.value = { title: '', category: '', keywords: '', sourceName: '', content: '', status: 'ACTIVE' };
    await search();
  } finally {
    saving.value = false;
  }
};

onMounted(search);
</script>
