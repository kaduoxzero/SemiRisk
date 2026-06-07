<template>
  <div class="risk-page space-y-6">
    <div class="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
      <div v-for="item in kpiCards" :key="item.label" class="hud-card p-4 flex items-center gap-4 cursor-pointer" @click="go(item.route)">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div :class="['w-12 h-12 rounded-lg border flex items-center justify-center', item.shell]">
          <span :class="['text-lg font-bold', item.color]">{{ item.mark }}</span>
        </div>
        <div>
          <p class="text-xs text-slate-500">{{ item.label }}</p>
          <p class="text-2xl font-bold font-mono text-white">{{ item.value }}</p>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 xl:col-span-3 hud-card p-4">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-sm font-bold mb-4 flex items-center">
          <span class="mr-2 text-primary">#</span>
          风险排行榜
        </h3>
        <div v-if="topRisks.length === 0" class="py-12 text-center text-xs text-slate-500">暂无真实风险事件</div>
        <div v-else class="space-y-3">
          <div v-for="(risk, index) in topRisks" :key="risk.eventId" class="flex items-center justify-between rounded bg-white/5 p-2 text-xs">
            <span class="w-6 h-6 rounded bg-primary/20 text-primary flex items-center justify-center font-bold">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="ml-3 flex-1 truncate">{{ risk.eventTitle }}</span>
            <span :class="badgeClass(risk.riskLevel)">{{ risk.riskLevel || '未分级' }}</span>
          </div>
        </div>
      </div>

      <div class="col-span-12 xl:col-span-6 hud-card relative min-h-[460px] p-4">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="scanline"></div>
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-bold">全球风险热点分布</h3>
          <button class="text-xs text-primary hover:underline" @click="go('/risk/gis')">打开 GIS</button>
        </div>
        <div ref="mapRef" class="h-[400px]"></div>
      </div>

      <div class="col-span-12 xl:col-span-3 flex flex-col gap-6">
        <div class="hud-card p-4 min-h-[220px]">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-sm font-bold mb-3 flex items-center">
            <span class="mr-2 text-accent">AI</span>
            AI 分析状态
          </h3>
          <p class="text-xs leading-relaxed text-slate-300">
            AI 报告仅基于数据库中的真实风险事件生成。当前可分析事件数：<span class="font-mono text-primary">{{ kpis.total }}</span>。
          </p>
          <button class="mt-4 w-full rounded bg-primary px-3 py-2 text-xs font-bold text-white" @click="go('/risk/report')">生成真实数据研判报告</button>
        </div>
        <div class="hud-card p-4 h-[220px]">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-xs font-bold mb-2">近 30 天风险趋势</h3>
          <div ref="trendRef" class="h-[170px]"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup name="RiskDashboard" lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { useRouter } from 'vue-router';
import * as echarts from 'echarts';
import { getGisNodes, getRiskKpis, getRiskTrend, listRiskEvents } from '@/api/risk/event';

const router = useRouter();
const kpis = ref<any>({ total: 0, today: 0, resolved: 0, resolveRate: 0, currentRiskIndex: 0 });
const topRisks = ref<any[]>([]);
const gisNodes = ref<any[]>([]);
const trendRows = ref<any[]>([]);
const mapRef = ref<HTMLDivElement | null>(null);
const trendRef = ref<HTMLDivElement | null>(null);
const mapChart = shallowRef<echarts.ECharts | null>(null);
const trendChart = shallowRef<echarts.ECharts | null>(null);

const kpiCards = computed(() => [
  { label: '总风险事件', value: kpis.value.total, mark: '!', color: 'text-danger', shell: 'bg-danger/10 border-danger/30', route: '/risk/alert' },
  { label: '今日新增', value: kpis.value.today, mark: '+', color: 'text-warning', shell: 'bg-warning/10 border-warning/30', route: '/risk/alert' },
  { label: '已处理', value: kpis.value.resolved, mark: '✓', color: 'text-success', shell: 'bg-success/10 border-success/30', route: '/risk/alert' },
  { label: '当前风险指数', value: kpis.value.currentRiskIndex, mark: '%', color: 'text-primary', shell: 'bg-primary/10 border-primary/30', route: '/risk/analysis' }
]);

const go = (path: string) => router.push(path);

const badgeClass = (level: string) => {
  if (level === 'CRITICAL') return 'rounded bg-danger/20 px-2 py-0.5 text-[10px] font-bold text-danger';
  if (level === 'WARNING') return 'rounded bg-warning/20 px-2 py-0.5 text-[10px] font-bold text-warning';
  return 'rounded bg-primary/20 px-2 py-0.5 text-[10px] font-bold text-primary';
};

const loadData = async () => {
  const [kpiRes, eventRes, gisRes, trendRes] = await Promise.all([
    getRiskKpis(),
    listRiskEvents({ pageNum: 1, pageSize: 5 }),
    getGisNodes(),
    getRiskTrend()
  ]);
  kpis.value = kpiRes.data || {};
  topRisks.value = eventRes.rows || [];
  gisNodes.value = gisRes.data || [];
  trendRows.value = trendRes.data || [];
  await nextTick();
  renderCharts();
};

const renderCharts = () => {
  if (mapRef.value) {
    mapChart.value ||= echarts.init(mapRef.value, 'dark');
    mapChart.value.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item', formatter: (p: any) => p.data.name },
      grid: { top: 30, bottom: 30, left: 30, right: 30 },
      xAxis: { show: false, min: -180, max: 180 },
      yAxis: { show: false, min: -90, max: 90 },
      series: [{ type: 'scatter', data: gisNodes.value.map((n) => ({ name: n.eventTitle, value: [n.longitude, n.latitude, Number(n.riskScore || 20)] })), symbolSize: (v: any) => Math.max(10, Number(v[2]) / 2), itemStyle: { color: '#ef4444' } }]
    });
  }
  if (trendRef.value) {
    trendChart.value ||= echarts.init(trendRef.value, 'dark');
    trendChart.value.setOption({
      backgroundColor: 'transparent',
      grid: { top: 20, bottom: 24, left: 34, right: 12 },
      xAxis: { type: 'category', data: trendRows.value.map((r) => r.date) },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } } },
      series: [{ type: 'line', smooth: true, data: trendRows.value.map((r) => r.riskScore), lineStyle: { color: '#3b82f6' }, areaStyle: { color: 'rgba(59,130,246,0.18)' } }]
    });
  }
};

const resize = () => {
  mapChart.value?.resize();
  trendChart.value?.resize();
};

onMounted(() => {
  loadData();
  window.addEventListener('resize', resize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize);
  mapChart.value?.dispose();
  trendChart.value?.dispose();
});
</script>
