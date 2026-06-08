<template>
  <section class="grid">
    <div class="panel alerts-toolbar-panel">
      <div class="toolbar">
        <input v-model="state.alertFilter.keyword" class="input wide" placeholder="搜索标题 / 来源" @input="onKeyword" />
        <select v-model="state.alertFilter.level" class="select" @change="actions.loadAlerts">
          <option value="">所有等级</option>
          <option>高危</option>
          <option>中危</option>
          <option>低危</option>
        </select>
        <select v-model="state.alertFilter.status" class="select" @change="actions.loadAlerts">
          <option value="">未忽略</option>
          <option>未处理</option>
          <option>处理中</option>
          <option>已忽略</option>
        </select>
        <button class="btn secondary" @click="actions.loadAlerts">刷新</button>
        <button
          class="btn"
          :disabled="!state.selectedAlertIds.length"
          @click="actions.batchProcess"
        >
          批量处理
          <span v-if="state.selectedAlertIds.length" class="badge low" style="margin-left:6px">{{ state.selectedAlertIds.length }}</span>
        </button>
      </div>
      <div class="alerts-stats">
        <span>共 <strong>{{ state.alerts.length }}</strong> 条</span>
        <span>高危 <strong class="high">{{ countByLevel('高危') }}</strong></span>
        <span>中危 <strong class="mid">{{ countByLevel('中危') }}</strong></span>
        <span>已选 <strong>{{ state.selectedAlertIds.length }}</strong></span>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th style="width:36px">
                <input type="checkbox" :checked="allSelected" @change="actions.toggleAllAlerts($event.target.checked)" />
              </th>
              <th>时间</th>
              <th>等级</th>
              <th>标题</th>
              <th>来源</th>
              <th>状态</th>
              <th style="width:120px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alert in visibleAlerts" :key="alert.id" :class="{ 'row-selected': state.selectedAlertIds.includes(alert.id) }">
              <td><input v-model="state.selectedAlertIds" :value="alert.id" type="checkbox" /></td>
              <td class="muted" style="font-size:12px;white-space:nowrap">{{ formatTime(alert.time) }}</td>
              <td><span class="badge" :class="badgeClass(alert.level)">{{ alert.level }}</span></td>
              <td>{{ alert.title }}</td>
              <td>
                <a v-if="alert.sourceUrl" class="text-link" :href="alert.sourceUrl" target="_blank" rel="noreferrer">{{ alert.source }}</a>
                <span v-else>{{ alert.source }}</span>
              </td>
              <td><span class="badge" :class="statusBadge(alert.status)">{{ alert.status }}</span></td>
              <td>
                <div style="display:flex;gap:6px">
                  <button class="btn secondary tiny" @click="actions.openRisk(alert.id)">详情</button>
                  <button class="btn danger tiny" @click="actions.ignoreAlert(alert.id)">忽略</button>
                </div>
              </td>
            </tr>
            <tr v-if="!state.alerts.length">
              <td colspan="7" class="muted" style="text-align:center;padding:24px">暂无告警，请确认公开源采集已完成。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn secondary" :disabled="alertPage <= 1" @click="alertPage--">上一页</button>
        <span>{{ alertPage }} / {{ totalPages }}</span>
        <button class="btn secondary" :disabled="alertPage >= totalPages" @click="alertPage++">下一页</button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue';
import { badgeClass, formatTime } from '../utils/formatters';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const alertPage = ref(1);
const pageSize = 15;

let debounceTimer = null;
function onKeyword() {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => props.actions.loadAlerts(), 400);
}

const allSelected = computed(() =>
  props.state.alerts.length > 0 &&
  props.state.selectedAlertIds.length === props.state.alerts.length
);

const totalPages = computed(() => Math.max(1, Math.ceil(props.state.alerts.length / pageSize)));
const visibleAlerts = computed(() =>
  props.state.alerts.slice((alertPage.value - 1) * pageSize, alertPage.value * pageSize)
);

function countByLevel(level) {
  return props.state.alerts.filter(a => a.level === level).length;
}

function statusBadge(status) {
  if (status === '已忽略') return 'mid';
  if (status === '处理中') return 'low';
  return '';
}
</script>
