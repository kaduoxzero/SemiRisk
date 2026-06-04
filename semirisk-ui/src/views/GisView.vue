<template>
  <section class="grid cols-2">
    <div class="panel">
      <h3>图层管理</h3>
      <label v-for="layer in state.layers" :key="layer">
        <input v-model="state.activeLayers" :value="layer" type="checkbox" @change="actions.loadGis" /> {{ layer }}
      </label>
    </div>
    <div class="panel">
      <h3>风险点位</h3>
      <p v-for="point in state.gis.points || []" :key="point.name">{{ point.name }} · 风险指数 {{ point.riskIndex }} · {{ point.analysis }}</p>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
