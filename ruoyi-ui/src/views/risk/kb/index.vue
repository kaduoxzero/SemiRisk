<template>
  <div class="p-6 space-y-6 text-slate-200">
    <!-- 混合检索检索栏 -->
    <div class="hud-card p-4">
      <div class="corner-br"></div><div class="corner-bl"></div>
      <div class="flex space-x-3">
        <input
          v-model="queryVal"
          class="cyber-input px-3 py-2 rounded text-xs flex-1"
          placeholder="可输入如 '半导体物流中断、备货比率、劳资应对政策' 进行语义向量模糊搜索..."
        />
        <button class="bg-primary px-6 py-2 rounded text-xs text-white font-bold hover:bg-blue-600 transition-colors shrink-0" @click="simulateKBQuery">
          混合检索 (Hybrid)
        </button>
      </div>
    </div>

    <!-- 结果流 -->
    <div class="space-y-4">
      <div v-for="(item, idx) in results" :key="idx" class="hud-card p-4 space-y-2">
        <div class="corner-br"></div><div class="corner-bl"></div>
        <div class="flex justify-between items-center border-b border-white/5 pb-2">
          <span class="text-xs font-bold text-white">{{ item.title }}</span>
          <span class="text-[9px] text-green-400 font-mono">相关度 (Cosine Score): {{ item.score }}</span>
        </div>
        <p class="text-xs text-slate-400 leading-relaxed">{{ item.desc }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const queryVal = ref('半导体物流中断应急策略')

const results = ref([
  {
    title: '《亚太海运阻断重组白皮书 2026修订版》',
    score: '0.98',
    desc: '摘要：在新加坡或马六甲航路突发大面积恶劣气候或罢工危机时，企业最优兜底策略为：首批紧急料件采用拼箱航空联运，后续转运巴生港、在华南备用仓缓冲。'
  }
])

const simulateKBQuery = () => {
  ElMessage.info('正在调配向量语义服务器计算中...')

  setTimeout(() => {
    if (queryVal.value.includes('财务') || queryVal.value.includes('信用')) {
      results.value = [
        {
          title: '《二级代理信用传导控制指引 V2》',
          score: '0.94',
          desc: '对杠杆率大于80%的企业，启动季度对账及提货预付款抵扣流程，在风险敞口大过100万时执行货权强制控制。'
        }
      ]
    } else {
      results.value = [
        {
          title: '《亚太海运阻断重组白皮书 2026修订版》',
          score: '0.98',
          desc: '摘要：在新加坡或马六甲航路突发大面积恶劣气候或罢工危机时，企业最优兜底策略为：首批紧急料件采用拼箱航空联运，后续转运巴生港、在华南备用仓缓冲。'
        }
      ]
    }
    ElMessage.success('向量库匹配计算完毕！')
  }, 500)
}
</script>
