import { createRouter, createWebHistory } from 'vue-router';
import { navItems } from '../constants/navigation';

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', name: 'login', component: { template: '<span />' }, meta: { module: 'dashboard', authMode: 'login', title: '登录' } },
  { path: '/register', name: 'register', component: { template: '<span />' }, meta: { module: 'dashboard', authMode: 'register', title: '注册' } },
  ...navItems.map(item => ({
    path: `/${item.key}`,
    name: item.key,
    component: { template: '<span />' },
    meta: { module: item.key, title: item.label }
  })),
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
];

export const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach(to => {
  const module = to.meta.module;
  if (!module || module === 'dashboard') return true;
  const session = JSON.parse(localStorage.getItem('semiriskUser') || 'null');
  if (!session) return '/dashboard';
  if (Array.isArray(session.modules) && !session.modules.includes(module)) return '/dashboard';
  return true;
});
