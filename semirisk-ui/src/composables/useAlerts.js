import { alertApi } from '../api/modules';

export function useAlerts(state, notify) {
  async function loadAlerts() {
    const params = new URLSearchParams();
    if (state.alertFilter.keyword) params.set('keyword', state.alertFilter.keyword);
    if (state.alertFilter.level) params.set('level', state.alertFilter.level);
    state.alerts = await alertApi.list(params);
  }

  async function ignoreAlert(id) {
    await alertApi.ignore(id);
    await loadAlerts();
    notify('告警已忽略');
  }

  async function batchProcess() {
    await alertApi.batchProcess(state.alerts.map(alert => alert.id));
    await loadAlerts();
    notify('批量处理完成');
  }

  return { batchProcess, ignoreAlert, loadAlerts };
}
