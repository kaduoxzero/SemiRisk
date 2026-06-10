import { riskApi } from '../api/modules';

export function useRisk(state, notify) {
  // 缓存上一次的分析结果，避免重复切换时重新请求
  const analysisCache = new Map();
  let lastFetchTime = 0;
  const CACHE_TTL = 30_000; // 30 秒内认为数据有效
  let loading = false;

  async function loadRiskAnalysis(windowName = '24h') {
    state.analysisWindow = windowName;

    // 如果缓存有效且未到 TTL，直接使用缓存数据
    const cached = analysisCache.get(windowName);
    if (cached && Date.now() - lastFetchTime < CACHE_TTL) {
      state.analysis = cached;
      return;
    }

    // 防止重复请求
    if (loading) return;
    loading = true;
    try {
      const startTime = Date.now();
      state.analysis = await riskApi.analysis(windowName);
      const elapsed = Date.now() - startTime;

      // 只缓存这次请求的结果
      analysisCache.set(windowName, state.analysis);
      lastFetchTime = Date.now();

      // 清理过期缓存（保留最近 3 个窗口的缓存）
      if (analysisCache.size > 3) {
        const keys = [...analysisCache.keys()];
        analysisCache.delete(keys[0]);
      }
    } finally {
      loading = false;
    }
  }

  async function loadRiskDetail(id = state.alerts[0]?.id || '') {
    if (!id) {
      state.riskDetail = {
        alertOnly: true,
        id: '',
        title: '请先从预警中心选择一条公开源告警',
        titleEn: 'Select one public-source alert from Alert Center first',
        level: '待采集',
        status: '未选择'
      };
      return;
    }
    state.riskDetail = await riskApi.detail(id);
  }

  async function assignRisk() {
    await riskApi.assign(state.riskDetail.id, 'Vue 指派专员');
    notify('负责人已指派');
    await loadRiskDetail(state.riskDetail.id);
  }

  // Expose loading state via state object for UI binding
  const _origLoad = loadRiskAnalysis;
  async function loadRiskAnalysisWrapper(windowName) {
    state.analysisLoading = true;
    try {
      await _origLoad(windowName);
    } finally {
      state.analysisLoading = false;
    }
  }

  return { assignRisk, loadRiskAnalysis: loadRiskAnalysisWrapper, loadRiskDetail };
}
