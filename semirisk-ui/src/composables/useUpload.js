import { uploadApi } from '../api/modules';

export function useUpload(state, notify) {
  function downloadTemplate() {
    window.open(uploadApi.templateUrl, '_blank');
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
    const source = new EventSource(uploadApi.logsUrl);
    source.addEventListener('log', event => state.logs.push(JSON.parse(event.data).message));
    source.onerror = () => source.close();
  }

  return { downloadTemplate, loadUploads, parseUploads, uploadFile };
}
