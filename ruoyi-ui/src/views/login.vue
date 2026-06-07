<template>
  <div class="login-container bg-cyber-bg min-h-screen overflow-hidden text-slate-200">
    <div class="fixed inset-0 pointer-events-none opacity-20">
      <svg height="100%" width="100%" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <pattern id="grid" width="100" height="100" patternUnits="userSpaceOnUse">
            <path d="M 100 0 L 0 0 0 100" fill="none" stroke="#3b82f6" stroke-width="0.5" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid)" />
      </svg>
    </div>
    <cyber-particles />

    <main class="relative z-10 flex min-h-screen items-center justify-center px-4 py-8">
      <section class="login-shell w-full max-w-[980px]">
        <div class="hud-card login-card">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>

          <div class="brand-panel">
            <div class="brand-mark">
              <svg-icon icon-class="lock" class-name="brand-icon" />
            </div>
            <h1>供应链风险智能管理系统</h1>
            <p>智能感知 · 精准预警 · 全局掌控</p>
            <div class="security-strip">
              <span>验证码</span>
              <span>加密传输</span>
              <span>Sa-Token</span>
              <span>IP 限流</span>
              <span>登录审计</span>
            </div>
          </div>

          <div class="form-panel">
            <div class="mode-tabs" role="tablist" aria-label="认证方式">
              <button v-for="item in modes" :key="item.value" :class="{ active: mode === item.value }" type="button" @click="switchMode(item.value)">
                {{ item.label }}
              </button>
            </div>

            <el-form ref="formRef" :model="form" :rules="activeRules" class="auth-form" label-position="top" @keyup.enter="submit">
              <el-form-item v-if="tenantEnabled" label="租户" prop="tenantId">
                <el-select v-model="form.tenantId" class="w-full" filterable placeholder="请选择租户">
                  <el-option v-for="tenant in tenantList" :key="tenant.tenantId" :label="tenant.companyName || tenant.tenantId" :value="tenant.tenantId" />
                </el-select>
              </el-form-item>

              <el-form-item label="用户名" prop="username">
                <el-input v-model.trim="form.username" maxlength="30" placeholder="请输入账号" autocomplete="username">
                  <template #prefix><svg-icon icon-class="user" /></template>
                </el-input>
              </el-form-item>

              <div v-if="mode === 'register'" class="contact-grid">
                <el-form-item label="邮箱" prop="email">
                  <el-input v-model.trim="form.email" maxlength="50" placeholder="用于找回密码" autocomplete="email">
                    <template #prefix><svg-icon icon-class="email" /></template>
                  </el-input>
                </el-form-item>
                <el-form-item label="手机号" prop="phonenumber">
                  <el-input v-model.trim="form.phonenumber" maxlength="11" placeholder="用于找回密码" autocomplete="tel">
                    <template #prefix><svg-icon icon-class="phone" /></template>
                  </el-input>
                </el-form-item>
              </div>

              <el-form-item v-if="mode === 'forgot'" label="注册邮箱或手机号" prop="contact">
                <el-input v-model.trim="form.contact" maxlength="50" placeholder="必须与数据库用户资料一致" autocomplete="email">
                  <template #prefix><svg-icon icon-class="email" /></template>
                </el-input>
              </el-form-item>

              <el-form-item :label="mode === 'forgot' ? '新密码' : '密码'" :prop="mode === 'forgot' ? 'newPassword' : 'password'">
                <el-input
                  v-model="passwordModel"
                  maxlength="30"
                  show-password
                  type="password"
                  :placeholder="mode === 'login' ? '请输入密码' : '8 位以上，含大小写、数字和特殊字符'"
                  :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
                >
                  <template #prefix><svg-icon icon-class="password" /></template>
                </el-input>
              </el-form-item>

              <el-form-item v-if="mode !== 'login'" label="确认密码" prop="confirmPassword">
                <el-input v-model="form.confirmPassword" maxlength="30" show-password type="password" placeholder="请再次输入密码" autocomplete="new-password">
                  <template #prefix><svg-icon icon-class="lock" /></template>
                </el-input>
              </el-form-item>

              <el-form-item v-if="captchaEnabled" label="验证码" prop="code">
                <div class="captcha-row">
                  <el-input v-model.trim="form.code" maxlength="6" placeholder="请输入验证码">
                    <template #prefix><svg-icon icon-class="validCode" /></template>
                  </el-input>
                  <button type="button" class="captcha-button" title="刷新验证码" @click="getCode">
                    <img v-if="codeUrl" :src="codeUrl" alt="验证码" />
                    <span v-else>刷新</span>
                  </button>
                </div>
              </el-form-item>

              <div v-if="mode === 'login'" class="form-options">
                <el-checkbox v-model="form.rememberMe">记住账号</el-checkbox>
                <button type="button" @click="switchMode('forgot')">忘记密码？</button>
              </div>

              <el-button class="submit-button" :loading="loading" type="primary" native-type="button" @click="submit">
                {{ submitText }}
                <svg-icon icon-class="caret-forward" />
              </el-button>
            </el-form>

            <p class="security-note">
              所有账号、密码重置和登录审计均写入真实数据库；密码通过 BCrypt 保存，登录令牌由 Sa-Token 签发。
            </p>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { to } from 'await-to-js';
