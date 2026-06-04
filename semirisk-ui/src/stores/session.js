import { defineStore } from 'pinia';

export const useSessionStore = defineStore('session', {
  state: () => ({
    session: JSON.parse(localStorage.getItem('semiriskUser') || 'null')
  }),
  actions: {
    setSession(session) {
      this.session = session;
      if (session) localStorage.setItem('semiriskUser', JSON.stringify(session));
      else localStorage.removeItem('semiriskUser');
    }
  }
});
