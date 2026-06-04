import { computed, onMounted, onUnmounted, reactive, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { navItems } from '../constants/navigation';
import { useSessionStore } from '../stores/session';
import { useAlerts } from './useAlerts';
import { useAuth } from './useAuth';
import { useDashboard } from './useDashboard';
import { useEnterprise } from './useEnterprise';
import { useGis } from './useGis';
import { useKnowledge } from './useKnowledge';
import { useReports } from './useReports';
import { useRisk } from './useRisk';
import { useSystem } from './useSystem';
import { useToast } from './useToast';
import { useUpload } from './useUpload';

export function useSemiRiskApp() {
  const route = useRoute();
  const router = useRouter();
  const sessionStore = useSessionStore();
  const initialView = String(route.meta.module || route.name || 'dashboard');
  const initialAuthMode = String(route.meta.authMode || 'login');

  const state = reactive({
    view: initialView,
    now: new Date().toLocaleString('zh-CN', { hour12: false }),
    toast: '',
    session: sessionStore.session,
    authMode: initialAuthMode,
    loginForm: { username: 'admin', password: 'password', rememberMe: true },
    registerForm: { username: '', email: '', password: '', displayName: '' },
    dashboard: {},
    uploads: [],
    logs: [],
    analysis: {},
    riskDetail: {},
    reportTemplates: [],
    reportForm: { template: 'risk-assessment', language: '中文', format: 'PDF' },
    reportJob: null,
    alerts: [],
    alertFilter: { keyword: '', level: '' },
    layers: ['heatmap', 'suppliers', 'ports', 'routes'],
    activeLayers: ['heatmap', 'suppliers'],
    gis: {},
    enterpriseKeyword: '安芯半导体供应链有限公司',
    enterprise: {},
    knowledgeQuery: '半导体物流中断',
    knowledge: {},
    system: {},
    aiConfig: { model: 'deepseekv4-pro', endpoint: 'https://api.deepseek.com/v1', apiKey: '' }
  });

  const allowedNavItems = computed(() => {
    if (!state.session) return navItems.filter(item => item.key === 'dashboard');
    const modules = state.session?.modules;
    if (!modules || !Array.isArray(modules)) return navItems;
    return navItems.filter(item => modules.includes(item.key));
  });

  const currentTitle = computed(() => navItems.find(item => item.key === state.view)?.label || 'SemiRisk');
  const { notify } = useToast(state);
  const dashboard = useDashboard(state, notify);
  const setSession = session => {
    sessionStore.setSession(session);
    state.session = session;
  };
  const auth = useAuth(state, allowedNavItems, notify, dashboard.loadDashboard, setSession);
  const upload = useUpload(state, notify);
  const risk = useRisk(state, notify);
  const reports = useReports(state, notify);
  const alerts = useAlerts(state, notify);
  const gis = useGis(state);
  const enterprise = useEnterprise(state);
  const knowledge = useKnowledge(state);
  const system = useSystem(state, notify);

  const loaders = {
    dashboard: dashboard.loadDashboard,
    upload: upload.loadUploads,
    analysis: risk.loadRiskAnalysis,
    detail: risk.loadRiskDetail,
    report: reports.loadReportTemplates,
    alerts: alerts.loadAlerts,
    gis: gis.loadGis,
    enterprise: enterprise.loadEnterprise,
    knowledge: knowledge.searchKnowledge,
    system: system.loadSystem
  };

  let clock;
  let keyHandler;

  function setView(view) {
    if (!allowedNavItems.value.some(item => item.key === view)) {
      state.view = 'dashboard';
      if (route.name !== 'dashboard') router.push('/dashboard');
      return;
    }
    state.view = view;
    if (route.name !== view) router.push(`/${view}`);
  }

  function setAuthMode(mode) {
    state.authMode = mode === 'register' ? 'register' : 'login';
    const target = state.authMode === 'register' ? '/register' : '/login';
    if (route.path !== target) router.push(target);
  }

  function openRisk(id) {
    state.view = 'detail';
    risk.loadRiskDetail(id);
  }

  async function switchAccount() {
    await auth.logout();
    setAuthMode('login');
  }

  watch(() => state.view, async key => {
    if (!allowedNavItems.value.some(item => item.key === key)) {
      state.view = 'dashboard';
      return;
    }
    await loaders[key]?.();
  });

  watch(() => route.meta.module, module => {
    if (module && module !== state.view) {
      setView(String(module));
    }
  });

  watch(() => route.meta.authMode, mode => {
    if (mode) state.authMode = String(mode);
  });

  onMounted(async () => {
    clock = setInterval(() => (state.now = new Date().toLocaleString('zh-CN', { hour12: false })), 1000);
    keyHandler = event => {
      if (event.ctrlKey && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setView('knowledge');
      }
    };
    document.addEventListener('keydown', keyHandler);

    if (state.session) {
      const restored = await auth.restoreSession();
      if (!restored) {
        await dashboard.loadDashboard();
        return;
      }
      if (!allowedNavItems.value.some(item => item.key === state.view)) {
        state.view = allowedNavItems.value[0]?.key || 'dashboard';
      }
    }
    await dashboard.loadDashboard();
  });

  onUnmounted(() => {
    clearInterval(clock);
    document.removeEventListener('keydown', keyHandler);
  });

  return {
    state,
    currentTitle,
    allowedNavItems,
    actions: {
      ...auth,
      ...dashboard,
      ...upload,
      ...risk,
      ...reports,
      ...alerts,
      ...gis,
      ...enterprise,
      ...knowledge,
      ...system,
      openRisk,
      switchAccount,
      setView,
      setAuthMode
    }
  };
}
