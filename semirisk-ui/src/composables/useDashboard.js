import { dashboardApi } from '../api/modules';

export function useDashboard(state, notify) {
  async function loadDashboard() {
    state.dashboard = await dashboardApi.overview();
  }

  async function recalculateRisk() {
    if (!state.session) {
      state.authMode = 'login';
      notify('请先登录后再触发重新测算');
      return;
    }
    await dashboardApi.recalculateRisk();
    await loadDashboard();
    notify('AI 风险测算已刷新');
  }

  return { loadDashboard, recalculateRisk };
}
