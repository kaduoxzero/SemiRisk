import { systemApi } from '../api/modules';

export function useSystem(state, notify) {
  async function loadSystem() {
    state.system = await systemApi.overview();
  }

  async function saveAiConfig() {
    await systemApi.saveAiConfig(state.aiConfig);
    await loadSystem();
    notify('AI API Key 已保存并脱敏展示');
  }

  return { loadSystem, saveAiConfig };
}
