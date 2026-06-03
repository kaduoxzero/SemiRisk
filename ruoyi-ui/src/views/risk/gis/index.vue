<template>
  <div class="absolute inset-0 z-0 flex flex-col overflow-hidden text-slate-200">
    <!-- 地图容器 -->
    <div ref="gisContainer" class="absolute inset-0 z-0"></div>

    <!-- 左侧图层选择 -->
    <div class="absolute left-6 top-6 z-10 w-64 space-y-4">
      <div class="hud-card p-4 bg-cyber-card/80 backdrop-blur">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="text-xs font-bold mb-3 flex items-center">
          <Icon icon="lucide:layers" class="mr-1.5 text-primary" />切换过滤图层
        </h3>
        <div class="space-y-2 text-xs">
          <label class="flex items-center space-x-2 cursor-pointer">
            <input type="checkbox" checked @change="toggleLayer(0)" class="accent-primary rounded bg-slate-800 border-slate-700"/>
            <span>海港物流骨干节点</span>
          </label>
          <label class="flex items-center space-x-2 cursor-pointer">
            <input type="checkbox" checked @change="toggleLayer(1)" class="accent-primary rounded bg-slate-800 border-slate-700"/>
            <span>分拨基地骨干节点</span>
          </label>
        </div>
      </div>
    </div>

    <!-- 点选浮窗 -->
    <div v-if="panelVisible" class="absolute right-6 top-6 z-10 w-72 hud-card p-4 bg-cyber-card/90 backdrop-blur">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <div class="flex justify-between items-center mb-2">
        <h3 class="text-xs font-bold text-white">{{ panelTitle }}</h3>
        <button class="text-slate-400 hover:text-white" @click="panelVisible = false">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <p class="text-[10px] text-slate-300 mb-4 leading-relaxed">{{ panelDesc }}</p>
      <button class="w-full py-1.5 bg-primary text-xs font-bold text-white rounded hover:bg-blue-600 transition-colors" @click="router.push('/risk/detail')">
        进入深度链路追踪
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'

const router = useRouter()
const gisContainer = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)

const panelVisible = ref(false)
const panelTitle = ref('')
const panelDesc = ref('')

const points = [
  { name: '新加坡物流枢纽', value: [30, 40, 95], desc: '新加坡节点：由于劳资纠纷及异常罢工，货物中转延误风险等级达到95。' },
  { name: '苏伊士关键海域航线', value: [60, 30, 75], desc: '苏伊士运河区：季节性大风影响，船舶通过效率略微下降。' },
  { name: '深圳盐田物流港', value: [80, 50, 40], desc: '深圳港区：运行状态健康，运力富余，具备承接溢出运力能力。' }
]

const renderGis = () => {
  if (!gisContainer.value) return
  chart.value = echarts.init(gisContainer.value, 'dark')

  chart.value.setOption({
    backgroundColor: 'transparent',
    xAxis: { show: false, min: 0, max: 100 },
    yAxis: { show: false, min: 0, max: 100 },
    series: [{
      type: 'effectScatter',
      coordinateSystem: 'cartesian2d',
      data: points.map(p => [p.value[0], p.value[1], p.value[2], p.name, p.desc]),
      symbolSize: (val: any) => val[2] / 4 + 8,
      rippleEffect: { scale: 3, brushType: 'stroke' },
      itemStyle: {
        color: (params: any) => params.data[2] > 80 ? '#ef4444' : '#eab308',
        shadowBlur: 10,
        shadowColor: '#333'
      },
      label: { show: true, formatter: (p: any) => p.data[3], position: 'right', fontSize: 9 }
    }]
  })

  chart.value.on('click', (p: any) => {
    panelTitle.value = p.data[3]
    panelDesc.value = p.data[4]
    panelVisible.value = true
  })
}

const toggleLayer = (idx: number) => {
  ElMessage.info(idx === 0 ? '已过滤骨干图层节点' : '已重构分拨图层视图')
}

const handleResize = () => chart.value?.resize()

onMounted(() => {
  renderGis()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart.value?.dispose()
})
</script>
