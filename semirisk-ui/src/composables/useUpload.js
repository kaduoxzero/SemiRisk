import { uploadApi } from '../api/modules';
import { authenticatedUrl } from '../api/client';

export function useUpload(state, notify) {
  let sseSource = null;

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
    // 关闭旧连接
    if (sseSource) sseSource.close();
    state.logs = [];

    let retryCount = 0;
    const maxRetries = 3;
    const baseDelay = 1500;

    function connect() {
      sseSource = new EventSource(authenticatedUrl(uploadApi.logsUrl));

      sseSource.addEventListener('log', event => {
        try {
          state.logs.push(JSON.parse(event.data).message);
        } catch {
          state.logs.push(String(event.data || '日志解析失败'));
        }
      });

      sseSource.onerror = () => {
        sseSource.close();
        sseSource = null;
        // 指数退避重连
        retryCount++;
        if (retryCount <= maxRetries) {
          const delay = baseDelay * Math.pow(2, retryCount - 1);
          setTimeout(connect, delay);
        }
      };

      sseSource.onopen = () => {
        retryCount = 0; // 连接成功，重置计数器
      };
    }

    connect();
  }

  return { downloadTemplate, loadUploads, parseUploads, uploadFile };
}
