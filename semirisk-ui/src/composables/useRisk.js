import { riskApi } from '../api/modules';

export function useRisk(state, notify) {
  async function loadRiskAnalysis(windowName = '24h') {
    state.analysis = await riskApi.analysis(windowName);
  }

  async function loadRiskDetail(id = 'RA-20260603-001') {
    state.riskDetail = await riskApi.detail(id);
  }

  async function assignRisk() {
    await riskApi.assign(state.riskDetail.id, 'Vue 指派专员');
    notify('负责人已指派');
    await loadRiskDetail(state.riskDetail.id);
  }

  return { assignRisk, loadRiskAnalysis, loadRiskDetail };
}
