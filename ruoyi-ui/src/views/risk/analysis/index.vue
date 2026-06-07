<template>
  <div class="risk-page space-y-6">
    <div class="grid gap-6 lg:grid-cols-3">
      <div class="hud-card p-5">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-sm font-bold text-white">真实事件总量</h3>
        <p class="mt-4 font-mono text-4xl font-bold text-primary">{{ kpis.total }}</p>
      </div>
      <div class="hud-card p-5">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-sm font-bold text-white">当前风险指数</h3>
        <p class="mt-4 font-mono text-4xl font-bold text-danger">{{ kpis.currentRiskIndex }}</p>
      </div>
      <div class="hud-card p-5">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-sm font-bold text-white">闭环率</h3>
        <p class="mt-4 font-mono text-4xl font-bold text-success">{{ kpis.resolveRate }}%</p>
      </div>
    </div>
    <div class="hud-card p-5">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <h3 class="mb-3 text-sm font-bold text-white">风险趋势分析</h3>
      <div ref="chartRef" class="h-[360px]"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import * as echarts from 'echarts';
import { getRiskKpis, getRiskTrend } from '@/api/risk/event';

const kpis = ref<any>({});
const trend = ref<any[]>([]);
const chartRef = ref<HTMLDivElement | null>(null);
const chart = shallowRef<echarts.ECharts | null>(null);

const loadData = async () => {
  const [k, t] = await Promise.all([getRiskKpis(), getRiskTrend()]);
  kpis.value = k.data || {};
  trend.value = t.data || [];
  await nextTick();
  render();
};

const render = () => {
  if (!chartRef.value) return;
  chart.value ||= echarts.init(chartRef.value, 'dark');
  chart.value.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: trend.value.map((r) => r.date) },
    yAxis: { type: 'value' },
    series: [
      { name: '风险指数', type: 'line', smooth: true, data: trend.value.map((r) => r.riskScore) },
      { name: '事件数', type: 'bar', data: trend.value.map((r) => r.count) }
    ]
  });
};

onMounted(loadData);
onBeforeUnmount(() => chart.value?.dispose());
</script>
