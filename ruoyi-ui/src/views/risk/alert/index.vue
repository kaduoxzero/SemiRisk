<template>
  <div class="risk-page">
    <div class="hud-card overflow-hidden">
      <div class="corner-br"></div>
      <div class="corner-bl"></div>
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-border bg-white/5 p-4">
        <div class="flex flex-wrap gap-3">
          <input v-model="query.eventTitle" class="cyber-input w-64 px-3 py-2 text-xs" placeholder="搜索真实风险事件" @keyup.enter="loadData" />
          <input v-model="query.enterpriseName" class="cyber-input w-56 px-3 py-2 text-xs" placeholder="企业名称" @keyup.enter="loadData" />
          <select v-model="query.riskLevel" class="cyber-select px-3 py-2 text-xs" @change="loadData">
            <option value="">全部等级</option>
            <option value="CRITICAL">CRITICAL</option>
            <option value="WARNING">WARNING</option>
            <option value="INFO">INFO</option>
          </select>
          <select v-model="query.status" class="cyber-select px-3 py-2 text-xs" @change="loadData">
            <option value="">全部状态</option>
            <option value="UNRESOLVED">未处理</option>
            <option value="RESOLVING">处理中</option>
            <option value="RESOLVED">已闭环</option>
          </select>
          <button class="risk-action px-4 py-2 text-xs" @click="loadData">查询</button>
          <button class="risk-action px-4 py-2 text-xs" @click="resetQuery">重置</button>
        </div>
        <button class="risk-action px-4 py-2 text-xs" @click="showCreate = !showCreate">{{ showCreate ? '收起新增' : '新增事件' }}</button>
      </div>

      <div v-if="showCreate" class="border-b border-border bg-white/5 p-4">
        <div class="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
          <input v-model="form.enterpriseName" class="cyber-input px-3 py-2 text-xs" placeholder="企业名称" />
          <input v-model="form.eventTitle" class="cyber-input px-3 py-2 text-xs xl:col-span-2" placeholder="事件标题" />
          <select v-model="form.category" class="cyber-select px-3 py-2 text-xs">
            <option>履约物流</option>
            <option>财务信用</option>
            <option>合规安全</option>
            <option>质量水平</option>
            <option>产能替代</option>
          </select>
          <select v-model="form.riskLevel" class="cyber-select px-3 py-2 text-xs">
            <option value="CRITICAL">CRITICAL</option>
            <option value="WARNING">WARNING</option>
            <option value="INFO">INFO</option>
          </select>
          <input v-model="form.riskScore" class="cyber-input px-3 py-2 text-xs" placeholder="风险分" />
          <input v-model="form.sourceName" class="cyber-input px-3 py-2 text-xs" placeholder="来源" />
          <input v-model="form.longitude" class="cyber-input px-3 py-2 text-xs" placeholder="经度" />
          <input v-model="form.latitude" class="cyber-input px-3 py-2 text-xs" placeholder="纬度" />
          <textarea v-model="form.description" class="cyber-textarea p-3 text-xs md:col-span-3 xl:col-span-4" placeholder="事件描述"></textarea>
          <button class="risk-action px-4 py-2 text-xs xl:col-span-2" :disabled="saving" @click="createEvent">
            {{ saving ? '写入中...' : '写入风险事件' }}
          </button>
        </div>
      </div>

      <div class="risk-scroll">
      <table class="risk-table">
        <thead>
          <tr>
            <th>发生时间</th>
            <th>等级</th>
            <th>企业</th>
            <th>事件</th>
            <th>来源</th>
            <th>风险分</th>
            <th>状态</th>
            <th class="text-right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="8" class="py-12 text-center text-slate-500">正在读取 risk_event...</td>
          </tr>
          <tr v-else-if="rows.length === 0">
            <td colspan="8" class="py-12 text-center text-slate-500">暂无真实风险事件，请先在数据上传页导入</td>
          </tr>
          <tr v-for="item in rows" :key="item.eventId">
            <td class="font-mono text-slate-400">{{ item.occurredAt || item.createTime }}</td>
            <td><span :class="badgeClass(item.riskLevel)">{{ item.riskLevel || '未分级' }}</span></td>
            <td>{{ item.enterpriseName || '-' }}</td>
            <td class="font-semibold text-white">{{ item.eventTitle }}</td>
            <td>{{ item.sourceName || '-' }}</td>
            <td class="font-mono">{{ item.riskScore || 0 }}</td>
            <td>{{ statusText(item.status) }}</td>
            <td class="text-right">
              <button class="text-primary hover:underline" @click="openDetail(item.eventId)">详情</button>
              <button class="ml-3 text-warning hover:underline" @click="mark(item.eventId, 'RESOLVING')">处理</button>
              <button class="ml-3 text-success hover:underline" @click="mark(item.eventId, 'RESOLVED')">闭环</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>

      <div class="flex flex-wrap items-center justify-between gap-3 p-4 text-xs text-slate-400">
        <span>共 {{ total }} 条真实风险事件</span>
        <div class="flex items-center gap-2">
          <button class="risk-action px-3 py-1" :disabled="query.pageNum <= 1" @click="page(-1)">上一页</button>
          <span class="font-mono">{{ query.pageNum }}</span>
          <button class="risk-action px-3 py-1" :disabled="query.pageNum * query.pageSize >= total" @click="page(1)">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { addRiskEvent, handleRiskEvent, listRiskEvents } from '@/api/risk/event';

