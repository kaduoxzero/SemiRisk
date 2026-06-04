import { authApi } from '../api/modules';

export function useAuth(state, allowedNavItems, notify, loadDashboard, setSession) {
  async function login() {
    const data = await authApi.login({ ...state.loginForm, captchaToken: 'vue-slider-ok' });
    setSession(data.user);
    if (!allowedNavItems.value.some(item => item.key === state.view)) {
      state.view = allowedNavItems.value[0]?.key || 'dashboard';
    }
    notify('登录成功');
    await loadDashboard();
  }

  async function register() {
    if (!/^[1-9][0-9]{4,11}@qq\.com$/i.test(state.registerForm.email || '')) {
      notify('请使用 QQ 邮箱注册，例如 123456@qq.com');
      return;
    }
    const data = await authApi.register(state.registerForm);
    setSession(data.user);
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
    setSession(null);
    state.view = 'dashboard';
  }

  async function restoreSession() {
    if (!state.session) return false;
    try {
      const me = await authApi.me();
      setSession({ ...state.session, ...me });
      return true;
    } catch {
      setSession(null);
      return false;
    }
  }

  return { login, logout, register, resetPassword, restoreSession };
}
