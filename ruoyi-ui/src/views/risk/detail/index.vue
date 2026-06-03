<template>
  <div class="p-6 space-y-6 text-slate-200">
    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 lg:col-span-8 hud-card p-4 space-y-4">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold border-b border-border pb-1 text-primary">受损风险事件基本面</h3>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
          <div class="flex justify-between border-b border-white/5 pb-1"><span class="text-slate-500">风险归类</span><span class="text-white font-mono">EV-LOG-2026-0520</span></div>
          <div class="flex justify-between border-b border-white/5 pb-1"><span class="text-slate-500">地理坐标</span><span>新加坡海域节点 (091X)</span></div>
          <div class="flex justify-between border-b border-white/5 pb-1"><span class="text-slate-500">预测延误范围</span><span>4.5至6.2个运行日</span></div>
          <div class="flex justify-between border-b border-white/5 pb-1"><span class="text-slate-500">受阻节点数</span><span class="text-danger font-bold">12家一级合作厂</span></div>
        </div>
      </div>

      <div class="col-span-12 lg:col-span-4 hud-card p-4 space-y-2 bg-accent/5">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold text-accent">AI 应急策略组建议</h3>
        <p class="text-[10px] text-slate-400 leading-normal">
          1. 建议将未运出的3批封测原料在巴生港换单转运。<br>
          2. 对受制约芯片备货追加 15% 生产保障系数。
        </p>
      </div>
    </div>

    <!-- 拓扑关系 -->
    <div class="hud-card p-4 h-[350px]">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <h3 class="text-xs font-bold mb-2">多节点层级渗透网络拓扑</h3>
      <div ref="topologyChartRef" class="w-full h-full"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, shallowRef } from 'vue'
import * as echarts from 'echarts'

const topologyChartRef = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)

const initTopology = () => {
  if (!topologyChartRef.value) return
  chart.value = echarts.init(topologyChartRef.value, 'dark')

  chart.value.setOption({
    backgroundColor: 'transparent',
    series: [{
      type: 'graph',
      layout: 'force',
      data: [
        { name: '阻断源: 新加坡劳资纠纷', symbolSize: 35, itemStyle: { color: '#ef4444' } },
        { name: '分拨仓 A', symbolSize: 22, itemStyle: { color: '#eab308' } },
        { name: '承运商 B', symbolSize: 22, itemStyle: { color: '#eab308' } },
        { name: '深圳总装总厂', symbolSize: 30, itemStyle: { color: '#3b82f6' } }
      ],
      links: [
        { source: '阻断源: 新加坡劳资纠纷', target: '分拨仓 A' },
        { source: '阻断源: 新加坡劳资纠纷', target: '承运商 B' },
        { source: '分拨仓 A', target: '深圳总装总厂' },
        { source: '承运商 B', target: '深圳总装总厂' }
      ],
      roam: true,
      label: { show: true, fontSize: 9, color: '#94a3b8' },
      force: { repulsion: 150, edgeLength: 90 },
      lineStyle: { width: 1.5, color: 'rgba(255,255,255,0.1)' }
    }]
  })
}

const handleResize = () => chart.value?.resize()

onMounted(() => {
  initTopology()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart.value?.dispose()
})
</script>
