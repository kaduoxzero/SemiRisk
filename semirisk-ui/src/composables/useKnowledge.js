import { knowledgeApi } from '../api/modules';

export function useKnowledge(state) {
  async function searchKnowledge() {
    state.knowledge = await knowledgeApi.search(state.knowledgeQuery);
  }

  async function askKnowledge() {
    if (!state.knowledgeQuestion.trim()) return;
    state.knowledgeLoading = true;
    try {
      state.knowledgeAnswer = await knowledgeApi.ask(state.knowledgeQuestion);
    } finally {
      state.knowledgeLoading = false;
    }
  }

  return { askKnowledge, searchKnowledge };
}
