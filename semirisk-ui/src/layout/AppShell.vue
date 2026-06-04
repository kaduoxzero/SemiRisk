<template>
  <div class="shell">
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
        <template v-if="session">
          <div class="account-name">{{ session.displayName || session.username }}</div>
          <div class="account-role">{{ session.role }} · {{ session.username }}</div>
          <button class="btn secondary full" @click="$emit('switch-account')">切换账户</button>
          <button class="btn danger full" @click="$emit('logout')">退出登录</button>
        </template>
        <template v-else>
          <div class="account-role">未登录，仅开放首页风险总览</div>
          <button class="btn full" @click="$emit('auth-mode', 'login')">登录</button>
          <button class="btn secondary full" @click="$emit('auth-mode', 'register')">注册</button>
        </template>
      </div>
    </aside>

    <section class="main">
      <header class="topbar">
        <h2 class="page-title">{{ currentTitle }}</h2>
        <div v-if="session" class="toolbar">
          <span class="muted">当前用户：{{ session.displayName || session.username }} · {{ session.role }} · {{ now }}</span>
          <button class="btn secondary" @click="$emit('logout')">退出</button>
        </div>
        <div v-else class="toolbar">
          <span class="muted">未登录仅可访问首页风险总览</span>
          <button class="btn" @click="$emit('auth-mode', 'login')">登录</button>
          <button class="btn secondary" @click="$emit('auth-mode', 'register')">注册</button>
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
  session: { type: Object, default: null }
});

defineEmits(['auth-mode', 'change-view', 'logout', 'switch-account']);
</script>
