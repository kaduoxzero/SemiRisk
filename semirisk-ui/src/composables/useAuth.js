import { authApi } from '../api/modules';

export function useAuth(state, allowedNavItems, notify, loadDashboard, setSession) {
  async function login() {
    if (!state.loginForm.username || !state.loginForm.password) {
      notify('请输入账号和密码');
      return;
    }
    if (state.authSubmitting) return;
    state.authSubmitting = true;
    try {
      const data = await authApi.login({ ...state.loginForm, captchaToken: 'vue-slider-ok' });
      const modules = data.user?.modules || [];
      setSession({ ...data.user, token: data.token, expiresAt: data.expiresAt, modules, active: modules.length > 0 });
      if (!allowedNavItems.value.some(item => item.key === state.view)) {
        state.view = allowedNavItems.value[0]?.key || 'dashboard';
      }
      notify('登录成功');
      await loadDashboard();
    } catch (error) {
      notify(error.message || '账号或密码错误');
    } finally {
      state.authSubmitting = false;
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
    if (!state.registerForm.verificationCode || state.registerForm.verificationCode.trim().length !== 6) {
      notify('请输入 6 位验证码');
      return;
    }
    if (state.authSubmitting) return;
    state.authSubmitting = true;
    try {
      const data = await authApi.register({
        username: state.registerForm.username,
        email: state.registerForm.email,
        displayName: state.registerForm.displayName,
        password: state.registerForm.password,
        verificationCode: state.registerForm.verificationCode
      });
      const modules = data.user?.modules || [];
      setSession({ ...data.user, token: data.token, expiresAt: data.expiresAt, modules, active: modules.length > 0 });
      state.authMode = 'login';
      notify('注册成功，已自动登录');
      await loadDashboard();
    } catch (error) {
      notify(error.message || '注册失败');
    } finally {
      state.authSubmitting = false;
    }
  }

  async function sendRegistrationCode() {
    const email = state.registerForm.email;
    if (!/^[1-9][0-9]{4,11}@qq\.com$/i.test(email || '')) {
      notify('请先输入有效的 QQ 邮箱');
      return;
    }
    if (state.authSubmitting) return;
    state.authSubmitting = true;
    try {
      await authApi.sendVerificationCode(email);
      notify('验证码已发送至您的邮箱');
    } catch (error) {
      notify(error.message || '发送验证码失败');
    } finally {
      state.authSubmitting = false;
    }
  }

  async function resetPassword() {
    const email = state.registerForm.email;
    if (!/^[1-9][0-9]{4,11}@qq\.com$/i.test(email || '')) {
      notify('请先输入注册时的 QQ 邮箱');
      state.authMode = 'register';
      return;
    }
    if (state.authSubmitting) return;
    state.authSubmitting = true;
    try {
      await authApi.requestPasswordReset(email);
      notify('重置验证码已发送至您的邮箱');
    } catch (error) {
      notify(error.message || '发送验证码失败');
    } finally {
      state.authSubmitting = false;
    }
  }

  async function confirmReset() {
    const email = state.registerForm.email;
    const resetCode = state.registerForm.resetCode;
    const newPassword = state.registerForm.newPassword;
    if (!/^[1-9][0-9]{4,11}@qq\.com$/i.test(email || '')) {
      notify('请输入注册时的 QQ 邮箱');
      return;
    }
    if (!resetCode || resetCode.trim().length !== 6) {
      notify('请输入 6 位验证码');
      return;
    }
    if (!newPassword || newPassword.length < 8) {
      notify('密码至少 8 位');
      return;
    }
    if (state.authSubmitting) return;
    state.authSubmitting = true;
    try {
      await authApi.confirmPasswordReset({ email, resetCode, newPassword });
      notify('密码重置成功，请使用新密码登录');
      state.authMode = 'login';
    } catch (error) {
      notify(error.message || '重置失败');
    } finally {
      state.authSubmitting = false;
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
      const modules = me.modules || state.session?.modules || [];
      setSession({ ...state.session, ...me, modules, active: modules.length > 0 });
      return true;
    } catch {
      setSession(null);
      return false;
    }
  }

  return { login, logout, register, sendRegistrationCode, resetPassword, confirmReset, restoreSession };
}
