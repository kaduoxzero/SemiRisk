<template>
  <div class="p-6 space-y-6 text-slate-200">
    <!-- 顶栏检索区 -->
    <div class="hud-card p-4 flex flex-col md:flex-row gap-3 items-center justify-between">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <div class="flex items-center space-x-3 w-full md:w-auto">
        <input
          v-model="searchVal"
          class="cyber-input px-3 py-1.5 rounded text-xs w-full md:w-64"
          placeholder="搜索企业名称 (如 某半导体、某物流)..."
        />
        <button class="bg-primary px-4 py-1.5 rounded text-xs text-white font-bold shrink-0 hover:bg-blue-600 transition-colors" @click="performProfileSearch">
          检索画像
        </button>
      </div>
      <span class="text-[10px] text-slate-500 font-mono">若依系统联动认证</span>
    </div>

    <!-- 企业基本概况 -->
    <div class="hud-card p-6 flex flex-col md:flex-row items-center justify-between gap-4">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <div class="flex items-center space-x-4">
        <div class="w-12 h-12 rounded bg-gradient-to-br from-primary to-blue-600 flex items-center justify-center font-bold text-white text-lg">
          {{ profileShort }}
        </div>
        <div>
          <h2 class="text-sm font-bold text-white">{{ profileName }}</h2>
          <p class="text-[10px] text-slate-400 mt-1">
            信用代码：<span class="font-mono">{{ profileCreditCode }}</span> · 合作评级: AA级
          </p>
        </div>
      </div>
      <div class="text-center font-mono">
        <p class="text-[9px] text-slate-500">抗风水位风险值</p>
        <p class="text-xl font-bold text-danger">{{ profileRiskVal }}</p>
      </div>
    </div>

    <!-- 双面板 ECharts -->
    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 lg:col-span-8 hud-card p-4 h-[320px]">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-2">多层关联生态关系链</h3>
        <div ref="ecoChartRef" class="w-full h-full"></div>
      </div>
      <div class="col-span-12 lg:col-span-4 hud-card p-4 h-[320px]">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-2">五维能力水位分布</h3>
        <div ref="radarChartRef" class="w-full h-full"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'

const searchVal = ref('深圳市某半导体有限公司')
const profileShort = ref('深')
const profileName = ref('深圳市某半导体有限公司')
const profileCreditCode = ref('91440300MA5XXXXXX')
const profileRiskVal = ref(74)

const ecoChartRef = ref<HTMLDivElement | null>(null)
const radarChartRef = ref<HTMLDivElement | null>(null)

const ecoChart = shallowRef<echarts.ECharts | null>(null)
const radarChart = shallowRef<echarts.ECharts | null>(null)

const performProfileSearch = () => {
  const kw = searchVal.value
  if (kw.includes('半导体') || kw.includes('深')) {
    loadProfileData('深圳市某半导体有限公司', '深', '91440300MA5XXXXXX', 74, [65, 85, 90, 95, 70])
    ElMessage.success('已成功加载 深圳市某半导体有限公司 画像数据')
  } else if (kw.includes('物流') || kw.includes('运')) {
    loadProfileData('苏哈国际多式联运有限公司', '苏', '91440300MB2YYYYYY', 35, [95, 60, 80, 50, 90])
    ElMessage.success('已成功加载 苏哈国际多式联运有限公司 画像数据')
  } else {
    ElMessage.warning('未检索到精确关联信息，已加载基础参考数据模式')
  }
}

const loadProfileData = (name: string, shortText: string, code: string, riskVal: number, radarVals: number[]) => {
  profileName.value = name
  profileShort.value = shortText
  profileCreditCode.value = code
  profileRiskVal.value = riskVal

  ecoChart.value?.setOption({
    backgroundColor: 'transparent',
    series: [{
      type: 'graph',
      layout: 'force',
      data: [
        { name: name, symbolSize: 35, itemStyle: { color: '#3b82f6' } },
        { name: '控股母厂 X', symbolSize: 20, itemStyle: { color: '#8b5cf6' } },
        { name: '晶圆代工厂 Y', symbolSize: 20, itemStyle: { color: '#8b5cf6' } }
      ],
      links: [
        { source: '控股母厂 X', target: name },
        { source: '晶圆代工厂 Y', target: name }
      ],
      roam: true,
      label: { show: true, fontSize: 8, color: '#94a3b8' },
      force: { repulsion: 120 }
    }]
  })

  radarChart.value?.setOption({
    backgroundColor: 'transparent',
    radar: {
      indicator: [
        { name: '财务信用', max: 100 },
        { name: '履约周期', max: 100 },
        { name: '合规安全', max: 100 },
        { name: '替换冗余度', max: 100 },
        { name: '质量水平', max: 100 }
      ],
      splitArea: { show: false }
    },
    series: [{
      type: 'radar',
      data: [{
        value: radarVals,
        areaStyle: { color: 'rgba(59, 130, 246, 0.4)' },
        lineStyle: { color: '#3b82f6' }
      }]
    }]
  })
}

const handleResize = () => {
  ecoChart.value?.resize()
  radarChart.value?.resize()
}

onMounted(() => {
  if (ecoChartRef.value) ecoChart.value = echarts.init(ecoChartRef.value, 'dark')
  if (radarChartRef.value) radarChart.value = echarts.init(radarChartRef.value, 'dark')

  loadProfileData('深圳市某半导体有限公司', '深', '91440300MA5XXXXXX', 74, [65, 85, 90, 95, 70])
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  ecoChart.value?.dispose()
  radarChart.value?.dispose()
})
</script>
