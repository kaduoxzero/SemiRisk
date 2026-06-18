<template>
  <div class="panel auth-panel">
    <div>
      <h3>{{ state.authMode === 'register' ? '注册账号' : '登录后解锁完整功能' }}</h3>
      <p class="muted">未登录状态只能查看首页风险总览。登录或注册后可按角色权限访问上传、分析、告警、报告、系统管理等模块。</p>
    </div>
    <div v-if="state.authMode === 'login'" class="toolbar">
      <input v-model="state.loginForm.username" class="input" placeholder="账号" />
      <input v-model="state.loginForm.password" class="input" placeholder="密码" type="password" />
      <button class="btn" :disabled="state.authSubmitting" @click="actions.login">
        {{ state.authSubmitting ? '登录中' : '登录' }}
      </button>
      <button class="btn secondary" :disabled="state.authSubmitting" @click="actions.setAuthMode('register')">去注册</button>
      <button class="btn secondary" :disabled="state.authSubmitting" @click="actions.resetPassword">忘记密码</button>
    </div>
    <div v-else-if="state.authMode === 'register'" class="toolbar">
      <input v-model="state.registerForm.username" class="input" placeholder="注册账号（3-32位字母/数字/下划线）" />
      <input v-model="state.registerForm.email" class="input" placeholder="QQ 邮箱，例如 123456@qq.com" type="email" />
      <button class="btn secondary" :disabled="state.authSubmitting" @click="actions.sendRegistrationCode" style="white-space:nowrap">获取验证码</button>
      <input v-model="state.registerForm.verificationCode" class="input" placeholder="6 位验证码" maxlength="6" />
      <input v-model="state.registerForm.displayName" class="input" placeholder="姓名/昵称（至少2字）" />
      <input v-model="state.registerForm.password" class="input" placeholder="密码（至少8位）" type="password" />
      <button class="btn" :disabled="state.authSubmitting" @click="actions.register">
        {{ state.authSubmitting ? '注册中' : '注册并登录' }}
      </button>
      <button class="btn secondary" :disabled="state.authSubmitting" @click="actions.setAuthMode('login')">返回登录</button>
    </div>
    <div v-else-if="state.authMode === 'forgot'" class="toolbar">
      <input v-model="state.registerForm.email" class="input" placeholder="注册时的 QQ 邮箱" type="email" />
      <button class="btn" :disabled="state.authSubmitting" @click="actions.resetPassword">
        {{ state.authSubmitting ? '发送中...' : '发送验证码' }}
      </button>
      <template v-if="showResetForm">
        <input v-model="state.registerForm.resetCode" class="input" placeholder="6 位验证码" maxlength="6" />
        <input v-model="state.registerForm.newPassword" class="input" placeholder="新密码（至少8位）" type="password" />
        <button class="btn" :disabled="state.authSubmitting" @click="actions.confirmReset">
          {{ state.authSubmitting ? '重置中...' : '确认重置' }}
        </button>
      </template>
      <button class="btn secondary" :disabled="state.authSubmitting" @click="actions.setAuthMode('login')">返回登录</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';

defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

// 忘记密码第二步：发送验证码后显示重置表单
const showResetForm = ref(false);

watch(() => state.authMode, (mode) => {
  if (mode !== 'forgot') showResetForm.value = false;
});
</script>
