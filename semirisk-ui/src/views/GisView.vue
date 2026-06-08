<template>
  <section class="gis-page">
    <div class="panel gis-map-panel">
      <div class="gis-header">
        <div>
          <h3>全球 GIS 风险 3D 地球</h3>
          <p class="muted">拖动旋转 · 滚轮缩放 · 点击查看节点详情。公开源风险点实时映射，弧线表示物流/供应链关联路径。</p>
        </div>
        <button class="btn secondary" @click="actions.loadGis">刷新图层</button>
      </div>

      <div ref="globeRef" class="globe-canvas"
        @pointerdown.prevent="onPointerDown"
        @wheel.prevent="onWheel">
        <div class="map-status">
          <strong>{{ activeLayerText }}</strong>
          <span>{{ state.gis.updatedAt || state.dashboard.refreshedAt || '等待刷新' }}</span>
          <span>{{ state.gis.dataSource || '公开源数据待加载' }}</span>
        </div>
        <div v-if="selectedPoint" class="globe-popover">
          <b>{{ selectedPoint.name }}</b>
          <p>风险指数：{{ selectedPoint.riskIndex }}</p>
          <p>{{ selectedPoint.analysis }}</p>
          <a v-if="selectedPoint.sourceUrl" class="text-link" :href="selectedPoint.sourceUrl" target="_blank" rel="noreferrer">原文</a>
          <button class="close-popover" @click.stop="selectedPoint = null">✕</button>
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
        <p v-else class="muted">点击地球上的点位查看详情</p>
      </div>
    </aside>

    <div class="panel gis-table">
      <h3>点位清单 · {{ points.length }} 个点位 · {{ routes.length }} 条路径</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>点位</th><th>经度</th><th>纬度</th><th>风险指数</th><th>数据说明</th></tr></thead>
          <tbody>
            <tr v-for="point in points" :key="point.id || point.name" @click="selectedPoint = point" style="cursor:pointer">
              <td>{{ point.name }}</td>
              <td>{{ point.lon }}</td>
              <td>{{ point.lat }}</td>
              <td><span class="badge" :class="riskClass(point.riskIndex)">{{ point.riskIndex }}</span></td>
              <td>{{ point.analysis }}</td>
            </tr>
            <tr v-if="!points.length">
              <td colspan="5" class="muted">暂无公开源 GIS 点位，请刷新采集。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
import * as THREE from 'three';
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const globeRef = ref(null);
const selectedPoint = ref(null);
const points = computed(() => props.state.gis.points || []);
const routes = computed(() => props.state.gis.routes || []);
const regions = computed(() => props.state.gis.regions || []);
const activeLayerText = computed(() => props.state.activeLayers.map(layerName).join(' / '));

let scene, camera, renderer, globeGroup, pointGroup, routeGroup, heatGroup;
let animationId, resizeObserver;
const raycaster = new THREE.Raycaster();
const mouse = new THREE.Vector2();
const pointObjects = [];

// 拖动旋转状态
let isDragging = false;
let lastMouse = { x: 0, y: 0 };
const rotationVelocity = { x: 0, y: 0 };
let cameraRadius = 6.4;
const CAM_MIN = 3.0;
const CAM_MAX = 12.0;

watch(points, list => {
  if (!list.length) selectedPoint.value = null;
  else if (!selectedPoint.value || !list.some(p => p.name === selectedPoint.value.name)) {
    selectedPoint.value = list[0];
  }
  rebuildScene();
}, { deep: true });

watch(() => props.state.activeLayers.slice(), rebuildScene, { deep: true });
watch(routes, rebuildScene, { deep: true });

onMounted(async () => {
  await nextTick();
  initGlobe();
  rebuildScene();
  document.addEventListener('pointermove', onPointerMove);
  document.addEventListener('pointerup', onPointerUp);
});

onUnmounted(() => {
  cancelAnimationFrame(animationId);
  resizeObserver?.disconnect();
  renderer?.dispose();
  globeRef.value?.replaceChildren();
  document.removeEventListener('pointermove', onPointerMove);
  document.removeEventListener('pointerup', onPointerUp);
});

