<template>
  <div class="p-6 space-y-6 text-slate-200">
    <!-- 步骤条 -->
    <div class="grid grid-cols-1 md:grid-cols-4 gap-4 max-w-4xl mx-auto">
      <div v-for="(step, index) in steps" :key="index"
           :class="['hud-card p-3 flex items-center space-x-3 transition-all duration-300', activeStep >= index + 1 ? 'border-primary/60 bg-primary/5 opacity-100' : 'opacity-50']"
      >
        <div :class="['w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold', activeStep >= index + 1 ? 'bg-primary text-white' : 'bg-slate-800 text-slate-400']">
          {{ index + 1 }}
        </div>
        <span class="text-xs font-semibold">{{ step }}</span>
      </div>
    </div>

    <!-- 上传核心工作区 -->
    <div class="grid grid-cols-12 gap-6">
      <div class="col-span-12 lg:col-span-7 space-y-6">
        <div
          class="hud-card p-8 border-2 border-dashed border-primary/30 hover:border-primary/60 transition-all cursor-pointer flex flex-col items-center justify-center text-center"
          @click="triggerUploadSimulation"
        >
          <div class="corner-br"></div><div class="corner-bl"></div>
          <Icon icon="lucide:upload-cloud" class="text-4xl text-primary/60 mb-2 animate-bounce" />
          <h3 class="text-sm font-bold text-white mb-1">点击或拖拽企业运行明细报表到此处</h3>
          <p class="text-[10px] text-slate-500">支持 XLS、CSV、PDF等，单个文件最大 50MB</p>

          <div v-if="fileLoaded" class="mt-4 p-2.5 bg-primary/10 border border-primary/30 rounded text-xs text-white">
            已选定：<span class="font-bold">2026年度东南亚物流运行数据.csv</span> (12.4 MB)
          </div>
        </div>

        <div class="hud-card p-4">
          <div class="corner-br"></div><div class="corner-bl"></div>
          <h3 class="text-xs font-bold mb-3">当前解析明细</h3>
          <div class="space-y-2">
            <div class="flex items-center justify-between p-2.5 bg-white/5 rounded text-xs">
              <div class="flex items-center space-x-2">
                <Icon icon="lucide:file-spreadsheet" class="text-green-400" />
                <span>季度财报汇总表.xlsx</span>
              </div>
              <span class="text-[10px] text-success">等待AI解析</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 日志面板 -->
      <div class="col-span-12 lg:col-span-5 flex flex-col">
        <div class="hud-card p-4 flex-1 flex flex-col h-[320px]">
          <div class="corner-br"></div><div class="corner-bl"></div>
          <h3 class="text-xs font-bold mb-3 flex items-center">
            <Icon icon="lucide:terminal" class="mr-1.5 text-primary" />AI 数据清洗引擎控制台
          </h3>

          <div class="flex-1 bg-black/40 rounded p-3 font-mono text-[10px] space-y-1.5 overflow-y-auto text-slate-400" ref="consoleRef">
            <p v-for="(log, idx) in logConsole" :key="idx" :class="getLogClass(log.type)">
              {{ log.text }}
            </p>
          </div>

          <button
            :disabled="!fileLoaded || isProcessing"
            class="mt-3 w-full py-2 bg-primary hover:bg-blue-600 rounded text-xs font-bold text-white transition-all disabled:opacity-50"
            @click="startProcessingSimulation"
          >
            启动AI校验及入库
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'

const steps = ['1. 文件上传', '2. 模板校验', '3. AI 清洗转化', '4. 导入完成']
const activeStep = ref(1)
const fileLoaded = ref(false)
const isProcessing = ref(false)
const consoleRef = ref<HTMLDivElement | null>(null)

interface LogEntry {
  text: string;
  type: 'info' | 'primary' | 'warn' | 'success' | 'indigo';
}

const logConsole = ref<LogEntry[]>([
  { text: '[15:30:01] INFO 等待源文件注入...', type: 'info' }
])

const appendLog = (text: string, type: 'info' | 'primary' | 'warn' | 'success' | 'indigo') => {
  logConsole.value.push({ text, type })
  nextTick(() => {
    if (consoleRef.value) {
      consoleRef.value.scrollTop = consoleRef.value.scrollHeight
    }
  })
}

const getLogClass = (type: string) => {
  if (type === 'primary') return 'text-primary'
  if (type === 'warn') return 'text-warning'
  if (type === 'indigo') return 'text-indigo-400'
  if (type === 'success') return 'text-success'
  return 'text-slate-500'
}

const triggerUploadSimulation = () => {
  if (fileLoaded.value) return
  fileLoaded.value = true
  appendLog('[15:31:02] FILE "2026年度东南亚物流运行数据.csv" 加载成功，校验通过。', 'primary')
  ElMessage.success('文件添加成功，已锁定导入流')
}

const startProcessingSimulation = () => {
  isProcessing.value = true
  activeStep.value = 2
  appendLog('[15:31:20] WARN 正在扫描模板异常字段...', 'warn')

  setTimeout(() => {
    activeStep.value = 3
    appendLog('[15:31:22] AI 自动校准空字段3处，转译东南亚GPS轨迹点...', 'indigo')
  }, 1000)

  setTimeout(() => {
    activeStep.value = 4
    appendLog('[15:31:24] SUCCESS 数据正式注入成功！受影响模型将触发自动报警评估。', 'success')
    ElMessage.success('数据完整清洗成功，指标已同步大盘！')
    isProcessing.value = false
  }, 2000)
}
</script>
