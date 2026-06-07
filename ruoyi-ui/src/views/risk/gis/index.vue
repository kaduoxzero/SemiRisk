<template>
  <div class="risk-page">
    <div class="hud-card relative min-h-[640px] p-4">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <div class="scanline"></div>
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-base font-bold text-white">GIS 风险地图</h2>
        <span class="text-xs text-slate-500">仅展示带经纬度的真实风险事件</span>
      </div>
      <div ref="chartRef" class="h-[570px]"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import * as echarts from 'echarts';
import { getGisNodes } from '@/api/risk/event';

const rows = ref<any[]>([]);
const chartRef = ref<HTMLDivElement | null>(null);
const chart = shallowRef<echarts.ECharts | null>(null);

const loadData = async () => {
  const res = await getGisNodes();
  rows.value = res.data || [];
  await nextTick();
  render();
};

const render = () => {
  if (!chartRef.value) return;
  chart.value ||= echarts.init(chartRef.value, 'dark');
  chart.value.setOption({
    backgroundColor: 'transparent',
    tooltip: { formatter: (p: any) => `${p.data.name}<br/>风险分：${p.data.value[2]}` },
    grid: { top: 20, bottom: 20, left: 30, right: 30 },
    xAxis: { name: '经度', min: -180, max: 180, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } } },
    yAxis: { name: '纬度', min: -90, max: 90, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } } },
    series: [{
      type: 'scatter',
      data: rows.value.map((r) => ({ name: r.eventTitle, value: [r.longitude, r.latitude, Number(r.riskScore || 0)] })),
      symbolSize: (v: any) => Math.max(8, Number(v[2]) / 2),
      itemStyle: { color: '#ef4444', shadowBlur: 12, shadowColor: 'rgba(239,68,68,0.45)' },
      label: { show: true, formatter: '{b}', position: 'top', color: '#cbd5e1', fontSize: 10 }
    }]
  });
};

onMounted(loadData);
onBeforeUnmount(() => chart.value?.dispose());
</script>
