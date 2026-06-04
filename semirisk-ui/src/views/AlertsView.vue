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
      <button class="btn" @click="actions.batchProcess">批量处理</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>时间</th><th>等级</th><th>标题</th><th>来源</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="alert in state.alerts" :key="alert.id">
            <td>{{ formatTime(alert.time) }}</td>
            <td><span class="badge" :class="badgeClass(alert.level)">{{ alert.level }}</span></td>
            <td>{{ alert.title }}</td><td>{{ alert.source }}</td><td>{{ alert.status }}</td>
            <td>
              <button class="btn secondary" @click="actions.openRisk(alert.id)">详情</button>
              <button class="btn danger" @click="actions.ignoreAlert(alert.id)">忽略</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup>
import { badgeClass, formatTime } from '../utils/formatters';

defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
