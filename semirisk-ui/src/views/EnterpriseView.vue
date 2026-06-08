<template>
  <section class="grid">
    <div class="toolbar">
      <input v-model="state.enterpriseKeyword" class="input wide" placeholder="企业名称或统一社会信用代码" />
      <button class="btn" @click="actions.loadEnterprise">搜索画像</button>
    </div>

    <div class="enterprise-workspace">
      <div class="panel">
        <div class="toolbar compact-toolbar">
          <h3>已有企业</h3>
          <span class="muted">{{ state.enterpriseList.length }} 家</span>
        </div>
        <button
          v-for="item in state.enterpriseList"
          :key="item.creditCode || item.name"
          class="list-row enterprise-row"
          :class="{ active: item.name === state.enterprise.name }"
          @click="actions.selectEnterprise(item)"
        >
          <strong>{{ item.name }}</strong>
          <span class="badge" :class="riskClass(item.riskScore)">{{ item.riskScore }}</span>
          <em>{{ item.industry }} · {{ item.location || item.creditLevel }}</em>
        </button>
        <p v-if="!state.enterpriseList.length" class="muted">暂无本地企业列表，先执行搜索加载。</p>
      </div>

      <div class="enterprise-layout">
        <div class="panel">
          <div class="toolbar compact-toolbar">
            <h3>{{ state.enterprise.name }}</h3>
            <span class="badge" :class="riskClass(state.enterprise.riskScore)">{{ state.enterprise.sourceMode || '画像库' }}</span>
          </div>
          <p class="muted">{{ state.enterprise.creditCode }}</p>
          <div class="score-circle">{{ state.enterprise.riskScore || 0 }}</div>
          <p>信用等级：{{ state.enterprise.creditLevel }}</p>
          <p>行业：{{ state.enterprise.industry }}</p>
          <p>地区：{{ state.enterprise.location || '待核验' }}</p>
          <p class="muted">{{ state.enterprise.matchStatus }}</p>
          <div v-if="(state.enterprise.internetSearches || []).length" class="external-links">
            <a v-for="link in state.enterprise.internetSearches" :key="link.url" class="text-link" :href="link.url" target="_blank" rel="noreferrer">{{ link.name }}</a>
          </div>
        </div>

        <div class="panel fixed-panel">
          <div class="toolbar compact-toolbar">
            <h3>{{ pages[state.enterprisePage - 1]?.title }}</h3>
            <span class="muted">{{ state.enterprisePage }} / {{ pages.length }}</span>
          </div>
          <div v-if="state.enterprisePage === 1" class="kv-list">
            <p v-for="(value, key) in state.enterprise.business || {}" :key="key"><span>{{ key }}</span><strong>{{ value }}</strong></p>
          </div>
          <div v-else-if="state.enterprisePage === 2" class="radar-list">
            <p v-for="(value, index) in state.enterprise.radar || []" :key="index"><span>{{ radarLabels[index] }}</span><meter min="0" max="100" :value="value"></meter><strong>{{ value }}</strong></p>
          </div>
          <div v-else-if="state.enterprisePage === 3" class="topology-line">
            <span v-for="node in state.enterprise.topology || []" :key="node">{{ node }}</span>
          </div>
          <div v-else>
            <p v-for="event in state.enterprise.events || []" :key="event">{{ event }}</p>
            <p v-if="!(state.enterprise.events || []).length" class="muted">暂无公开源关联事件。</p>
          </div>
          <div class="pager">
            <button class="btn secondary" :disabled="state.enterprisePage <= 1" @click="state.enterprisePage--">上一页</button>
            <button class="btn secondary" :disabled="state.enterprisePage >= pages.length" @click="state.enterprisePage++">下一页</button>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <h3>公开源待核验信号</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>来源</th><th>维度</th><th>评分</th><th>事件</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="item in state.enterprise.publicSignals || []" :key="item.id">
              <td>{{ item.source }}</td>
              <td>{{ item.dimension }}</td>
              <td><span class="badge" :class="riskClass(item.riskScore)">{{ item.riskScore }}</span></td>
              <td><a class="text-link" :href="item.sourceUrl" target="_blank" rel="noreferrer">{{ item.title }}</a></td>
              <td>{{ item.fetchedAt }}</td>
            </tr>
            <tr v-if="!(state.enterprise.publicSignals || []).length">
              <td colspan="5" class="muted">本地公开源暂未命中该企业，已提供互联网查询入口。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const pages = [
  { title: '工商基础数据' },
  { title: '多维风险评估' },
  { title: '上下游拓扑' },
  { title: '历史事件' }
];
const radarLabels = ['财务状况', '运营能力', '合规风险', '技术实力', '供应链稳定性'];

function riskClass(score) {
  if (score >= 80) return 'high';
  if (score >= 60) return 'mid';
  return 'low';
}
</script>