function initGlobe() {
  const host = globeRef.value;
  if (!host) return;
  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(42, host.clientWidth / host.clientHeight, 0.1, 100);
  camera.position.set(0, 0.4, cameraRadius);

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(host.clientWidth, host.clientHeight);
  host.appendChild(renderer.domElement);

  scene.add(new THREE.AmbientLight(0x9cc7ff, 1.2));
  const key = new THREE.DirectionalLight(0xffffff, 2.1);
  key.position.set(4, 3, 5);
  scene.add(key);

  globeGroup = new THREE.Group();
  pointGroup = new THREE.Group();
  routeGroup = new THREE.Group();
  heatGroup = new THREE.Group();
  globeGroup.add(heatGroup, routeGroup, pointGroup);
  scene.add(globeGroup);

  const earth = new THREE.Mesh(
    new THREE.SphereGeometry(2.05, 96, 64),
    new THREE.MeshPhongMaterial({ color: 0x123a6f, emissive: 0x071b38, shininess: 18, transparent: true, opacity: 0.96 })
  );
  globeGroup.add(earth);

  const wire = new THREE.Mesh(
    new THREE.SphereGeometry(2.065, 48, 32),
    new THREE.MeshBasicMaterial({ color: 0x4ea1ff, wireframe: true, transparent: true, opacity: 0.12 })
  );
  globeGroup.add(wire);
  addLatLonGrid();

  resizeObserver = new ResizeObserver(() => {
    if (!host || !renderer || !camera) return;
    camera.aspect = host.clientWidth / host.clientHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(host.clientWidth, host.clientHeight);
  });
  resizeObserver.observe(host);

  animate();
}

function onPointerDown(event) {
  isDragging = true;
  lastMouse = { x: event.clientX, y: event.clientY };
  rotationVelocity.x = 0;
  rotationVelocity.y = 0;
}

function onPointerMove(event) {
  if (!isDragging) return;
  const dx = event.clientX - lastMouse.x;
  const dy = event.clientY - lastMouse.y;
  lastMouse = { x: event.clientX, y: event.clientY };
  if (globeGroup) {
    globeGroup.rotation.y += dx * 0.008;
    globeGroup.rotation.x += dy * 0.008;
    // Clamp x rotation to avoid flipping
    globeGroup.rotation.x = Math.max(-1.3, Math.min(1.3, globeGroup.rotation.x));
  }
  rotationVelocity.x = dy * 0.004;
  rotationVelocity.y = dx * 0.004;
}

function onPointerUp(event) {
  if (!isDragging) return;
  isDragging = false;
  // Click detection: small movement = pick point
  const dx = event.clientX - lastMouse.x;
  const dy = event.clientY - lastMouse.y;
  if (Math.abs(dx) < 3 && Math.abs(dy) < 3 && renderer && camera) {
    const rect = renderer.domElement.getBoundingClientRect();
    mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(mouse, camera);
    const hit = raycaster.intersectObjects(pointObjects)[0];
    if (hit?.object?.userData?.point) selectedPoint.value = hit.object.userData.point;
  }
}

function onWheel(event) {
  cameraRadius += event.deltaY * 0.01;
  cameraRadius = Math.max(CAM_MIN, Math.min(CAM_MAX, cameraRadius));
  if (camera) {
    camera.position.setLength(cameraRadius);
    camera.lookAt(0, 0, 0);
  }
}

function addLatLonGrid() {
  for (let lat = -60; lat <= 60; lat += 30) {
    const pts = [];
    for (let lon = -180; lon <= 180; lon += 6) pts.push(latLonToVector(lon, lat, 2.075));
    globeGroup.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), gridMaterial()));
  }
  for (let lon = -150; lon <= 180; lon += 30) {
    const pts = [];
    for (let lat = -80; lat <= 80; lat += 4) pts.push(latLonToVector(lon, lat, 2.078));
    globeGroup.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), gridMaterial()));
  }
}

