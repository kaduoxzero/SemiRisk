<template>
  <div class="risk-page space-y-6">
    <div class="hud-card p-5">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <div class="grid gap-3 md:grid-cols-4">
        <select v-model="form.templateType" class="cyber-select px-3 py-2 text-xs">
          <option>供应链风险研判报告</option>
          <option>供应商财务风险报告</option>
          <option>物流通道风险报告</option>
        </select>
        <input v-model="form.dateRange" class="cyber-input px-3 py-2 text-xs" placeholder="分析区间" />
        <select v-model="form.format" class="cyber-select px-3 py-2 text-xs">
          <option value="markdown">Markdown</option>
          <option value="text">Text</option>
        </select>
        <button class="risk-action px-4 py-2 text-xs" :disabled="loading" @click="generate">
          {{ loading ? '生成中...' : '调用 Python AI 生成' }}
        </button>
      </div>
      <div class="mt-3 flex items-center justify-between text-xs text-slate-500">
        <span>未配置外部模型密钥时，Python AI 服务会使用本地规则引擎生成可交付报告。</span>
        <button class="text-primary hover:underline" @click="loadData">刷新报告列表</button>
      </div>
    </div>

    <div class="hud-card overflow-hidden">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <table class="risk-table">
        <thead><tr><th>标题</th><th>模板</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="item.reportId">
            <td>{{ item.reportTitle }}</td>
            <td>{{ item.templateType }}</td>
            <td><span :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td>
            <td>{{ item.createTime }}</td>
            <td>
              <button class="text-primary hover:underline" @click="current = item">查看</button>
              <button class="ml-3 text-success hover:underline" @click="download(item)">下载</button>
            </td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="5" class="py-12 text-center text-slate-500">暂无真实报告记录</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="current" class="hud-card p-5">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h3 class="text-sm font-bold text-white">{{ current.reportTitle }}</h3>
        <button class="risk-action px-3 py-1 text-xs" @click="download(current)">下载报告</button>
      </div>
      <pre class="whitespace-pre-wrap text-xs leading-6 text-slate-300">{{ current.content || current.errorMessage }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { generateAiReport } from '@/api/risk/event';
import { listReports } from '@/api/risk/enterprise';

const form = reactive({ templateType: '供应链风险研判报告', dateRange: '全部真实数据', format: 'markdown' });
const rows = ref<any[]>([]);
const current = ref<any>(null);
const loading = ref(false);

const loadData = async () => {
  const res = await listReports({ pageNum: 1, pageSize: 20 });
  rows.value = res.rows || [];
};

const generate = async () => {
  loading.value = true;
  try {
    const res = await generateAiReport(form);
    current.value = res.data;
    ElMessage.success('AI 报告任务已写入数据库');
    await loadData();
  } finally {
    loading.value = false;
  }
};

const statusText = (status: string) => ({ GENERATING: '生成中', FINISHED: '已完成', FAILED: '失败' }[status] || status || '-');
const statusClass = (status: string) => {
  if (status === 'FINISHED') return 'rounded bg-success/20 px-2 py-0.5 text-[10px] font-bold text-success';
  if (status === 'FAILED') return 'rounded bg-danger/20 px-2 py-0.5 text-[10px] font-bold text-danger';
  return 'rounded bg-primary/20 px-2 py-0.5 text-[10px] font-bold text-primary';
};
const download = (report: any) => {
  const blob = new Blob([report.content || report.errorMessage || ''], { type: 'text/plain;charset=utf-8' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `${report.reportTitle || 'risk-report'}.${report.formatType === 'markdown' ? 'md' : 'txt'}`;
  link.click();
  URL.revokeObjectURL(link.href);
};

onMounted(loadData);
</script>
