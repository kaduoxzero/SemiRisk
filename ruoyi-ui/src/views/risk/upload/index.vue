<template>
  <div class="risk-page space-y-6">
    <div class="hud-card p-6">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <h2 class="text-base font-bold text-white">真实数据上传</h2>
      <div class="mt-5 grid gap-4 lg:grid-cols-3">
        <label class="lg:col-span-2 rounded border border-dashed border-primary/30 bg-white/5 p-6 text-center cursor-pointer hover:bg-primary/10">
          <div class="mx-auto mb-3 text-4xl text-primary">↑</div>
          <input class="hidden" type="file" accept=".csv" @change="selectFile" />
          <div class="text-sm text-white">{{ file?.name || '选择真实业务 CSV 文件' }}</div>
          <div class="mt-2 text-[11px] text-slate-500">字段顺序：企业名称,信用代码,行业,地区,供应链角色,事件标题,分类,等级,状态,来源,风险分,经度,纬度,描述,发生时间</div>
        </label>
        <div class="rounded border border-border bg-white/5 p-4 text-xs leading-6 text-slate-300">
          <div>导入企业数：<span class="font-mono text-primary">{{ result.enterpriseRows ?? '-' }}</span></div>
          <div>导入事件数：<span class="font-mono text-primary">{{ result.eventRows ?? '-' }}</span></div>
          <button class="mt-4 w-full risk-action px-4 py-2 font-bold disabled:opacity-40" :disabled="!file || loading" @click="upload">
            {{ loading ? '上传中...' : '写入真实库' }}
          </button>
          <button class="mt-2 w-full text-primary hover:underline" @click="downloadSample">下载 CSV 样例</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { uploadRunningReport } from '@/api/risk/enterprise';

const file = ref<File | null>(null);
const loading = ref(false);
const result = ref<any>({});

const selectFile = (event: Event) => {
  const selected = (event.target as HTMLInputElement).files?.[0] || null;
  if (selected && !selected.name.toLowerCase().endsWith('.csv')) {
    ElMessage.warning('仅支持 CSV 文件');
    file.value = null;
    return;
  }
  file.value = selected;
};

const upload = async () => {
  if (!file.value) return;
  loading.value = true;
  try {
    const res = await uploadRunningReport(file.value);
    result.value = res.data || {};
    ElMessage.success('真实数据已导入数据库');
  } finally {
    loading.value = false;
  }
};

const downloadSample = () => {
  const content = [
    'enterprise,creditCode,industry,region,role,eventTitle,category,riskLevel,status,source,riskScore,longitude,latitude,description,occurredAt',
    '华东精密制造有限公司,91310000MA1K000001,高端装备制造,上海,核心供应商,关键轴承批次延期交付,履约物流,CRITICAL,UNRESOLVED,供应商周报,79.12,121.473701,31.230416,核心轴承批次延迟5天,2026-06-03 10:30:00'
  ].join('\n');
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = 'semirisk-upload-sample.csv';
  link.click();
  URL.revokeObjectURL(link.href);
};
</script>
