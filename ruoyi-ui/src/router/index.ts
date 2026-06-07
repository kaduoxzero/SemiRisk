import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import Layout from '@/layout/index.vue';

export const constantRoutes: RouteRecordRaw[] = [
  { path: '/redirect', component: Layout, hidden: true, children: [{ path: '/redirect/:path(.*)', component: () => import('@/views/redirect/index.vue') }] },
  { path: '/social-callback', hidden: true, component: () => import('@/layout/components/SocialCallback/index.vue') },
  { path: '/login', component: () => import('@/views/login.vue'), hidden: true },
  { path: '/register', component: () => import('@/views/register.vue'), hidden: true },
  {
    path: '/',
    component: Layout,
    redirect: '/risk/dashboard',
    hidden: true,
    children: [{ path: 'index', alias: ['/index', '/dashboard', '/dashboard.html'], component: () => import('@/views/dashboard/index.vue'), name: 'Index', meta: { title: '首页风险总览', icon: 'dashboard', affix: true } }]
  },
  {
    path: '/risk',
    component: Layout,
    redirect: '/risk/dashboard',
    alwaysShow: true,
    meta: { title: '供应链风险', icon: 'system' },
    children: [
      { path: 'dashboard', component: () => import('@/views/dashboard/index.vue'), name: 'RiskDashboard', meta: { title: '首页风险总览', icon: 'dashboard' } },
      { path: 'upload', alias: ['/data-upload', '/data-upload.html'], component: () => import('@/views/risk/upload/index.vue'), name: 'RiskDataUpload', meta: { title: '数据上传', icon: 'upload' } },
      { path: 'analysis', alias: ['/risk-analysis', '/risk-analysis.html'], component: () => import('@/views/risk/analysis/index.vue'), name: 'RiskAnalysis', meta: { title: '风险分析', icon: 'chart' } },
      { path: 'detail', alias: ['/risk-detail', '/risk-detail.html'], component: () => import('@/views/risk/detail/index.vue'), name: 'RiskDetail', meta: { title: '风险详情', icon: 'documentation' } },
      { path: 'report', alias: ['/report-generation', '/report-generation.html'], component: () => import('@/views/risk/report/index.vue'), name: 'RiskReport', meta: { title: 'AI报告生成', icon: 'form' } },
      { path: 'alert', alias: ['/alert-center', '/alert-center.html'], component: () => import('@/views/risk/alert/index.vue'), name: 'RiskAlert', meta: { title: '预警中心', icon: 'message' } },
      { path: 'gis', alias: ['/gis-map', '/gis-map.html'], component: () => import('@/views/risk/gis/index.vue'), name: 'RiskGis', meta: { title: 'GIS风险地图', icon: 'chart' } },
      { path: 'profile', alias: ['/enterprise-profile', '/enterprise-profile.html'], component: () => import('@/views/risk/profile/index.vue'), name: 'RiskEnterpriseProfile', meta: { title: '企业画像', icon: 'peoples' } },
      { path: 'kb', alias: ['/knowledge-base', '/knowledge-base.html'], component: () => import('@/views/risk/kb/index.vue'), name: 'RiskKnowledgeBase', meta: { title: '知识库检索', icon: 'education' } },
      { path: 'system', alias: ['/system-management', '/system-management.html'], component: () => import('@/views/risk/system/index.vue'), name: 'RiskSystemManagement', meta: { title: '系统管理', icon: 'system' } }
    ]
  },
  { path: '/user', component: Layout, hidden: true, redirect: 'noredirect', children: [{ path: 'profile', component: () => import('@/views/system/user/profile/index.vue'), name: 'Profile', meta: { title: '个人中心', icon: 'user' } }] },
  { path: '/401', component: () => import('@/views/error/401.vue'), hidden: true },
  { path: '/:pathMatch(.*)*', component: () => import('@/views/error/404.vue'), hidden: true }
];

export const dynamicRoutes: RouteRecordRaw[] = [];

const router = createRouter({
  history: createWebHistory(import.meta.env.VITE_APP_CONTEXT_PATH),
  routes: constantRoutes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition || { top: 0 };
  }
});

export default router;
