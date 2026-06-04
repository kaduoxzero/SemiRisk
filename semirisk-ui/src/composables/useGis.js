import { gisApi } from '../api/modules';

export function useGis(state) {
  async function loadGis() {
    state.gis = await gisApi.map(state.activeLayers);
  }

  return { loadGis };
}
