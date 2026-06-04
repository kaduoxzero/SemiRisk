<template>
  <AppShell
    :current-title="currentTitle"
    :current-view="state.view"
    :nav-items="allowedNavItems"
    :now="state.now"
    :session="state.session"
    @auth-mode="actions.setAuthMode"
    @change-view="actions.setView"
    @logout="actions.logout"
    @switch-account="actions.switchAccount"
  >
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
