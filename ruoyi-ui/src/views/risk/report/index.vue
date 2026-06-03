<template>
  <div class="p-6 space-y-6 text-slate-200">
    <!-- 模板选择 -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div v-for="id in [1, 2, 3]" :key="id"
           :class="['hud-card p-4 cursor-pointer transition-all', selectedTemplate === id ? 'border-primary/50 bg-primary/10' : '']"
           @click="selectTemplate(id)"
      >
        <div class="corner-br"></div><div class="corner-bl"></div>
        <h3 class="font-bold text-xs">{{ getTemplateName(id) }}</h3>
        <p class="text-[9px] text-slate-400 mt-2">{{ getTemplateDesc(id) }}</p>
      </div>
    </div>

    <!-- 参数配置 -->
    <div class="hud-card p-6">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <div class="flex flex-col sm:flex-row justify-between items-center border-b border-white/5 pb-4 mb-4 gap-3">
        <h3 class="text-xs font-bold text-primary">配置报告输出参数</h3>
        <button class="bg-primary hover:bg-blue-600 text-xs px-6 py-2 rounded text-white font-bold transition-all" @click="triggerReportGeneration">
          开始智能生成报告
        </button>
      </div>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-6 text-xs">
        <div>
          <label class="block text-slate-400 mb-1">选定时间范围</label>
          <input class="cyber-input w-full p-2.5 rounded text-xs" type="date" value="2026-05-20"/>
        </div>
        <div>
          <label class="block text-slate-400 mb-1">报告输出格式</label>
          <select class="cyber-input w-full p-2.5 rounded text-xs bg-slate-800">
            <option>电子版安全防伪PDF</option>
            <option>可编辑富文本Word</option>
          </select>
        </div>
      </div>
    </div>

    <!-- 进度动画 -->
    <div v-if="loadingVisible" class="hud-card p-8 flex flex-col items-center justify-center space-y-4">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <div class="w-16 h-16 rounded-full border-4 border-primary border-t-transparent animate-spin"></div>
      <div class="text-center">
        <p class="text-xs font-bold">{{ statusLabel }}</p>
        <div class="w-64 h-1.5 bg-slate-800 rounded-full overflow-hidden mt-2">
          <div class="h-full bg-primary transition-all duration-300" :style="{ width: progressVal + '%' }"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const selectedTemplate = ref(1)
const loadingVisible = ref(false)
const progressVal = ref(20)
const statusLabel = ref('大模型正在对多级节点信息进行建模推演 [20%]...')

const getTemplateName = (id: number) => {
  if (id === 1) return 'A型. 区域物流中断阻断评估模版'
  if (id === 2) return 'B型. 供应商财务破产风险研判模版'
  return 'C型. 整体供应链韧性扫描研报'
}

const getTemplateDesc = (id: number) => {
  if (id === 1) return '适合跨国港口拥堵、航路变更等灾害情景研判。'
  if (id === 2) return '深度审查工商、多级控股、财务杠杆健康度。'
  return '宏观扫描企业整体供应网络稳定性。'
}

const selectTemplate = (id: number) => {
  selectedTemplate.value = id
  ElMessage.info(`已更换模版 ${id}`)
}

const triggerReportGeneration = () => {
  loadingVisible.value = true
  progressVal.value = 20

  const steps = [
    "提取一级、二级关联合作厂工商画像...",
    "正在运用蒙特卡洛算法测算海运延误水位...",
    "调用GPT大语言模型整合输出处置建议...",
    "生成防伪校验码，PDF研报封包打包中...",
    "报告生成成功！"
  ]

  const timer = setInterval(() => {
    progressVal.value += 20
    if (progressVal.value >= 100) {
      progressVal.value = 100
      clearInterval(timer)
      ElMessage.success('研报封包输出成功，已下发通知！')
      setTimeout(() => {
        router.push('/risk/alert')
      }, 1000)
    }
    statusLabel.value = steps[Math.min(Math.floor(progressVal.value / 25), steps.length - 1)] + ` [${progressVal.value}%]`
  }, 700)
}
</script>
