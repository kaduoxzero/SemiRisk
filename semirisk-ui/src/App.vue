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
        <input v-model="state.registerForm.email" class="input" placeholder="QQ 邮箱，如 123456@qq.com" type="email" autocomplete="email" />
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

    <!-- 忘记密码 slot -->
    <template #forgot>
      <div class="auth-form">
        <input v-model="state.registerForm.email" class="input" placeholder="注册时的 QQ 邮箱" type="email" autocomplete="email" />
        <button class="btn auth-btn" :disabled="state.authSubmitting" @click="actions.resetPassword">
          获取重置 Token
        </button>
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
import { computed } from 'vue';
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
</script>
