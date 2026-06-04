import { authApi } from '../api/modules';

export function useAuth(state, allowedNavItems, notify, loadDashboard) {
  async function login() {
    const data = await authApi.login({ ...state.loginForm, captchaToken: 'vue-slider-ok' });
    localStorage.setItem('semiriskUser', JSON.stringify(data.user));
    state.session = data.user;
    if (!allowedNavItems.value.some(item => item.key === state.view)) {
      state.view = allowedNavItems.value[0]?.key || 'dashboard';
    }
    notify('登录成功');
    await loadDashboard();
  }

  async function register() {
    const data = await authApi.register(state.registerForm);
    localStorage.setItem('semiriskUser', JSON.stringify(data.user));
    state.session = data.user;
    state.authMode = 'login';
    notify('注册成功，已自动登录');
    await loadDashboard();
  }

  async function resetPassword() {
    const data = await authApi.requestPasswordReset('admin@risk.com');
    notify(`重置 Token 已生成：${data.token.slice(0, 8)}...`);
  }

  async function logout() {
    await authApi.logout();
    localStorage.removeItem('semiriskUser');
    state.session = null;
    state.view = 'dashboard';
  }

  async function restoreSession() {
    if (!state.session) return false;
    try {
      const me = await authApi.me();
      state.session = { ...state.session, ...me };
      localStorage.setItem('semiriskUser', JSON.stringify(state.session));
      return true;
    } catch {
      localStorage.removeItem('semiriskUser');
      state.session = null;
      return false;
    }
  }

  return { login, logout, register, resetPassword, restoreSession };
}
