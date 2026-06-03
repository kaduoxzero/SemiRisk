<template>
  <div class="p-6 space-y-6 text-slate-200">
    <div class="flex justify-between items-center">
      <h2 class="text-md font-semibold">AI 智能风险诊断屏</h2>
      <div class="flex bg-slate-800/80 rounded-lg p-1 border border-border">
        <button
          :class="['px-3 py-1 text-xs font-medium rounded-md transition-all', activeTimeline === '24h' ? 'bg-primary text-white shadow' : 'text-slate-400 hover:text-white']"
          @click="switchTimeline('24h')"
        >
          近24小时
        </button>
        <button
          :class="['px-3 py-1 text-xs font-medium rounded-md transition-all', activeTimeline === '7d' ? 'bg-primary text-white shadow' : 'text-slate-400 hover:text-white']"
          @click="switchTimeline('7d')"
        >
          近7天
        </button>
      </div>
    </div>

    <!-- 顶部排版 -->
    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 md:col-span-4 hud-card p-6 flex flex-col items-center justify-center min-h-[220px]">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-2">综合抗险健康水位</h3>
        <div ref="gaugeRef" class="w-40 h-40"></div>
      </div>

      <div class="col-span-12 md:col-span-8 hud-card p-6 flex flex-col justify-center">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-2 flex items-center text-accent">
          <Icon icon="lucide:sparkles" class="mr-1 animate-pulse" />核心推演报告
        </h3>
        <p class="text-xs text-slate-300 leading-relaxed">
          检测到外部物流通道故障对下游组装产生<span class="text-danger font-bold ml-1 mr-1">强关联效应</span>。由于高分子组件与晶圆封装产能错配严重，若不及时替换备选路线，部分客户交期将发生滞后风险。
        </p>
      </div>
    </div>

    <!-- 饼图 & 推理链 -->
    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 md:col-span-4 hud-card p-4 h-[280px]">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-2">事件类型占比</h3>
        <div ref="pieRef" class="w-full h-48"></div>
      </div>

      <div class="col-span-12 md:col-span-8 hud-card p-4 flex flex-col h-[280px]">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-4">推理传导链路模型</h3>
        <div class="flex-1 flex flex-col md:flex-row items-center justify-around relative px-4 gap-4">
          <div class="p-2.5 border border-danger/30 rounded text-center text-[10px] bg-danger/10 w-full md:w-36">
            <p class="font-bold">异常节点发现</p>
            <p class="text-slate-400 mt-1">新加坡罢工爆发</p>
          </div>
          <Icon icon="lucide:arrow-right" class="text-slate-500 text-lg animate-pulse rotate-90 md:rotate-0" />
          <div class="p-2.5 border border-warning/30 rounded text-center text-[10px] bg-warning/10 w-full md:w-36">
            <p class="font-bold">传导阻断路径</p>
            <p class="text-slate-400 mt-1">封测商A缺货</p>
          </div>
          <Icon icon="lucide:arrow-right" class="text-slate-500 text-lg animate-pulse rotate-90 md:rotate-0" />
          <div class="p-2.5 border border-success/30 rounded text-center text-[10px] bg-success/10 w-full md:w-36">
            <p class="font-bold">智能纠正对策</p>
            <p class="text-slate-400 mt-1">切换备选供应商C</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, shallowRef } from 'vue'
import { Icon } from '@iconify/vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'

const activeTimeline = ref('24h')

const gaugeRef = ref<HTMLDivElement | null>(null)
const pieRef = ref<HTMLDivElement | null>(null)

const gaugeChart = shallowRef<echarts.ECharts | null>(null)
const pieChart = shallowRef<echarts.ECharts | null>(null)

const initCharts = () => {
  if (gaugeRef.value) {
    gaugeChart.value = echarts.init(gaugeRef.value, 'dark')
    gaugeChart.value.setOption({
      backgroundColor: 'transparent',
      series: [{
        type: 'gauge',
        startAngle: 180,
        endAngle: 0,
        min: 0,
        max: 100,
        axisLine: { lineStyle: { width: 6, color: [[0.3, '#3b82f6'], [0.7, '#eab308'], [1, '#ef4444']] } },
        pointer: { width: 3 },
        detail: { show: false },
        data: [{ value: 82 }]
      }]
    })
  }

  if (pieRef.value) {
    pieChart.value = echarts.init(pieRef.value, 'dark')
    pieChart.value.setOption({
      backgroundColor: 'transparent',
      series: [{
        type: 'pie',
        radius: ['40%', '60%'],
        data: [
          { value: 42, name: '物流运输', itemStyle: { color: '#ef4444' } },
          { value: 28, name: '财务信用', itemStyle: { color: '#eab308' } },
          { value: 30, name: '其他合规', itemStyle: { color: '#3b82f6' } }
        ],
        label: { show: true, fontSize: 10, color: '#94a3b8' }
      }]
    })
  }
}

const switchTimeline = (type: string) => {
  activeTimeline.value = type
  const score = type === '24h' ? 82 : 45
  gaugeChart.value?.setOption({
    series: [{ data: [{ value: score }] }]
  })
  ElMessage.info(`已切换至 ${type} 时间跨度分析`)
}

const handleResize = () => {
  gaugeChart.value?.resize()
  pieChart.value?.resize()
}

onMounted(() => {
  initCharts()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  gaugeChart.value?.dispose()
  pieChart.value?.dispose()
})
</script>
