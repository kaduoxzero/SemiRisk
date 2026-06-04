import { reportApi } from '../api/modules';

export function useReports(state, notify) {
  async function loadReportTemplates() {
    state.reportTemplates = await reportApi.templates();
  }

  async function startReport() {
    state.reportJob = await reportApi.createJob({ ...state.reportForm, threshold: 70 });
    pollReport(state.reportJob.id);
  }

  async function pollReport(id) {
    state.reportJob = await reportApi.job(id);
    if (state.reportJob.progress < 100) setTimeout(() => pollReport(id), 900);
    else notify('报告已生成，可下载对应格式文件');
  }

  function downloadReport() {
    if (!state.reportJob?.downloadUrl) {
      notify('报告尚未生成完成');
      return;
    }
    window.location.href = state.reportJob.downloadUrl;
  }

  return { loadReportTemplates, startReport, downloadReport };
}
