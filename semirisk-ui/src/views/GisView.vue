<template>
  <section class="gis-page">
    <div class="panel gis-map-panel">
      <div class="gis-header">
        <div>
          <h3>全球 GIS 风险实时地图</h3>
          <p class="muted">基于已接入公开源、VM 数据库与本地规则引擎生成，生产环境接入实时 GIS/物流源后替换。</p>
        </div>
        <button class="btn secondary" @click="actions.loadGis">刷新图层</button>
      </div>

      <div class="map-canvas">
        <svg class="route-network" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
          <path d="M32 38 C45 30 58 36 68 54" />
          <path d="M68 54 C74 50 82 46 90 40" />
          <path d="M32 38 C24 48 18 59 12 70" />
        </svg>
        <div class="continent asia">亚太供应带</div>
        <div class="continent europe">欧洲港口群</div>
        <div class="continent america">北美转运带</div>

        <button
          v-for="point in points"
          :key="point.id || point.name"
          class="risk-point"
          :class="riskClass(point.riskIndex)"
          :style="pointStyle(point)"
          @click="selectedPoint = point"
          :title="`${point.name} 风险指数 ${point.riskIndex}`"
        >
          <span></span>
        </button>

        <div class="map-status">
          <strong>{{ activeLayerText }}</strong>
          <span>{{ state.gis.updatedAt || state.dashboard.refreshedAt || '等待刷新' }}</span>
          <span>{{ state.gis.dataSource || '公开源数据待加载' }}</span>
        </div>
      </div>
    </div>

    <aside class="gis-side">
      <div class="panel">
        <h3>图层管理</h3>
        <div class="layer-list">
          <label v-for="layer in state.layers" :key="layer" class="layer-toggle">
            <input v-model="state.activeLayers" :value="layer" type="checkbox" @change="actions.loadGis" />
            <span>{{ layerName(layer) }}</span>
          </label>
        </div>
      </div>

      <div class="panel">
        <h3>热点摘要</h3>
        <div v-for="region in regions" :key="region.name" class="region-row">
          <span>{{ region.name }}</span>
          <strong :class="riskClass(region.score)">{{ region.score }}</strong>
          <em>{{ region.status }}</em>
        </div>
      </div>

      <div class="panel detail-panel">
        <h3>节点详情</h3>
        <template v-if="selectedPoint">
          <div class="detail-title">{{ selectedPoint.name }}</div>
          <p class="muted">坐标：{{ selectedPoint.lon }}, {{ selectedPoint.lat }}</p>
          <p>风险指数：<strong :class="riskClass(selectedPoint.riskIndex)">{{ selectedPoint.riskIndex }}</strong></p>
          <p>{{ selectedPoint.analysis }}</p>
          <p v-if="selectedPoint.sourceUrl"><a class="text-link" :href="selectedPoint.sourceUrl" target="_blank" rel="noreferrer">查看公开源原文</a></p>
          <button class="btn" @click="actions.setView('analysis')">进入深度分析</button>
        </template>
        <p v-else class="muted">暂无公开源点位，请刷新 data-service 采集任务</p>
      </div>
    </aside>

    <div class="panel gis-table">
      <h3>点位清单</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>点位</th><th>经度</th><th>纬度</th><th>风险指数</th><th>数据说明</th></tr></thead>
          <tbody>
            <tr v-for="point in points" :key="point.name" @click="selectedPoint = point">
              <td>{{ point.name }}</td>
              <td>{{ point.lon }}</td>
              <td>{{ point.lat }}</td>
              <td><span class="badge" :class="badgeClass(point.riskIndex)">{{ point.riskIndex }}</span></td>
              <td>{{ point.analysis }}</td>
            </tr>
            <tr v-if="!points.length">
              <td colspan="5" class="muted">暂无公开源 GIS 点位。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const selectedPoint = ref(null);
const points = computed(() => props.state.gis.points || []);
const regions = computed(() => props.state.gis.regions || []);
const activeLayerText = computed(() => props.state.activeLayers.map(layerName).join(' / '));

watch(points, list => {
  if (!list.length) {
    selectedPoint.value = null;
    return;
  }
  if (!selectedPoint.value || !list.some(point => point.name === selectedPoint.value.name)) {
    selectedPoint.value = list[0];
  }
}, { immediate: true });

function layerName(layer) {
  return {
    heatmap: '风险热力图',
    suppliers: '供应商分布',
    ports: '港口/航道',
    routes: '物流路径'
  }[layer] || layer;
}

function pointStyle(point) {
  const left = Math.min(94, Math.max(6, ((Number(point.lon) + 180) / 360) * 100));
  const top = Math.min(86, Math.max(10, ((90 - Number(point.lat)) / 180) * 100));
  return { left: `${left}%`, top: `${top}%` };
}

function riskClass(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}

function badgeClass(score) {
  return riskClass(score);
}
</script>
