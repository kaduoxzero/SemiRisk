<template>
  <AppShell
    :current-title="currentTitle"
    :current-view="state.view"
    :nav-items="allowedNavItems"
    :now="state.now"
    :session="state.session"
    :auth-mode="state.authMode"
    @auth-mode="actions.setAuthMode"
    @change-view="actions.setView"
    @logout="actions.logout"
    @switch-account="actions.switchAccount"
  >
    <!-- 登录 slot -->
    <template #login>
      <div class="auth-form">
        <input v-model="state.loginForm.username" class="input" placeholder="账号" autocomplete="username" @keydown.enter="actions.login" />
        <input v-model="state.loginForm.password" class="input" placeholder="密码" type="password" autocomplete="current-password" @keydown.enter="actions.login" />
        <button class="btn auth-btn" :disabled="state.authSubmitting" @click="actions.login">
          {{ state.authSubmitting ? '登录中...' : '登 录' }}
        </button>
        <div class="auth-links">
          <button class="auth-link-btn" @click="actions.setAuthMode('register')">注册账号</button>
          <button class="auth-link-btn" @click="actions.setAuthMode('forgot')">忘记密码</button>
        </div>
      </div>
    </template>

    <!-- 注册 slot -->
    <template #register>
      <div class="auth-form">
        <input v-model="state.registerForm.username" class="input" placeholder="账号（3-32位字母/数字/下划线）" autocomplete="username" />
        <div style="display:flex;gap:8px;">
          <input v-model="state.registerForm.email" class="input" style="flex:1" placeholder="QQ 邮箱，如 123456@qq.com" type="email" autocomplete="email" />
          <button class="btn secondary" :disabled="state.authSubmitting" @click="actions.sendRegistrationCode" style="white-space:nowrap;min-width:90px">
            {{ state.authSubmitting ? '发送中...' : '获取验证码' }}
          </button>
        </div>
        <input v-model="state.registerForm.verificationCode" class="input" placeholder="6 位验证码" maxlength="6" />
        <input v-model="state.registerForm.displayName" class="input" placeholder="姓名/昵称（至少2字）" />
        <input v-model="state.registerForm.password" class="input" placeholder="密码（至少8位）" type="password" autocomplete="new-password" @keydown.enter="actions.register" />
        <button class="btn auth-btn" :disabled="state.authSubmitting" @click="actions.register">
          {{ state.authSubmitting ? '注册中...' : '注册并登录' }}
        </button>
        <div class="auth-links">
          <button class="auth-link-btn" @click="actions.setAuthMode('login')">已有账号，去登录</button>
        </div>
      </div>
    </template>

    <!-- 忘记密码 slot — 两步流程 -->
    <template #forgot>
      <div class="auth-form">
        <input v-model="state.registerForm.email" class="input" placeholder="注册时的 QQ 邮箱" type="email" autocomplete="email" />
        <button class="btn auth-btn" :disabled="state.authSubmitting" @click="actions.resetPassword">
          {{ state.authSubmitting ? '发送中...' : '发送验证码' }}
        </button>
        <template v-if="showResetForm">
          <input v-model="state.registerForm.resetCode" class="input" placeholder="6 位验证码" maxlength="6" />
          <input v-model="state.registerForm.newPassword" class="input" placeholder="新密码（至少8位）" type="password" />
          <button class="btn auth-btn" :disabled="state.authSubmitting" @click="actions.confirmReset">
            {{ state.authSubmitting ? '重置中...' : '确认重置' }}
          </button>
        </template>
        <div class="auth-links">
          <button class="auth-link-btn" @click="actions.setAuthMode('login')">返回登录</button>
        </div>
      </div>
    </template>

    <!-- 主内容 -->
    <component :is="currentViewComponent" :state="state" :actions="actions" />
  </AppShell>
  <ToastMessage :message="state.toast" />
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import ToastMessage from './components/ToastMessage.vue';
import { useSemiRiskApp } from './composables/useSemiRiskApp';
import AppShell from './layout/AppShell.vue';
import AlertsView from './views/AlertsView.vue';
import AnalysisView from './views/AnalysisView.vue';
import DashboardView from './views/DashboardView.vue';
import EnterpriseView from './views/EnterpriseView.vue';
import GisView from './views/GisView.vue';
import KnowledgeView from './views/KnowledgeView.vue';
import ReportView from './views/ReportView.vue';
import RiskDetailView from './views/RiskDetailView.vue';
import SystemView from './views/SystemView.vue';
import UploadView from './views/UploadView.vue';

const { state, currentTitle, allowedNavItems, actions } = useSemiRiskApp();

const viewComponents = {
  dashboard: DashboardView,
  upload: UploadView,
  analysis: AnalysisView,
  detail: RiskDetailView,
  report: ReportView,
  alerts: AlertsView,
  gis: GisView,
  enterprise: EnterpriseView,
  knowledge: KnowledgeView,
  system: SystemView
};

const currentViewComponent = computed(() => viewComponents[state.view] || DashboardView);

// 忘记密码第二步：发送验证码后显示重置表单
const showResetForm = ref(false);

watch(() => state.authMode, (mode) => {
  if (mode !== 'forgot') showResetForm.value = false;
});
</script>
