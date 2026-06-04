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

defineEmits(['auth-mode', 'change-view', 'logout']);
</script>