function gridMaterial() {
  return new THREE.LineBasicMaterial({ color: 0x7bbcff, transparent: true, opacity: 0.14 });
}

function rebuildScene() {
  if (!globeGroup || !pointGroup || !routeGroup || !heatGroup) return;
  clearGroup(pointGroup); clearGroup(routeGroup); clearGroup(heatGroup);
  pointObjects.splice(0, pointObjects.length);

  if (props.state.activeLayers.includes('heatmap')) {
    points.value.forEach(p => heatGroup.add(heatHalo(p)));
  }
  if (props.state.activeLayers.includes('routes')) {
    routes.value.forEach(r => routeGroup.add(routeArc(r)));
  }
  if (props.state.activeLayers.includes('suppliers') || props.state.activeLayers.includes('ports')) {
    points.value.forEach(p => {
      const m = pointMarker(p);
      pointObjects.push(m);
      pointGroup.add(m);
    });
  }
}

function clearGroup(group) {
  while (group.children.length) {
    const child = group.children.pop();
    child.geometry?.dispose();
    child.material?.dispose();
  }
}

function pointMarker(point) {
  const m = new THREE.Mesh(
    new THREE.SphereGeometry(0.055, 18, 18),
    new THREE.MeshBasicMaterial({ color: riskColor(point.riskIndex) })
  );
  m.position.copy(latLonToVector(point.lon, point.lat, 2.19));
  m.userData.point = point;
  return m;
}

function heatHalo(point) {
  const halo = new THREE.Mesh(
    new THREE.SphereGeometry(point.riskIndex >= 80 ? 0.18 : 0.12, 24, 24),
    new THREE.MeshBasicMaterial({ color: riskColor(point.riskIndex), transparent: true, opacity: point.riskIndex >= 80 ? 0.28 : 0.16 })
  );
  halo.position.copy(latLonToVector(point.lon, point.lat, 2.17));
  return halo;
}

function routeArc(route) {
  const from = latLonToVector(route.fromLon, route.fromLat, 2.18);
  const to = latLonToVector(route.toLon, route.toLat, 2.18);
  const mid = from.clone().add(to).normalize().multiplyScalar(2.92);
  const curve = new THREE.QuadraticBezierCurve3(from, mid, to);
  return new THREE.Line(
    new THREE.BufferGeometry().setFromPoints(curve.getPoints(72)),
    new THREE.LineBasicMaterial({ color: riskColor(route.riskIndex), transparent: true, opacity: 0.82 })
  );
}

function latLonToVector(lon, lat, radius) {
  const phi = (90 - Number(lat)) * Math.PI / 180;
  const theta = (Number(lon) + 180) * Math.PI / 180;
  return new THREE.Vector3(
    -radius * Math.sin(phi) * Math.cos(theta),
    radius * Math.cos(phi),
    radius * Math.sin(phi) * Math.sin(theta)
  );
}

function animate() {
  animationId = requestAnimationFrame(animate);
  if (!isDragging && globeGroup) {
    // Auto-rotate with velocity damping
    rotationVelocity.y *= 0.95;
    rotationVelocity.x *= 0.95;
    globeGroup.rotation.y += (Math.abs(rotationVelocity.y) > 0.0001 ? rotationVelocity.y : 0.0025);
    globeGroup.rotation.x += rotationVelocity.x;
    globeGroup.rotation.x = Math.max(-1.3, Math.min(1.3, globeGroup.rotation.x));
  }
  renderer?.render(scene, camera);
}

function layerName(layer) {
  return { heatmap: '风险热力光晕', suppliers: '供应商/企业点位', ports: '港口/航道节点', routes: '物流路径弧线' }[layer] || layer;
}
function riskClass(score) { return score >= 80 ? 'high' : score >= 60 ? 'mid' : 'low'; }
function riskColor(score) { return score >= 80 ? 0xff5f6d : score >= 60 ? 0xfacc15 : 0x60a5fa; }
</script>
