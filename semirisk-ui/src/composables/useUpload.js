import { uploadApi } from '../api/modules';
import { authenticatedUrl } from '../api/client';

export function useUpload(state, notify) {
  function downloadTemplate() {
    window.open(authenticatedUrl(uploadApi.templateUrl), '_blank');
  }

  async function loadUploads() {
    state.uploads = await uploadApi.list();
  }

  async function uploadFile(event) {
    const file = event.target.files[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    await uploadApi.upload(form);
    notify('文件已进入清洗队列');
    await loadUploads();
    streamLogs();
  }

  async function parseUploads() {
    await Promise.all(state.uploads.map(task => uploadApi.parse(task.id)));
    notify('AI 校验完成');
    await loadUploads();
  }

  function streamLogs() {
    state.logs = [];
    const source = new EventSource(authenticatedUrl(uploadApi.logsUrl));
    source.addEventListener('log', event => {
      try {
        state.logs.push(JSON.parse(event.data).message);
      } catch {
        state.logs.push(String(event.data || '日志解析失败'));
      }
    });
    source.onerror = () => source.close();
  }

  return { downloadTemplate, loadUploads, parseUploads, uploadFile };
}
