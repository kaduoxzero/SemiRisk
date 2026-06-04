import { alertApi } from '../api/modules';

export function useAlerts(state, notify) {
  async function loadAlerts() {
    const params = new URLSearchParams();
    if (state.alertFilter.keyword) params.set('keyword', state.alertFilter.keyword);
    if (state.alertFilter.level) params.set('level', state.alertFilter.level);
    if (state.alertFilter.status) params.set('status', state.alertFilter.status);
    state.alerts = await alertApi.list(params);
    state.selectedAlertIds = state.selectedAlertIds.filter(id => state.alerts.some(alert => alert.id === id));
  }

  async function ignoreAlert(id) {
    await alertApi.ignore(id);
    state.selectedAlertIds = state.selectedAlertIds.filter(item => item !== id);
    await loadAlerts();
    notify('告警已忽略');
  }

  async function batchProcess() {
    if (!state.selectedAlertIds.length) {
      notify('请先选择需要批量处理的告警');
      return;
    }
    await alertApi.batchProcess(state.selectedAlertIds);
    state.selectedAlertIds = [];
    await loadAlerts();
    notify('批量处理完成');
  }

  function toggleAllAlerts(checked) {
    state.selectedAlertIds = checked ? state.alerts.map(alert => alert.id) : [];
  }

  return { batchProcess, ignoreAlert, loadAlerts, toggleAllAlerts };
}
