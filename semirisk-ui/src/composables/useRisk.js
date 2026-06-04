import { riskApi } from '../api/modules';

export function useRisk(state, notify) {
  async function loadRiskAnalysis(windowName = '24h') {
    state.analysisWindow = windowName;
    state.analysis = await riskApi.analysis(windowName);
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

  return { assignRisk, loadRiskAnalysis, loadRiskDetail };
}
