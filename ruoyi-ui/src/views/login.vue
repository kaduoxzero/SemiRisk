<template>
  <div class="login-container bg-cyber-bg text-slate-200 min-h-screen flex items-center justify-center overflow-hidden relative">
    <!-- SVG 科技网格背景 -->
    <div class="fixed inset-0 pointer-events-none opacity-20 z-0">
      <svg height="100%" width="100%" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <pattern id="grid" width="100" height="100" patternUnits="userSpaceOnUse">
            <path d="M 100 0 L 0 0 0 100" fill="none" stroke="#3b82f6" stroke-width="0.5"></path>
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid)"></rect>
      </svg>
    </div>

    <!-- 粒子背景组件 -->
    <cyber-particles />

    <div class="relative z-10 w-full max-w-md px-6">
      <div class="hud-card p-8 space-y-8">
        <div class="corner-br"></div><div class="corner-bl"></div>

        <div class="text-center space-y-2">
          <div class="flex justify-center">
            <div class="w-16 h-16 rounded-2xl bg-primary/20 flex items-center justify-center border border-primary/50 shadow-[0_0_20px_rgba(59,130,246,0.5)]">
              <Icon icon="lucide:shield-check" class="text-4xl text-primary" />
            </div>
          </div>
          <h1 class="text-xl font-bold tracking-tight text-white mt-4">供应链风险智能管理系统</h1>
          <p class="text-xs text-slate-400 font-mono uppercase tracking-widest">Risk Management Hub v2.0</p>
        </div>

        <!-- 登录表单 -->
        <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="space-y-6">
          <el-form-item prop="username">
            <div class="relative w-full">
              <Icon icon="lucide:user" class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 z-10" />
              <input v-model="loginForm.username" class="cyber-input w-full pl-10 pr-4 py-2.5 rounded-lg text-sm" placeholder="请输入管理员用户名" />
            </div>
          </el-form-item>

          <el-form-item prop="password">
            <div class="relative w-full">
              <Icon icon="lucide:lock" class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 z-10" />
              <input v-model="loginForm.password" type="password" class="cyber-input w-full pl-10 pr-4 py-2.5 rounded-lg text-sm" placeholder="请输入密码" />
            </div>
          </el-form-item>

          <!-- 保持若依原型的登录按钮事件逻辑 handleLogin -->
          <button @click.prevent="handleLogin" class="w-full bg-primary hover:bg-blue-600 text-white font-bold py-3 rounded-lg shadow-[0_0_15px_rgba(59,130,246,0.4)] transition-all transform active:scale-95 flex items-center justify-center space-x-2">
            <span>进入中枢控制台</span>
            <Icon icon="lucide:arrow-right" />
          </button>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { getCodeImg, getTenantList } from '@/api/login';
import { authRouterUrl } from '@/api/system/social/auth';
import { useUserStore } from '@/store/modules/user';
import { LoginData, TenantVO } from '@/api/types';
import { to } from 'await-to-js';
import { HttpStatus } from '@/enums/RespEnum';
import { useI18n } from 'vue-i18n';
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import CyberParticles from '@/components/CyberParticles/index.vue'

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const title = import.meta.env.VITE_APP_TITLE;
const userStore = useUserStore();
const router = useRouter();
const { t } = useI18n();

const loginForm = ref<LoginData>({
  tenantId: '000000',
  username: 'admin',
  password: 'admin123',
  rememberMe: false,
  code: '',
  uuid: ''
} as LoginData);

const loginRules: ElFormRules = {
  tenantId: [{ required: true, trigger: 'blur', message: t('login.rule.tenantId.required') }],
  username: [{ required: true, trigger: 'blur', message: t('login.rule.username.required') }],
  password: [{ required: true, trigger: 'blur', message: t('login.rule.password.required') }],
  code: [{ required: true, trigger: 'change', message: t('login.rule.code.required') }]
};

const codeUrl = ref('');
const loading = ref(false);
// 验证码开关
const captchaEnabled = ref(true);
// 租户开关
const tenantEnabled = ref(true);

// 注册开关
const register = ref(false);
const redirect = ref('/');
const loginRef = ref<ElFormInstance>();
// 租户列表
const tenantList = ref<TenantVO[]>([]);

watch(
  () => router.currentRoute.value,
  (newRoute: any) => {
    redirect.value = newRoute.query && newRoute.query.redirect && decodeURIComponent(newRoute.query.redirect);
  },
  { immediate: true }
);

const handleLogin = () => {
  loginRef.value?.validate(async (valid: boolean, fields: any) => {
    if (valid) {
      loading.value = true;
      // 勾选了需要记住密码设置在 localStorage 中设置记住用户名和密码
      if (loginForm.value.rememberMe) {
        localStorage.setItem('tenantId', String(loginForm.value.tenantId));
        localStorage.setItem('username', String(loginForm.value.username));
        localStorage.setItem('password', String(loginForm.value.password));
        localStorage.setItem('rememberMe', String(loginForm.value.rememberMe));
      } else {
        // 否则移除
        localStorage.removeItem('tenantId');
        localStorage.removeItem('username');
        localStorage.removeItem('password');
        localStorage.removeItem('rememberMe');
      }
      // 调用action的登录方法
      const [err] = await to(userStore.login(loginForm.value));
      if (!err) {
        const redirectUrl = redirect.value || '/';
        await router.push(redirectUrl);
        loading.value = false;
      } else {
        loading.value = false;
        // 重新获取验证码
        if (captchaEnabled.value) {
          await getCode();
        }
      }
    } else {
      console.log('error submit!', fields);
    }
  });
};

