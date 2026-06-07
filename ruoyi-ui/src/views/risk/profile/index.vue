<template>
  <div class="risk-page space-y-6">
    <div class="hud-card p-4 flex flex-wrap items-center justify-between gap-3">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <input v-model="keyword" class="cyber-input w-80 px-3 py-2 text-xs" placeholder="企业名称或统一社会信用代码" @keyup.enter="loadProfile" />
      <button class="rounded bg-primary px-4 py-2 text-xs font-bold text-white" @click="loadProfile">检索企业画像</button>
    </div>

    <div v-if="!enterprise" class="hud-card p-12 text-center text-sm text-slate-500">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      暂无企业画像数据，请先上传真实企业风险数据
    </div>

    <template v-else>
      <div class="hud-card p-6 flex flex-wrap items-center justify-between gap-4">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div>
          <h2 class="text-lg font-bold text-white">{{ enterprise.enterpriseName }}</h2>
          <p class="mt-1 text-xs text-slate-400">{{ enterprise.creditCode || '-' }} / {{ enterprise.industry || '-' }} / {{ enterprise.region || '-' }}</p>
        </div>
        <div class="text-right">
          <p class="text-[10px] text-slate-500">风险评分</p>
          <p class="font-mono text-3xl font-bold text-danger">{{ enterprise.riskScore || 0 }}</p>
        </div>
      </div>

      <div class="grid grid-cols-12 gap-6">
        <div class="col-span-12 xl:col-span-5 hud-card p-4">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-sm font-bold mb-3">五维风险分布</h3>
          <div ref="radarRef" class="h-[320px]"></div>
        </div>
        <div class="col-span-12 xl:col-span-7 hud-card p-4">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <h3 class="text-sm font-bold mb-3">关联风险事件</h3>
          <table class="risk-table">
            <tbody>
              <tr v-for="item in events" :key="item.eventId">
                <td>{{ item.eventTitle }}</td>
                <td>{{ item.category }}</td>
                <td class="font-mono">{{ item.riskScore }}</td>
                <td>{{ item.status }}</td>
              </tr>
              <tr v-if="events.length === 0"><td class="text-center text-slate-500" colspan="4">该企业暂无真实风险事件</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import * as echarts from 'echarts';
import { getEnterpriseProfile } from '@/api/risk/enterprise';

const keyword = ref('');
const enterprise = ref<any>(null);
const events = ref<any[]>([]);
const radar = ref<Record<string, number>>({});
const radarRef = ref<HTMLDivElement | null>(null);
const radarChart = shallowRef<echarts.ECharts | null>(null);

const loadProfile = async () => {
  const res = await getEnterpriseProfile(keyword.value);
  enterprise.value = res.data?.enterprise || null;
  events.value = res.data?.events || [];
  radar.value = res.data?.radar || {};
  await nextTick();
  renderRadar();
};

const renderRadar = () => {
  if (!radarRef.value || !enterprise.value) return;
  radarChart.value ||= echarts.init(radarRef.value, 'dark');
  const names = Object.keys(radar.value);
  radarChart.value.setOption({
    backgroundColor: 'transparent',
    radar: { indicator: names.map((name) => ({ name, max: 100 })) },
    series: [{ type: 'radar', data: [{ value: names.map((name) => radar.value[name] || 0), areaStyle: { color: 'rgba(59,130,246,0.25)' } }] }]
  });
};

onMounted(loadProfile);
onBeforeUnmount(() => radarChart.value?.dispose());
</script>
