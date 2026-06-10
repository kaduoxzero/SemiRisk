import { reportApi } from '../api/modules';

export function useReports(state, notify) {
  let pollTimer = null;

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  async function loadReportTemplates() {
    state.reportTemplates = await reportApi.templates();
  }

  async function startReport() {
    stopPolling(); // 清理旧轮询
    state.reportJob = await reportApi.createJob({
      ...state.reportForm,
      threshold: 70
    });
    pollReport(state.reportJob.id);
  }

  async function pollReport(id) {
    try {
      state.reportJob = await reportApi.job(id);
    } catch {
      // 轮询失败，5 秒后重试
      pollTimer = setTimeout(() => pollReport(id), 5000);
      return;
    }
    if ((state.reportJob.progress || 0) < 100) {
      // 指数退避：3s → 5s → 8s，避免频繁轮询
      const delay = Math.min(8000, 3000 + (state.reportJob.progress || 0) * 50);
      pollTimer = setTimeout(() => pollReport(id), delay);
    } else {
      stopPolling();
      notify('报告已生成，可点击下载');
    }
  }

  return { loadReportTemplates, startReport, stopPolling };
}
