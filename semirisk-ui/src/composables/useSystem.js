import { systemApi } from '../api/modules';

export function useSystem(state, notify) {
  async function loadSystem() {
    state.system = await systemApi.overview();
  }

  async function loadAiConfig() {
    try {
      const data = await systemApi.getAiConfig();
      const configs = data && typeof data === 'object' ? data : {};
      const entry = Object.values(configs)[0];
      if (entry) {
        state.aiConfig.model = entry.model || state.aiConfig.model;
        state.aiConfig.endpoint = entry.endpoint || state.aiConfig.endpoint;
        // maskedApiKey from server; real key stays blank in UI unless user re-enters
        if (entry.configured) {
          state.aiConfigSaved = true;
        }
      }
    } catch {
      // backend not ready yet; keep defaults
    }
  }

  async function saveAiConfig() {
    await systemApi.saveAiConfig(state.aiConfig);
    state.aiConfigSaved = true;
    await loadSystem();
    notify('AI API Key 已保存，重启后自动加载');
  }

  async function pingModel() {
    const result = await systemApi.pingModel({ model: state.aiConfig.model, endpoint: state.aiConfig.endpoint });
    state.modelPing = result;
    notify(result?.reachable ? `模型 endpoint 可达，延迟 ${result.latencyMs}ms` : '模型 endpoint 暂不可达');
  }

  async function reconnectSource(name) {
    const result = await systemApi.reconnect(name);
    await loadSystem();
    notify(result?.reachable ? `${name} 连通正常（${result.latencyMs}ms）` : `${name} 不可达，请检查中间件`);
  }

  async function triggerAgent(name) {
    const result = await systemApi.triggerAgent(name);
    await loadSystem();
    notify(result?.result || 'Agent 已触发');
  }

  return { loadSystem, loadAiConfig, saveAiConfig, pingModel, reconnectSource, triggerAgent };
}
