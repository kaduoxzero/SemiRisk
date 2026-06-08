<template>
  <!-- 未登录：全屏卡片式认证页 -->
  <div v-if="!session" class="auth-fullscreen">
    <div class="auth-brand">
      <div class="brand-mark">SR</div>
      <span>SemiRisk</span>
      <span class="auth-brand-sub">半导体供应链风险智能平台</span>
    </div>

    <!-- 登录卡片 -->
    <div v-if="authMode === 'login'" class="auth-card" key="login">
      <h2>登录</h2>
      <p class="auth-tip">登录后按角色权限访问完整功能模块</p>
      <slot name="login" />
    </div>

    <!-- 注册卡片 -->
    <div v-else-if="authMode === 'register'" class="auth-card" key="register">
      <h2>注册账号</h2>
      <p class="auth-tip">使用 QQ 邮箱注册，注册后自动登录</p>
      <slot name="register" />
    </div>

    <!-- 忘记密码卡片 -->
    <div v-else-if="authMode === 'forgot'" class="auth-card" key="forgot">
      <h2>找回密码</h2>
      <p class="auth-tip">输入注册 QQ 邮箱，获取重置 Token</p>
      <slot name="forgot" />
    </div>

    <div class="auth-footer">SemiRisk &copy; {{ new Date().getFullYear() }} · 仅供授权使用</div>
  </div>

  <!-- 已登录：正常布局 -->
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">SR</span><span>SemiRisk</span></div>
      <nav class="nav">
        <button
          v-for="item in navItems"
          :key="item.key"
          :class="{ active: currentView === item.key }"
          @click="$emit('change-view', item.key)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="sidebar-footer">
        <div class="account-name">{{ session.displayName || session.username }}</div>
        <div class="account-role">{{ session.role }} · {{ session.username }}</div>
        <button class="btn secondary full" @click="$emit('switch-account')">切换账户</button>
        <button class="btn danger full" @click="$emit('logout')">退出登录</button>
      </div>
    </aside>

    <section class="main">
      <header class="topbar">
        <h2 class="page-title">{{ currentTitle }}</h2>
        <div class="topbar-meta">
          <span class="muted">{{ session.displayName || session.username }} · {{ session.role }} · {{ now }}</span>
        </div>
      </header>
      <section class="main-content">
        <slot />
      </section>
    </section>
  </div>
</template>

<script setup>
defineProps({
  currentTitle: { type: String, required: true },
  currentView: { type: String, required: true },
  navItems: { type: Array, required: true },
  now: { type: String, required: true },
  session: { type: Object, default: null },
  authMode: { type: String, default: 'login' }
});

defineEmits(['auth-mode', 'change-view', 'logout', 'switch-account']);
</script>