const router = useRouter();
const query = reactive({ pageNum: 1, pageSize: 20, eventTitle: '', enterpriseName: '', riskLevel: '', status: '' });
const rows = ref<any[]>([]);
const total = ref(0);
const loading = ref(false);
const saving = ref(false);
const showCreate = ref(false);
const form = reactive({
  enterpriseName: '',
  eventTitle: '',
  category: '履约物流',
  riskLevel: 'WARNING',
  status: 'UNRESOLVED',
  sourceName: '人工录入',
  riskScore: '',
  longitude: '',
  latitude: '',
  description: ''
});

const loadData = async () => {
  loading.value = true;
  try {
    const res = await listRiskEvents(query);
    rows.value = res.rows || [];
    total.value = res.total || rows.value.length;
  } finally {
    loading.value = false;
  }
};

const createEvent = async () => {
  if (!form.enterpriseName || !form.eventTitle) {
    ElMessage.warning('请填写企业名称和事件标题');
    return;
  }
  saving.value = true;
  try {
    await addRiskEvent({
      ...form,
      riskScore: form.riskScore ? Number(form.riskScore) : undefined,
      longitude: form.longitude ? Number(form.longitude) : undefined,
      latitude: form.latitude ? Number(form.latitude) : undefined,
      occurredAt: new Date()
    });
    ElMessage.success('风险事件已写入真实业务表');
    Object.assign(form, { enterpriseName: '', eventTitle: '', category: '履约物流', riskLevel: 'WARNING', status: 'UNRESOLVED', sourceName: '人工录入', riskScore: '', longitude: '', latitude: '', description: '' });
    query.pageNum = 1;
    await loadData();
  } finally {
    saving.value = false;
  }
};

const mark = async (id: number, status: string) => {
  await handleRiskEvent(id, status);
  ElMessage.success('状态已写入真实业务表');
  loadData();
};

const page = (step: number) => {
  query.pageNum += step;
  loadData();
};

const resetQuery = () => {
  Object.assign(query, { pageNum: 1, pageSize: 20, eventTitle: '', enterpriseName: '', riskLevel: '', status: '' });
  loadData();
};

const openDetail = (id: number) => router.push({ path: '/risk/detail', query: { id } });
const statusText = (status: string) => ({ UNRESOLVED: '未处理', RESOLVING: '处理中', RESOLVED: '已闭环' }[status] || status || '-');
const badgeClass = (level: string) => {
  if (level === 'CRITICAL') return 'rounded bg-danger/20 px-2 py-0.5 text-[10px] font-bold text-danger';
  if (level === 'WARNING') return 'rounded bg-warning/20 px-2 py-0.5 text-[10px] font-bold text-warning';
  return 'rounded bg-primary/20 px-2 py-0.5 text-[10px] font-bold text-primary';
};

onMounted(loadData);
</script>