import { forgotPassword, getCodeImg, getTenantList, register } from '@/api/login';
import { useUserStore } from '@/store/modules/user';
import CyberParticles from '@/components/CyberParticles/index.vue';
import type { TenantVO } from '@/api/types';

type AuthMode = 'login' | 'register' | 'forgot';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const mode = ref<AuthMode>('login');
const loading = ref(false);
const captchaEnabled = ref(true);
const tenantEnabled = ref(true);
const codeUrl = ref('');
const formRef = ref<FormInstance>();
const tenantList = ref<TenantVO[]>([]);

const form = reactive({
  tenantId: '000000',
  username: '',
  password: '',
  newPassword: '',
  confirmPassword: '',
  email: '',
  phonenumber: '',
  contact: '',
  rememberMe: false,
  code: '',
  uuid: ''
});

const modes: Array<{ label: string; value: AuthMode }> = [
  { label: '登录', value: 'login' },
  { label: '注册', value: 'register' },
  { label: '找回密码', value: 'forgot' }
];

const passwordPattern = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,30}$/;

const passwordModel = computed({
  get: () => (mode.value === 'forgot' ? form.newPassword : form.password),
  set: (value: string) => {
    if (mode.value === 'forgot') {
      form.newPassword = value;
    } else {
      form.password = value;
    }
  }
});

const submitText = computed(() => {
  if (mode.value === 'register') return '注册账号';
  if (mode.value === 'forgot') return '重置密码';
  return '进入系统';
});

const validateConfirm = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  const target = mode.value === 'forgot' ? form.newPassword : form.password;
  if (mode.value !== 'login' && value !== target) {
    callback(new Error('两次输入的密码不一致'));
    return;
  }
  callback();
};

const validateContact = (_rule: unknown, _value: string, callback: (error?: Error) => void) => {
  if (mode.value === 'register' && !form.email && !form.phonenumber) {
    callback(new Error('邮箱或手机号至少填写一个，用于后续找回密码'));
    return;
  }
  callback();
};

const baseRules: FormRules = {
  tenantId: [{ required: true, message: '请选择租户', trigger: 'change' }],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 30, message: '用户名长度必须在 2 到 30 位之间', trigger: 'blur' }
  ],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
};

