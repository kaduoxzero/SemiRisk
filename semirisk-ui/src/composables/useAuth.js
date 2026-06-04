import { authApi } from '../api/modules';

export function useAuth(state, allowedNavItems, notify, loadDashboard, setSession) {
  async function login() {
    if (!state.loginForm.username || !state.loginForm.password) {
      notify('请输入账号和密码');
      return;
    }
    try {
      const data = await authApi.login({ ...state.loginForm, captchaToken: 'vue-slider-ok' });
      setSession({ ...data.user, token: data.token, expiresAt: data.expiresAt });
      if (!allowedNavItems.value.some(item => item.key === state.view)) {
        state.view = allowedNavItems.value[0]?.key || 'dashboard';
      }
      notify('登录成功');
      await loadDashboard();
    } catch (error) {
      notify(error.message || '账号或密码错误');
    }
  }

  async function register() {
    if (!/^[A-Za-z0-9_]{3,32}$/.test(state.registerForm.username || '')) {
      notify('账号仅支持 3-32 位字母、数字或下划线');
      return;
    }
    if (!/^[1-9][0-9]{4,11}@qq\.com$/i.test(state.registerForm.email || '')) {
      notify('请使用 QQ 邮箱注册，例如 123456@qq.com');
      return;
    }
    if (!state.registerForm.displayName || state.registerForm.displayName.trim().length < 2) {
      notify('请输入至少 2 个字符的姓名/昵称');
      return;
    }
    if (!state.registerForm.password || state.registerForm.password.length < 8) {
      notify('密码至少 8 位');
      return;
    }
    try {
      const data = await authApi.register(state.registerForm);
      setSession({ ...data.user, token: data.token, expiresAt: data.expiresAt });
      state.authMode = 'login';
      notify('注册成功，已自动登录');
      await loadDashboard();
    } catch (error) {
      notify(error.message || '注册失败');
    }
  }

  async function resetPassword() {
    const email = state.registerForm.email;
    if (!/^[1-9][0-9]{4,11}@qq\.com$/i.test(email || '')) {
      notify('请先在注册邮箱输入框填写 QQ 邮箱');
      state.authMode = 'register';
      return;
    }
    try {
      const data = await authApi.requestPasswordReset(email);
      notify(`重置 Token 已生成：${data.token.slice(0, 8)}...`);
    } catch (error) {
      notify(error.message || '重置失败');
    }
  }

  async function logout() {
    try {
      await authApi.logout();
    } catch {
      // Token may already be expired; local state still needs clearing.
    }
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
