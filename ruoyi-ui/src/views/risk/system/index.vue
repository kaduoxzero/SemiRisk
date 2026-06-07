<template>
  <div class="risk-page space-y-6">
    <div class="hud-card p-5">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <h2 class="text-base font-bold text-white">风险数据源管理</h2>
      <div class="mt-4 grid gap-3 md:grid-cols-5">
        <input v-model="form.sourceName" class="cyber-input px-3 py-2 text-xs" placeholder="数据源名称" />
        <input v-model="form.sourceType" class="cyber-input px-3 py-2 text-xs" placeholder="类型" />
        <input v-model="form.accessMode" class="cyber-input px-3 py-2 text-xs" placeholder="接入方式" />
        <input v-model="form.endpoint" class="cyber-input px-3 py-2 text-xs" placeholder="地址/说明" />
        <button class="rounded bg-primary px-4 py-2 text-xs font-bold text-white" @click="save">写入数据源</button>
      </div>
    </div>

    <div class="hud-card overflow-hidden">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <table class="risk-table">
        <thead><tr><th>名称</th><th>类型</th><th>接入方式</th><th>地址</th><th>状态</th><th>更新时间</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="item.sourceId">
            <td>{{ item.sourceName }}</td>
            <td>{{ item.sourceType }}</td>
            <td>{{ item.accessMode }}</td>
            <td>{{ item.endpoint }}</td>
            <td>{{ item.status }}</td>
            <td>{{ item.updateTime || item.createTime }}</td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="6" class="py-12 text-center text-slate-500">暂无真实数据源配置</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { addDataSource, listDataSources } from '@/api/risk/enterprise';

const rows = ref<any[]>([]);
const form = reactive({ sourceName: '', sourceType: '', accessMode: '', endpoint: '', status: 'ACTIVE' });

const loadData = async () => {
  const res = await listDataSources({ pageNum: 1, pageSize: 50 });
  rows.value = res.rows || [];
};

const save = async () => {
  await addDataSource(form);
  ElMessage.success('数据源已写入真实业务表');
  Object.assign(form, { sourceName: '', sourceType: '', accessMode: '', endpoint: '', status: 'ACTIVE' });
  loadData();
};

onMounted(loadData);
</script>
