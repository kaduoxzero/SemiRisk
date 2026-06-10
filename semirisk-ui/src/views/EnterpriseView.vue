<template>
  <section class="grid">
    <div class="toolbar">
      <input v-model="state.enterpriseKeyword" class="input wide" placeholder="企业名称 / 关键词" @keydown.enter="actions.loadEnterprise" />
      <button class="btn" :disabled="loading" @click="search">{{ loading ? '搜索中…' : '搜索画像' }}</button>
    </div>

    <div class="enterprise-workspace">
      <div class="panel">
        <div class="toolbar compact-toolbar">
          <h3>企业列表</h3>
          <span class="badge low">{{ state.enterpriseList.length }} 家</span>
        </div>
        <button
          v-for="item in state.enterpriseList"
          :key="item.creditCode || item.name"
          class="list-row enterprise-row"
          :class="{ active: item.name === state.enterprise.name }"
          @click="actions.selectEnterprise(item)"
        >
          <span class="badge" :class="riskClass(item.riskScore)">{{ item.riskScore }}</span>
          <div class="enterprise-row-info">
            <strong>{{ item.name }}</strong>
            <em>{{ item.industry }} · {{ item.location || item.creditLevel }}</em>
          </div>
        </button>
        <p v-if="!state.enterpriseList.length" class="muted" style="padding:8px 0">暂无企业列表，先执行搜索。</p>
      </div>

      <div class="enterprise-layout">
        <div class="panel enterprise-overview">
          <div class="toolbar compact-toolbar">
            <h3>{{ state.enterprise.name || '未选择企业' }}</h3>
            <span class="badge low">{{ state.enterprise.sourceMode || '公开观察名单' }}</span>
          </div>
          <p class="muted" style="font-size:12px">{{ state.enterprise.creditCode }}</p>
          <div class="score-circle-wrap">
            <div class="score-circle" :class="riskClass(state.enterprise.riskScore)">{{ state.enterprise.riskScore || '—' }}</div>
            <div class="score-circle-labels">
              <span>综合风险指数</span>
              <strong>{{ state.enterprise.creditLevel || '-' }}</strong>
            </div>
          </div>
          <div class="ent-kv">
            <div><span class="muted">行业</span><strong>{{ state.enterprise.industry || '-' }}</strong></div>
            <div><span class="muted">地区</span><strong>{{ state.enterprise.location || '待核验' }}</strong></div>
          </div>
          <p v-if="state.enterprise.matchStatus" class="muted" style="font-size:12px;margin-top:8px">{{ state.enterprise.matchStatus }}</p>
          <div v-if="(state.enterprise.internetSearches || []).length" class="external-links">
            <a v-for="link in state.enterprise.internetSearches" :key="link.url" class="text-link ext-link" :href="link.url" target="_blank" rel="noreferrer">
              ↗ {{ link.name }}
            </a>
          </div>
          <div v-if="(state.enterprise.internetSearchResults || []).length" class="search-results">
            <h4>联网搜索结果</h4>
            <div v-for="(r, i) in state.enterprise.internetSearchResults" :key="i" class="search-result-item">
              <a class="text-link" :href="r.url" target="_blank" rel="noreferrer">{{ r.title }}</a>
              <p class="muted">{{ r.snippet || r.source || '' }}</p>
              <span class="muted" style="font-size:11px">{{ r.date || r.source }}</span>
            </div>
          </div>
        </div>

        <div class="panel fixed-panel">
          <div class="toolbar compact-toolbar">
            <h3>{{ pages[state.enterprisePage - 1]?.title }}</h3>
            <div style="display:flex;gap:4px">
              <button v-for="(p, i) in pages" :key="p.title"
                class="btn secondary tiny"
                :class="{ active: state.enterprisePage === i + 1 }"
                @click="state.enterprisePage = i + 1">
                {{ i + 1 }}
              </button>
            </div>
          </div>

          <div v-if="state.enterprisePage === 1" class="kv-list">
            <div v-for="(value, key) in state.enterprise.business || {}" :key="key" class="kv-row">
              <span class="muted">{{ key }}</span><strong>{{ value }}</strong>
            </div>
            <p v-if="!Object.keys(state.enterprise.business || {}).length" class="muted">工商权威字段待接入权威源。</p>
          </div>

          <div v-else-if="state.enterprisePage === 2" class="radar-list">
            <div v-for="(value, index) in state.enterprise.radar || []" :key="index" class="bar-row">
              <span class="bar-label">{{ radarLabels[index] }}</span>
              <div class="bar-track">
                <div class="bar-fill" :class="riskClass(value)" :style="{ width: value + '%' }" />
              </div>
              <strong class="bar-value" :class="riskClass(value)">{{ value }}</strong>
            </div>
          </div>

          <div v-else-if="state.enterprisePage === 3" class="topology-chain">
            <span v-for="(node, i) in state.enterprise.topology || []" :key="node" class="topology-node">
              {{ node }}<span v-if="i < (state.enterprise.topology || []).length - 1" class="topology-arrow">→</span>
            </span>
          </div>

          <div v-else>
            <p v-for="event in state.enterprise.events || []" :key="event" class="event-line">{{ event }}</p>
            <p v-if="!(state.enterprise.events || []).length" class="muted">暂无公开源关联事件。</p>
          </div>

          <div class="pager" style="margin-top:12px">
            <button class="btn secondary" :disabled="state.enterprisePage <= 1" @click="state.enterprisePage--">上一页</button>
            <button class="btn secondary" :disabled="state.enterprisePage >= pages.length" @click="state.enterprisePage++">下一页</button>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar compact-toolbar">
        <h3>公开源关联信号</h3>
        <span class="badge low">{{ (state.enterprise.publicSignals || []).length }} 条</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>来源</th><th>维度</th><th>评分</th><th>事件</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="item in state.enterprise.publicSignals || []" :key="item.id">
              <td>{{ item.source }}</td>
              <td>{{ item.dimension }}</td>
              <td><span class="badge" :class="riskClass(item.riskScore)">{{ item.riskScore }}</span></td>
              <td><a class="text-link" :href="item.sourceUrl" target="_blank" rel="noreferrer">{{ item.title }}</a></td>
              <td class="muted" style="font-size:12px;white-space:nowrap">{{ item.fetchedAt }}</td>
            </tr>
            <tr v-if="!(state.enterprise.publicSignals || []).length">
              <td colspan="5" class="muted" style="text-align:center;padding:20px">本地公开源暂未命中，已提供互联网查询入口。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const loading = ref(false);

async function search() {
  loading.value = true;
  try { await props.actions.loadEnterprise(); } finally { loading.value = false; }
}

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
