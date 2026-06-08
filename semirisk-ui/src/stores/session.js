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
  let session;
  try {
    session = JSON.parse(localStorage.getItem('semiriskUser') || 'null');
  } catch {
    localStorage.removeItem('semiriskUser');
    return null;
  }
  if (!session?.token || !session?.expiresAt) {
    localStorage.removeItem('semiriskUser');
    return null;
  }
  if (new Date(session.expiresAt).getTime() <= Date.now()) {
    localStorage.removeItem('semiriskUser');
    return null;
  }
  return session;
}
