<template>
  <div class="space-y-6 text-slate-200">
    <div class="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
      <div v-for="item in kpis" :key="item.label" class="hud-card p-4 flex items-center space-x-4 cursor-pointer" @click="go(item.route)">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div :class="['w-12 h-12 rounded-lg border flex items-center justify-center', item.shell]">
          <Icon :icon="item.icon" :class="['text-2xl', item.color]" />
        </div>
        <div>
          <p class="text-xs text-slate-500 font-medium">{{ item.label }}</p>
          <p class="text-2xl font-bold font-mono text-white">{{ item.value }}</p>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 xl:col-span-3 flex flex-col gap-6">
        <div class="hud-card p-4 min-h-[310px]">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-sm font-bold border-b border-border pb-2 mb-4 flex items-center">
            <Icon icon="lucide:list-ordered" class="mr-2 text-primary" />
            风险排行榜
          </h3>
          <div class="space-y-3">
            <div v-for="(risk, index) in topRisks" :key="risk.name" class="flex items-center justify-between p-2 rounded bg-white/5 border border-white/5">
              <span :class="['w-6 h-6 flex items-center justify-center text-xs font-bold rounded', index === 0 ? 'bg-danger/20 text-danger' : index < 3 ? 'bg-warning/20 text-warning' : 'bg-primary/20 text-primary']">
                {{ String(index + 1).padStart(2, '0') }}
              </span>
              <span class="flex-1 ml-3 text-xs truncate">{{ risk.name }}</span>
              <span :class="['px-2 py-0.5 rounded text-[10px] font-bold', risk.levelClass]">{{ risk.level }}</span>
            </div>
          </div>
        </div>

        <div class="hud-card p-4">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-sm font-bold mb-4">原材料风险指数</h3>
          <div class="space-y-4">
            <div v-for="item in materialRisks" :key="item.name">
              <div class="flex justify-between text-[10px] mb-1">
                <span>{{ item.name }}</span>
                <span :class="item.color">{{ item.value }}%</span>
              </div>
              <div class="h-1.5 bg-white/10 rounded-full overflow-hidden">
                <div :class="['h-full', item.bar]" :style="{ width: `${item.value}%` }"></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="col-span-12 xl:col-span-6 hud-card relative overflow-hidden flex flex-col min-h-[520px]">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="scanline"></div>
        <div class="flex items-center justify-between p-4 pb-0 z-10">
          <h3 class="text-sm font-bold">全球风险热点分布</h3>
          <button class="text-[10px] text-primary hover:underline" @click="go('/risk/gis')">打开完整 GIS</button>
        </div>
        <div ref="mapChartRef" class="flex-1 w-full min-h-0"></div>
      </div>

      <div class="col-span-12 xl:col-span-3 flex flex-col gap-6">
        <div class="hud-card p-4 flex flex-col min-h-[310px]">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-sm font-bold mb-3 flex items-center">
            <Icon icon="lucide:sparkles" class="mr-2 text-accent" />
            AI 风险摘要
          </h3>
          <div class="bg-accent/5 border border-accent/20 rounded-lg p-3 text-xs leading-relaxed text-slate-300 italic mb-4">
            根据过去24小时数据分析，东南亚地区的台风路径可能对二级供应商物流造成 48-72 小时的延迟。建议立即启动华南备选仓储计划，并关注锂矿价格波动。
          </div>
          <h3 class="text-xs font-bold mb-2 text-slate-400">实时动态</h3>
          <div class="flex-1 space-y-3 text-[10px]">
            <div v-for="feed in feeds" :key="feed.time" :class="['border-l-2 pl-3 py-1', feed.border]">
              <p class="text-slate-400">{{ feed.time }} - 来源: {{ feed.source }}</p>
              <p class="text-white font-medium">{{ feed.title }}</p>
            </div>
          </div>
        </div>

        <div class="hud-card p-4 h-[210px] flex flex-col">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-xs font-bold mb-2">近 30 天主要风险维数趋势</h3>
          <div ref="trendChartRef" class="flex-1 w-full min-h-0"></div>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <div class="hud-card p-4 xl:col-span-2">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-4">核心供应链段态模拟</h3>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-4">
          <div v-for="node in chainNodes" :key="node.name" class="rounded border border-white/5 bg-white/5 p-3 text-xs">
            <div class="flex items-center justify-between">
              <span class="font-bold text-white">{{ node.name }}</span>
              <span :class="node.color">{{ node.status }}</span>
            </div>
            <p class="mt-2 text-[10px] text-slate-500 leading-relaxed">{{ node.desc }}</p>
          </div>
        </div>
      </div>

      <div class="hud-card p-4">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-4">快捷处置</h3>
        <div class="grid grid-cols-2 gap-3">
          <button v-for="action in actions" :key="action.label" class="rounded border border-primary/20 bg-primary/10 px-3 py-3 text-xs text-primary hover:bg-primary/20" @click="go(action.route)">
            <Icon :icon="action.icon" class="mx-auto mb-1 text-lg" />
            {{ action.label }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup name="RiskDashboard" lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { useRouter } from 'vue-router';
import { Icon } from '@iconify/vue';
import * as echarts from 'echarts';

const router = useRouter();

const kpis = [
  { label: '总风险事件', value: '1,284', icon: 'lucide:alert-triangle', color: 'text-danger', shell: 'bg-danger/10 border-danger/30', route: '/risk/alert' },
  { label: '今日新增', value: '42', icon: 'lucide:plus-circle', color: 'text-warning', shell: 'bg-warning/10 border-warning/30', route: '/risk/alert' },
  { label: '已处理', value: '956', icon: 'lucide:check-circle', color: 'text-success', shell: 'bg-success/10 border-success/30', route: '/risk/detail' },
  { label: '处理率', value: '74.5%', icon: 'lucide:activity', color: 'text-primary', shell: 'bg-primary/10 border-primary/30', route: '/risk/analysis' }
];

const topRisks = [
  { name: '东南亚物流中断风险', level: '高危', levelClass: 'bg-danger/20 text-danger' },
  { name: '芯片半导体供应短缺', level: '中危', levelClass: 'bg-warning/20 text-warning' },
  { name: '原材料价格波动预警', level: '中危', levelClass: 'bg-warning/20 text-warning' },
  { name: '合规性审查异常', level: '低危', levelClass: 'bg-primary/20 text-primary' },
  { name: '供应商财务波动风险', level: '低危', levelClass: 'bg-primary/20 text-primary' }
];

const materialRisks = [
  { name: '锂电池原材料', value: 88, color: 'text-danger', bar: 'bg-danger' },
  { name: '稀有金属', value: 62, color: 'text-warning', bar: 'bg-warning' },
  { name: '高分子材料', value: 34, color: 'text-primary', bar: 'bg-primary' }
];

const feeds = [
  { time: '14:20', source: '路透社', title: '苏伊士运河出现短暂拥堵，影响欧洲线航运', border: 'border-primary' },
  { time: '12:05', source: '系统监控', title: '二级供应商财务评分下调至 CCC+', border: 'border-danger' },
  { time: '09:48', source: '港口 API', title: '新加坡转运节点等待时间升至 36 小时', border: 'border-warning' }
];

const chainNodes = [
  { name: '矿产来源', status: '88%', color: 'text-danger', desc: '锂矿价格波动与出口管制叠加，原材料补货成本上升。' },
  { name: '封测供应商', status: '62%', color: 'text-warning', desc: '东南亚分拨延迟导致封测产线排产缓冲不足。' },
  { name: '物流通道', status: '95%', color: 'text-danger', desc: '港口拥堵和天气扰动构成短期高危链路。' },
  { name: '终端交付', status: '34%', color: 'text-primary', desc: '华南备选仓储可承接部分延期订单。' }
];

const actions = [
  { label: '上传数据', icon: 'lucide:upload-cloud', route: '/risk/upload' },
  { label: 'AI 分析', icon: 'lucide:bar-chart-3', route: '/risk/analysis' },
  { label: '生成报告', icon: 'lucide:file-text', route: '/risk/report' },
  { label: '预警中心', icon: 'lucide:bell', route: '/risk/alert' }
];

const mapChartRef = ref<HTMLDivElement | null>(null);
const trendChartRef = ref<HTMLDivElement | null>(null);
const mapChart = shallowRef<echarts.ECharts | null>(null);
const trendChart = shallowRef<echarts.ECharts | null>(null);

const go = (path: string) => router.push(path);

const initCharts = () => {
  if (mapChartRef.value) {
    mapChart.value = echarts.init(mapChartRef.value, 'dark');
    mapChart.value.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item' },
      grid: { top: 20, bottom: 20, left: 20, right: 20 },
      xAxis: { show: false, min: 0, max: 100 },
      yAxis: { show: false, min: 0, max: 100 },
      series: [
        {
          type: 'graph',
          layout: 'none',
          coordinateSystem: 'cartesian2d',
          symbolSize: (value: number[]) => value[2],
          data: [
            { name: '苏伊士港口拥堵', value: [22, 62, 28], itemStyle: { color: '#ef4444' } },
            { name: '深圳盐田物流节点', value: [63, 42, 20], itemStyle: { color: '#3b82f6' } },
            { name: '新加坡物流劳资纠纷', value: [76, 30, 34], itemStyle: { color: '#ef4444' } },
            { name: '华南备选仓储', value: [55, 18, 18], itemStyle: { color: '#22c55e' } }
          ],
          links: [
            { source: '苏伊士港口拥堵', target: '深圳盐田物流节点' },
            { source: '新加坡物流劳资纠纷', target: '深圳盐田物流节点' },
            { source: '华南备选仓储', target: '深圳盐田物流节点' }
          ],
          label: { show: true, formatter: '{b}', position: 'top', fontSize: 10, color: '#cbd5e1' },
          lineStyle: { color: 'rgba(59, 130, 246, 0.35)', width: 2, curveness: 0.15 },
          emphasis: { focus: 'adjacency' }
        }
      ]
    });
  }

  if (trendChartRef.value) {
    trendChart.value = echarts.init(trendChartRef.value, 'dark');
    trendChart.value.setOption({
      backgroundColor: 'transparent',
      grid: { top: 15, bottom: 20, left: 30, right: 10 },
      xAxis: { type: 'category', data: ['5-01', '5-05', '5-10', '5-15', '5-20'], axisLine: { show: false } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } } },
      series: [
        {
          data: [35, 48, 42, 60, 78],
          type: 'line',
          smooth: true,
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(59, 130, 246, 0.4)' },
              { offset: 1, color: 'rgba(59, 130, 246, 0)' }
            ])
          },
          lineStyle: { color: '#3b82f6', width: 2 }
        }
      ]
    });
  }
};

const handleResize = () => {
  mapChart.value?.resize();
  trendChart.value?.resize();
};

onMounted(() => {
  initCharts();
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  mapChart.value?.dispose();
  trendChart.value?.dispose();
});
</script>
