<template>
  <div class="panel auth-panel">
    <div>
      <h3>{{ state.authMode === 'register' ? '注册账号' : '登录后解锁完整功能' }}</h3>
      <p class="muted">未登录状态只能查看首页风险总览。登录或注册后可按角色权限访问上传、分析、告警、报告、系统管理等模块。</p>
    </div>
    <div v-if="state.authMode === 'login'" class="toolbar">
      <input v-model="state.loginForm.username" class="input" placeholder="账号" />
      <input v-model="state.loginForm.password" class="input" placeholder="密码" type="password" />
      <button class="btn" @click="actions.login">登录</button>
      <button class="btn secondary" @click="state.authMode = 'register'">去注册</button>
      <button class="btn secondary" @click="actions.resetPassword">忘记密码</button>
    </div>
    <div v-else class="toolbar">
      <input v-model="state.registerForm.username" class="input" placeholder="注册账号" />
      <input v-model="state.registerForm.displayName" class="input" placeholder="姓名/昵称" />
      <input v-model="state.registerForm.password" class="input" placeholder="密码" type="password" />
      <button class="btn" @click="actions.register">注册并登录</button>
      <button class="btn secondary" @click="state.authMode = 'login'">返回登录</button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
