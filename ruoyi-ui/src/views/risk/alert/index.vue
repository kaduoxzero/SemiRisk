<template>
  <div class="p-6 h-full flex flex-col space-y-6">
    <div class="hud-card flex-1 flex flex-col overflow-hidden">
      <div class="corner-br"></div><div class="corner-bl"></div>

      <!-- 过滤条件栏 -->
      <div class="p-4 border-b border-border flex flex-wrap gap-4 items-center justify-between bg-white/5 z-10">
        <div class="flex flex-wrap items-center gap-3">
          <div class="relative">
            <Icon icon="lucide:search" class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
            <input
              v-model="searchKeyword"
              class="cyber-input pl-9 pr-4 py-1.5 rounded text-xs w-56"
              placeholder="输入关键字模糊搜索..."
              type="text"
            />
          </div>

          <select v-model="levelFilter" class="cyber-input px-3 py-1.5 rounded text-xs">
            <option value="ALL">全部风险评级</option>
            <option value="CRITICAL">Critical (高危)</option>
            <option value="WARNING">Warning (中等风险)</option>
            <option value="INFO">Info (低风险/一般)</option>
          </select>

          <select v-model="statusFilter" class="cyber-input px-3 py-1.5 rounded text-xs">
            <option value="ALL">全部处理进度</option>
            <option value="UNRESOLVED">未处理</option>
            <option value="RESOLVING">处理中</option>
            <option value="RESOLVED">已闭环</option>
          </select>
        </div>
        <div class="text-[10px] text-slate-500">
          实时同步时基: <span class="font-mono text-primary">每 30s 自动刷新</span>
        </div>
      </div>

      <!-- 数据表格 -->
      <div class="flex-1 overflow-y-auto">
        <table class="w-full text-left border-collapse">
          <thead>
          <tr class="text-[10px] uppercase text-slate-500 border-b border-border bg-white/5 font-mono">
            <th class="px-6 py-3">告警基点</th>
            <th class="px-6 py-3">安全水位评级</th>
            <th class="px-6 py-3">事件源概要描述</th>
            <th class="px-6 py-3">信号来源</th>
            <th class="px-6 py-3">处理进度</th>
            <th class="px-6 py-3 text-right">人工介入</th>
          </tr>
          </thead>
          <tbody class="text-xs divide-y divide-white/5">
          <tr v-if="filteredData.length === 0">
            <td colspan="6" class="text-center py-8 text-slate-500">无符合搜索要求的告警事件</td>
          </tr>
          <tr v-for="item in filteredData" :key="item.time" class="hover:bg-white/5 transition-colors">
            <td class="px-6 py-4 font-mono text-slate-400">{{ item.time }}</td>
            <td class="px-6 py-4">
                <span :class="['px-1.5 py-0.5 rounded text-[8px] font-bold', getBadgeClass(item.level)]">
                  {{ item.level }}
                </span>
            </td>
            <td class="px-6 py-4 font-semibold text-white cursor-pointer hover:text-primary transition-colors" @click="router.push('/risk/detail')">
              {{ item.title }}
            </td>
            <td class="px-6 py-4 text-slate-400">{{ item.source }}</td>
            <td class="px-6 py-4" v-html="getStatusHtml(item.status)"></td>
            <td class="px-6 py-4 text-right space-x-2">
              <button class="text-primary hover:underline" @click="router.push('/risk/detail')">详情</button>
              <button class="text-slate-400 hover:text-white" @click="ignoreAlert">忽略</button>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'

const router = useRouter()

// 1. 声明静态源数据 (后续可替换为 API 接口请求数据)
const mockData = ref([
  { time: '15:30:12', level: 'CRITICAL', title: '东南亚港口主航线通道拥堵指数突破预警限制', source: 'GIS 综合星图', status: 'UNRESOLVED' },
  { time: '14:55:20', level: 'WARNING', title: '封测原料锂电池组件24小时波动率超5%', source: '行业大宗监控', status: 'RESOLVING' },
  { time: '14:20:05', level: 'INFO', title: '华南区域出台关税与配额临时减免细则', source: '政策舆情爬虫', status: 'RESOLVED' },
  { time: '11:15:22', level: 'CRITICAL', title: '二级合作封测企业 A 工商财报异常预估亏损', source: '信披数据仓库', status: 'UNRESOLVED' }
])

// 2. 绑定过滤状态
const searchKeyword = ref('')
const levelFilter = ref('ALL')
const statusFilter = ref('ALL')

// 3. 计算过滤后的结果集 (自动计算，代替 filterData 方法)
const filteredData = computed(() => {
  return mockData.value.filter(item => {
    const matchKw = item.title.toLowerCase().includes(searchKeyword.value.toLowerCase()) ||
      item.source.toLowerCase().includes(searchKeyword.value.toLowerCase())
    const matchLvl = levelFilter.value === 'ALL' || item.level === levelFilter.value
    const matchSts = statusFilter.value === 'ALL' || item.status === statusFilter.value
    return matchKw && matchLvl && matchSts
  })
})

const getBadgeClass = (level: string) => {
  if (level === 'CRITICAL') return 'bg-danger/20 text-danger'
  if (level === 'WARNING') return 'bg-warning/20 text-warning'
  return 'bg-primary/20 text-primary'
}

const getStatusHtml = (status: string) => {
  if (status === 'UNRESOLVED') {
    return `<span class="text-danger flex items-center gap-1"><span class="w-1.5 h-1.5 rounded-full bg-danger animate-ping"></span>未处理</span>`
  }
  return status === 'RESOLVING' ? '<span class="text-warning">处理中</span>' : '<span class="text-success">已闭环</span>'
}

const ignoreAlert = () => {
  ElMessage.info('告警已忽略')
}
</script>