/**
 * 获取验证码
 */
const getCode = async () => {
  const res = await getCodeImg();
  const { data } = res;
  captchaEnabled.value = data.captchaEnabled === undefined ? true : data.captchaEnabled;
  if (captchaEnabled.value) {
    // 刷新验证码时清空输入框
    loginForm.value.code = '';
    codeUrl.value = 'data:image/gif;base64,' + data.img;
    loginForm.value.uuid = data.uuid;
  }
};

const getLoginData = () => {
  const tenantId = localStorage.getItem('tenantId');
  const username = localStorage.getItem('username');
  const password = localStorage.getItem('password');
  const rememberMe = localStorage.getItem('rememberMe');
  loginForm.value = {
    tenantId: tenantId === null ? String(loginForm.value.tenantId) : tenantId,
    username: username === null ? String(loginForm.value.username) : username,
    password: password === null ? String(loginForm.value.password) : String(password),
    rememberMe: rememberMe === null ? false : Boolean(rememberMe)
  } as LoginData;
};

/**
 * 获取租户列表
 */
const initTenantList = async () => {
  const { data } = await getTenantList(false);
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  if (tenantEnabled.value) {
    tenantList.value = data.voList;
    if (tenantList.value != null && tenantList.value.length !== 0) {
      loginForm.value.tenantId = tenantList.value[0].tenantId;
    }
  }
};

/**
 * 第三方登录
 * @param type
 */
const doSocialLogin = (type: string) => {
  authRouterUrl(type, loginForm.value.tenantId).then((res: any) => {
    if (res.code === HttpStatus.SUCCESS) {
      // 获取授权地址跳转
      window.location.href = res.data;
    } else {
      ElMessage.error(res.msg);
    }
  });
};

onMounted(() => {
  getCode();
  initTenantList();
  getLoginData();
});
</script>

<style lang="scss" scoped>
.login {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background-image: url('../assets/images/login-background.jpg');
  background-size: cover;
  background-position: center;
}

.title-box {
  display: flex;
  align-items: center;
  gap: 8px;

  .title {
    margin: 0px auto 26px auto;
    text-align: center;
    color: var(--el-text-color-primary);
    font-weight: 600;
    letter-spacing: 0.5px;
  }

  :deep(.lang-select--style) {
    line-height: 0;
    color: var(--el-text-color-secondary);
  }
}

.login-form {
  border-radius: var(--app-radius-lg);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.5);
  width: min(420px, 90vw);
  padding: 32px 30px 12px 30px;
  z-index: 1;
  box-shadow: var(--app-shadow-lg);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  .el-input {
    height: 40px;
    input {
      height: 40px;
    }
  }

  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 0px;
  }
}

.login-tip {
  font-size: 13px;
  text-align: center;
  color: #bfbfbf;
}

.login-form :deep(.el-input__wrapper) {
  background-color: rgba(255, 255, 255, 0.9);
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
}

.login-form :deep(.el-button--primary) {
  border-radius: var(--app-radius-md);
  box-shadow: 0 8px 20px rgba(59, 130, 246, 0.25);
}

.login-form :deep(.el-button.is-circle) {
  background: rgba(15, 23, 42, 0.04);
  border: 1px solid rgba(15, 23, 42, 0.08);
  color: var(--el-text-color-regular);
}

.login-form :deep(.el-button.is-circle:hover) {
  background: rgba(59, 130, 246, 0.1);
  border-color: rgba(59, 130, 246, 0.2);
}

.login-code {
  width: calc(37% - 10px);
  height: 40px;
  float: right;
  margin-left: 10px;
  box-sizing: border-box;
  border-radius: var(--app-radius-sm);
  overflow: hidden;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid var(--el-border-color-light);

  img {
    cursor: pointer;
    vertical-align: middle;
    display: block;
    width: 100%;
    height: 40px;
    object-fit: cover;
  }
}

.el-login-footer {
  height: 40px;
  line-height: 40px;
  position: fixed;
  bottom: 0;
  width: 100%;
  text-align: center;
  color: rgba(255, 255, 255, 0.75);
  font-family: Arial, serif;
  font-size: 12px;
  letter-spacing: 1px;
}

.login-code-img {
  height: 40px;
  padding-left: 0;
}

:global(html.dark) {
  .login-form {
    background: rgba(17, 24, 39, 0.9);
    border-color: rgba(148, 163, 184, 0.2);
  }

  .login-form :deep(.el-input__wrapper) {
    background-color: rgba(17, 24, 39, 0.7);
  }

  .login-form :deep(.el-button.is-circle) {
    background: rgba(148, 163, 184, 0.12);
    border-color: rgba(148, 163, 184, 0.25);
    color: #e5e7eb;
  }

  .el-login-footer {
    color: rgba(226, 232, 240, 0.65);
  }
}
</style>
