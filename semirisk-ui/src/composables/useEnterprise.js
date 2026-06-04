import { enterpriseApi } from '../api/modules';

export function useEnterprise(state) {
  async function loadEnterprise() {
    state.enterprise = await enterpriseApi.profile(state.enterpriseKeyword);
  }

  return { loadEnterprise };
}
