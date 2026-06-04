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
    else notify('报告已生成，可通过后端下载接口获取');
  }

  return { loadReportTemplates, startReport };
}
