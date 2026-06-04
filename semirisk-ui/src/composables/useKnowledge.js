import { knowledgeApi } from '../api/modules';

export function useKnowledge(state) {
  async function searchKnowledge() {
    state.knowledge = await knowledgeApi.search(state.knowledgeQuery);
  }

  return { searchKnowledge };
}
