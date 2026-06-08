import { enterpriseApi } from '../api/modules';

export function useEnterprise(state) {
  async function loadEnterpriseList() {
    state.enterpriseList = await enterpriseApi.list();
    if (!state.enterpriseKeyword && state.enterpriseList[0]?.name) {
      state.enterpriseKeyword = state.enterpriseList[0].name;
    }
  }

  async function loadEnterprise() {
    if (!state.enterpriseList.length) {
      await loadEnterpriseList();
    }
    state.enterprise = await enterpriseApi.profile(state.enterpriseKeyword);
    if (Array.isArray(state.enterprise.catalog)) {
      state.enterpriseList = state.enterprise.catalog;
    }
    state.enterprisePage = 1;
  }

  async function selectEnterprise(item) {
    state.enterpriseKeyword = item.name;
    await loadEnterprise();
  }

  return { loadEnterprise, loadEnterpriseList, selectEnterprise };
}
