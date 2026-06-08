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
    loginForm: { username: '', password: '', rememberMe: false },
    registerForm: { username: '', email: '', password: '', displayName: '' },
    authSubmitting: false,
    dashboard: {},
    dashboardPage: 1,
    uploads: [],
    logs: [],
    analysisWindow: '24h',
    analysis: {},
    selectedRiskId: '',
    detailPage: 1,
    riskDetail: {},
    reportTemplates: [],
    reportForm: { template: 'risk-assessment', language: '中文', format: 'PDF' },
    reportJob: null,
    alerts: [],
    alertFilter: { keyword: '', level: '', status: '' },
    selectedAlertIds: [],
    layers: ['heatmap', 'suppliers', 'ports', 'routes'],
    activeLayers: ['heatmap', 'suppliers', 'ports', 'routes'],
    gis: {},
    enterpriseKeyword: '台积电',
    enterpriseList: [],
    enterprisePage: 1,
    enterprise: {},
    knowledgeQuery: '半导体物流中断',
    knowledgeQuestion: '当前半导体供应链最需要关注什么风险？',
    knowledgeAnswer: null,
    knowledgeLoading: false,
    knowledge: {},
    system: {},
    systemLogPage: 1,
    systemLogDate: new Date().toISOString().slice(0, 10),
    aiConfig: { model: 'deepseek-v4-pro', endpoint: 'https://api.deepseek.com/v1', apiKey: '' },
    modelPing: null
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
    detail: () => risk.loadRiskDetail(state.selectedRiskId),
    report: reports.loadReportTemplates,
    alerts: alerts.loadAlerts,
    gis: gis.loadGis,
    enterprise: enterprise.loadEnterprise,
    knowledge: knowledge.searchKnowledge,
    system: system.loadSystem
  };

  let clock;
  let keyHandler;
  let authExpiredHandler;

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

  function replaceRoute(path) {
    if (route.path !== path) router.replace(path);
  }

  async function login() {
    const previousToken = state.session?.token || '';
    await auth.login();
    if (state.session?.token && state.session.token !== previousToken) {
      replaceRoute(state.view === 'dashboard' ? '/dashboard' : `/${state.view}`);
    }
  }

  async function register() {
    const previousToken = state.session?.token || '';
    await auth.register();
    if (state.session?.token && state.session.token !== previousToken) {
      replaceRoute(state.view === 'dashboard' ? '/dashboard' : `/${state.view}`);
    }
  }

  async function logout() {
    await auth.logout();
    replaceRoute('/dashboard');
  }

  function openRisk(id) {
    state.selectedRiskId = id;
    state.view = 'detail';
    if (route.name !== 'detail') router.push('/detail');
    risk.loadRiskDetail(id);
  }

  async function switchAccount() {
    await logout();
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
    authExpiredHandler = () => {
      setSession(null);
      state.view = 'dashboard';
      state.authMode = 'login';
      if (route.name !== 'dashboard') router.push('/dashboard');
      notify('登录已过期，请重新登录');
    };
    window.addEventListener('semirisk-auth-expired', authExpiredHandler);

    if (state.session) {
      const restored = await auth.restoreSession();
      if (!restored) {
        state.view = 'dashboard';
        state.authMode = 'login';
        if (route.name !== 'dashboard') router.replace('/dashboard');
        await dashboard.loadDashboard();
        return;
      }
      if (!allowedNavItems.value.some(item => item.key === state.view)) {
        state.view = allowedNavItems.value[0]?.key || 'dashboard';
      }
      await loaders[state.view]?.();
      return;
    }
    await dashboard.loadDashboard();
  });

  onUnmounted(() => {
    clearInterval(clock);
    document.removeEventListener('keydown', keyHandler);
    window.removeEventListener('semirisk-auth-expired', authExpiredHandler);
  });

  return {
    state,
    currentTitle,
    allowedNavItems,
    actions: {
      ...auth,
      login,
      logout,
      register,
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
