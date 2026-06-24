<template>
  <section class="split-detail">
    <aside class="panel fixed-panel">
      <div class="toolbar compact-toolbar">
        <h3>告警概览</h3>
        <button class="btn secondary" @click="actions.loadAlerts">刷新</button>
      </div>
      <button
        v-for="alert in visibleAlerts"
        :key="alert.id"
        class="list-row"
        :class="{ active: alert.id === detail.id }"
        @click="actions.openRisk(alert.id)"
      >
        <span class="badge severity-badge" :class="badgeClass(alert.level)">{{ alert.level }}</span>
        <strong>{{ alert.title }}</strong>
        <em>{{ alert.source }} · {{ formatTime(alert.time) }}</em>
      </button>
      <p v-if="!alerts.length" class="muted">请先从预警中心进入，或点击刷新加载公开源告警。</p>
      <div class="pager">
        <button class="btn secondary" :disabled="state.detailPage <= 1" @click="state.detailPage--">上一页</button>
        <span>{{ state.detailPage }} / {{ totalPages }}</span>
        <button class="btn secondary" :disabled="state.detailPage >= totalPages" @click="state.detailPage++">下一页</button>
      </div>
    </aside>

    <section class="panel alert-detail-panel">
      <div class="alert-detail-head">
        <div>
          <p class="muted">点击左侧告警后生成解释 / Explain Selected Alert</p>
          <h3>{{ detail.title || detail.originalTitle || '未选择告警' }}</h3>
        </div>
        <span class="badge severity-badge" :class="badgeClass(detail.level)">{{ detail.level || '待采集' }}</span>
      </div>

      <div class="alert-detail-grid">
        <div><span class="muted">编号</span><strong>{{ detail.id || '-' }}</strong></div>
        <div><span class="muted">状态</span><strong>{{ detail.status || '-' }}</strong></div>
        <div><span class="muted">风险评分</span><strong>{{ detail.riskScore ?? 0 }}</strong></div>
        <div><span class="muted">发布时间</span><strong>{{ formatTime(detail.firstSeen) }}</strong></div>
        <div><span class="muted">来源</span><strong>{{ detail.source || '-' }}</strong></div>
        <div><span class="muted">维度</span><strong>{{ detail.type || '-' }}</strong></div>
      </div>

      <div class="translation-card">
        <p class="muted">英文原文 / English</p>
        <p>{{ detail.titleEn || detail.originalTitle || '-' }}</p>
        <p class="muted">中文译文 / Chinese</p>
        <p>{{ detail.translation?.zh || detail.title || '-' }}</p>
        <a v-if="detail.sourceUrl" class="text-link" :href="detail.sourceUrl" target="_blank" rel="noreferrer">查看公开源原文 / Open Source Article</a>
      </div>

      <div class="table-wrap compact-table">
        <table>
          <thead><tr><th>中文字段</th><th>中文内容</th><th>English Field</th><th>English Content</th></tr></thead>
          <tbody>
            <tr v-for="row in detail.bilingualRows || []" :key="row.zhLabel + row.enLabel">
              <td>{{ row.zhLabel }}</td><td>{{ row.zh }}</td><td>{{ row.enLabel }}</td><td>{{ row.en }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<script setup>
import { computed, watch } from 'vue';
import { badgeClass, formatTime } from '../utils/formatters';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const pageSize = 8;
const detail = computed(() => props.state.riskDetail || {});
const alerts = computed(() => props.state.alerts || []);
const totalPages = computed(() => Math.max(1, Math.ceil(alerts.value.length / pageSize)));
const visibleAlerts = computed(() => alerts.value.slice((props.state.detailPage - 1) * pageSize, props.state.detailPage * pageSize));

watch(alerts, () => {
  props.state.detailPage = 1;
});
</script>
