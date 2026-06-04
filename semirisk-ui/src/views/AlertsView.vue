<template>
  <section class="panel">
    <div class="toolbar">
      <input v-model="state.alertFilter.keyword" class="input" placeholder="标题/来源" @input="actions.loadAlerts" />
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
      <button class="btn" @click="actions.batchProcess">批量处理 {{ state.selectedAlertIds.length || '' }}</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th><input type="checkbox" :checked="allSelected" @change="actions.toggleAllAlerts($event.target.checked)" /></th>
            <th>时间</th><th>等级</th><th>标题</th><th>来源</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="alert in state.alerts" :key="alert.id">
            <td><input v-model="state.selectedAlertIds" :value="alert.id" type="checkbox" /></td>
            <td>{{ formatTime(alert.time) }}</td>
            <td><span class="badge" :class="badgeClass(alert.level)">{{ alert.level }}</span></td>
            <td>{{ alert.title }}</td>
            <td>
              <a v-if="alert.sourceUrl" class="text-link" :href="alert.sourceUrl" target="_blank" rel="noreferrer">{{ alert.source }}</a>
              <span v-else>{{ alert.source }}</span>
            </td>
            <td>{{ alert.status }}</td>
            <td>
              <button class="btn secondary" @click="actions.openRisk(alert.id)">详情</button>
              <button class="btn danger" @click="actions.ignoreAlert(alert.id)">忽略</button>
            </td>
          </tr>
          <tr v-if="!state.alerts.length">
            <td colspan="7" class="muted">暂无公开源告警，请确认 data-service 已完成公开网站采集。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue';
import { badgeClass, formatTime } from '../utils/formatters';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const allSelected = computed(() => props.state.alerts.length > 0 && props.state.selectedAlertIds.length === props.state.alerts.length);
</script>
