import { alertApi } from '../api/modules';

export const ALERT_STATUS = {
  UNHANDLED: '未处理',
  PROCESSING: '处理中',
  HANDLED: '已处理',
  IGNORED: '已忽略'
};

export function useAlerts(state, notify) {
  async function loadAlerts() {
    const params = new URLSearchParams();
    if (state.alertFilter.keyword) params.set('keyword', state.alertFilter.keyword);
    if (state.alertFilter.level) params.set('level', state.alertFilter.level);
    if (state.alertFilter.status) params.set('status', state.alertFilter.status);
    state.alerts = await alertApi.list(params);
    state.selectedAlertIds = state.selectedAlertIds.filter(id =>
      state.alerts.some(alert => alert.id === id && alert.status === ALERT_STATUS.UNHANDLED)
    );
  }

  async function ignoreAlert(id) {
    const alert = state.alerts.find(item => item.id === id);
    if (alert && alert.status !== ALERT_STATUS.UNHANDLED) {
      notify(`当前状态为“${alert.status}”，不能重复忽略`);
      return;
    }
    await alertApi.ignore(id);
    state.selectedAlertIds = state.selectedAlertIds.filter(item => item !== id);
    await loadAlerts();
    notify('告警已忽略');
  }

  async function restoreAlert(id) {
    const alert = state.alerts.find(item => item.id === id);
    if (alert && alert.status !== ALERT_STATUS.IGNORED) {
      notify(`当前状态为“${alert.status}”，只有已忽略告警可以恢复`);
      return;
    }
    await alertApi.restore(id);
    await loadAlerts();
    notify('告警已恢复为未处理');
  }

  async function handleAlert(id) {
    const alert = state.alerts.find(item => item.id === id);
    if (alert && (alert.status === ALERT_STATUS.IGNORED || alert.status === ALERT_STATUS.HANDLED)) {
      notify(`当前状态为“${alert.status}”，不能标记为已处理`);
      return;
    }
    await alertApi.handle(id);
    state.selectedAlertIds = state.selectedAlertIds.filter(item => item !== id);
    await loadAlerts();
    notify('告警已标记为已处理');
  }

  async function batchProcess() {
    const actionableIds = state.selectedAlertIds.filter(id =>
      state.alerts.some(alert => alert.id === id && alert.status === ALERT_STATUS.UNHANDLED)
    );
    if (!actionableIds.length) {
      notify('请先选择未处理的告警');
      return;
    }
    const result = await alertApi.batchProcess(actionableIds);
    state.selectedAlertIds = [];
    await loadAlerts();
    notify(`批量处理完成：处理 ${result.processed || 0} 条，跳过 ${result.skipped || 0} 条`);
  }

  function toggleAllAlerts(checked) {
    state.selectedAlertIds = checked
      ? state.alerts.filter(alert => alert.status === ALERT_STATUS.UNHANDLED).map(alert => alert.id)
      : [];
  }

  return { batchProcess, handleAlert, ignoreAlert, loadAlerts, restoreAlert, toggleAllAlerts };
}
