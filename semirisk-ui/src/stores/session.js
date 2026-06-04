import { defineStore } from 'pinia';

export const useSessionStore = defineStore('session', {
  state: () => ({
    session: readSession()
  }),
  actions: {
    setSession(session) {
      this.session = session;
      if (session) localStorage.setItem('semiriskUser', JSON.stringify(session));
      else localStorage.removeItem('semiriskUser');
    }
  }
});

function readSession() {
  const session = JSON.parse(localStorage.getItem('semiriskUser') || 'null');
  if (!session?.token || !session?.expiresAt) return session;
  if (new Date(session.expiresAt).getTime() <= Date.now()) {
    localStorage.removeItem('semiriskUser');
    return null;
  }
  return session;
}
