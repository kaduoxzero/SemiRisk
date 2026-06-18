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
    notify('文件已上传，AI 自动分析中');
    await loadUploads();
  }

  return { downloadTemplate, loadUploads, uploadFile };
}