const activeRules = computed<FormRules>(() => {
  const passwordRule = { required: true, pattern: passwordPattern, message: '密码需 8-30 位且包含大小写字母、数字和特殊字符', trigger: 'blur' };
  if (mode.value === 'login') {
    return {
      ...baseRules,
      password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
    };
  }
  if (mode.value === 'register') {
    return {
      ...baseRules,
      password: [passwordRule],
      confirmPassword: [{ required: true, message: '请确认密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }],
      email: [{ validator: validateContact, trigger: 'blur' }, { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
      phonenumber: [{ validator: validateContact, trigger: 'blur' }, { pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
    };
  }
  return {
    ...baseRules,
    contact: [{ required: true, message: '请输入注册邮箱或手机号', trigger: 'blur' }],
    newPassword: [passwordRule],
    confirmPassword: [{ required: true, message: '请确认新密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
  };
});

const resetSensitiveFields = () => {
  form.password = '';
  form.newPassword = '';
  form.confirmPassword = '';
  form.code = '';
  form.uuid = '';
};

const switchMode = async (next: AuthMode) => {
  mode.value = next;
  resetSensitiveFields();
  formRef.value?.clearValidate();
  await getCode();
};

const getCode = async () => {
  const [err, res] = await to(getCodeImg());
  if (err || !res) return;
  const data = res.data;
  captchaEnabled.value = data.captchaEnabled === undefined ? true : data.captchaEnabled;
  if (captchaEnabled.value && data.img) {
    codeUrl.value = `data:image/gif;base64,${data.img}`;
    form.uuid = data.uuid || '';
  }
};

const initTenantList = async () => {
  const [err, res] = await to(getTenantList(false));
  if (err || !res) return;
  const data = res.data;
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  tenantList.value = data.voList || [];
  if (tenantList.value.length > 0) {
    form.tenantId = tenantList.value[0].tenantId;
  }
};

const buildPayload = () => ({
  tenantId: form.tenantId,
  username: form.username,
  password: form.password,
  newPassword: form.newPassword,
  email: form.email,
  phonenumber: form.phonenumber,
  contact: form.contact,
  rememberMe: form.rememberMe,
  code: form.code,
  uuid: form.uuid,
  userType: 'sys_user'
});

const submit = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    const payload = buildPayload();
    const [err] = await to(mode.value === 'login' ? userStore.login(payload as any) : mode.value === 'register' ? register(payload) : forgotPassword(payload));
    loading.value = false;
    if (err) {
      await getCode();
      return;
    }
    if (mode.value === 'login') {
      localStorage.setItem('rememberedUser', form.rememberMe ? form.username : '');
      await router.push((route.query.redirect as string) || '/');
      return;
    }
    ElMessage.success(mode.value === 'register' ? '注册成功，请登录' : '密码已重置，请登录');
    await switchMode('login');
  });
};

onMounted(() => {
  const remembered = localStorage.getItem('rememberedUser');
  if (remembered) {
    form.username = remembered;
    form.rememberMe = true;
  }
  initTenantList();
  getCode();
});
</script>

<style scoped lang="scss">
.login-shell {
  container-type: inline-size;
}

.login-card {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(360px, 1fr);
  gap: 0;
  min-height: 620px;
  overflow: hidden;
}

.brand-panel,
.form-panel {
  padding: 44px;
}

.brand-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  border-right: 1px solid rgba(59, 130, 246, 0.14);
  background:
    linear-gradient(135deg, rgba(59, 130, 246, 0.16), transparent 42%),
    radial-gradient(circle at 20% 20%, rgba(34, 197, 94, 0.12), transparent 30%),
    rgba(15, 23, 42, 0.24);
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  margin-bottom: 28px;
  border: 1px solid rgba(59, 130, 246, 0.55);
  border-radius: 16px;
  background: rgba(59, 130, 246, 0.18);
  box-shadow: 0 0 22px rgba(59, 130, 246, 0.42);
}

.brand-icon {
  width: 38px;
  height: 38px;
  color: #3b82f6;
}

.brand-panel h1 {
  margin: 0;
  color: #fff;
  font-size: 30px;
  font-weight: 800;
  line-height: 1.25;
}

.brand-panel p {
  margin: 14px 0 0;
  color: #94a3b8;
  font-size: 14px;
  font-weight: 600;
}

.security-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 36px;
}

.security-strip span {
  padding: 7px 10px;
  border: 1px solid rgba(59, 130, 246, 0.2);
  border-radius: 6px;
  color: #bfdbfe;
  background: rgba(15, 23, 42, 0.68);
  font-size: 12px;
}

.form-panel {
  background: rgba(2, 6, 23, 0.18);
}

.mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  margin-bottom: 28px;
  padding: 4px;
  border: 1px solid rgba(59, 130, 246, 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
}

.mode-tabs button {
  min-height: 38px;
  border: 0;
  border-radius: 6px;
  color: #94a3b8;
  background: transparent;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s ease;
}

.mode-tabs button.active {
  color: #fff;
  background: #3b82f6;
  box-shadow: 0 0 14px rgba(59, 130, 246, 0.34);
}

.auth-form :deep(.el-form-item__label) {
  color: #cbd5e1;
  font-weight: 600;
}

.auth-form :deep(.el-input__wrapper),
.auth-form :deep(.el-select__wrapper) {
  min-height: 46px;
  border: 1px solid rgba(59, 130, 246, 0.16);
  border-radius: 8px;
  background: rgba(30, 41, 59, 0.55);
  box-shadow: none;
}

.auth-form :deep(.el-input__inner) {
  color: #fff;
}

.contact-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 126px;
  gap: 12px;
  width: 100%;
}

.captcha-button {
  height: 46px;
  overflow: hidden;
  border: 1px solid rgba(59, 130, 246, 0.22);
  border-radius: 8px;
  color: #bfdbfe;
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;
}

.captcha-button img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.form-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.form-options button {
  border: 0;
  color: #3b82f6;
  background: transparent;
  cursor: pointer;
}

.submit-button {
  width: 100%;
  height: 46px;
  border: 0;
  border-radius: 8px;
  font-weight: 800;
  box-shadow: 0 0 16px rgba(59, 130, 246, 0.36);
}

.security-note {
  margin: 22px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}

@container (max-width: 780px) {
  .login-card {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    display: none;
  }

  .form-panel {
    padding: 28px 22px;
  }
}

@media (max-width: 520px) {
  .contact-grid,
  .captcha-row {
    grid-template-columns: 1fr;
  }

  .captcha-button {
    width: 100%;
  }
}
</style>
