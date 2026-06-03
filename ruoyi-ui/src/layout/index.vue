<template>
  <!-- 1. 加上了 bg-cyber-bg 类（对应 #030712）以及文字浅灰色 -->
  <div :class="classObj" class="app-wrapper bg-cyber-bg text-slate-200" :style="{ '--current-color': theme }">
    <!-- 2. 在这里插入我们之前建好的粒子背景组件 -->
    <cyber-particles />

    <div v-if="device === 'mobile' && sidebar.opened" class="drawer-bg" @click="handleClickOutside" />

    <!-- 3. 为侧边栏外层加上边框和半透明高斯模糊背景 -->
    <side-bar v-if="!sidebar.hide" class="sidebar-container border-r border-border/50 bg-card/40 backdrop-blur-xl" />

    <div :class="{ hasTagsView: needTagsView, sidebarHide: sidebar.hide }" class="main-container">
      <!-- 4. 顶栏容器加上高斯模糊 -->
      <div :class="{ 'fixed-header': fixedHeader }" class="header-container">
        <navbar ref="navbarRef" @set-layout="setLayout" />
        <tags-view v-if="needTagsView" />
      </div>
      <app-main />
      <settings ref="settingRef" />
    </div>
  </div>
</template>

<script setup lang="ts">
import SideBar from './components/Sidebar/index.vue';
import { AppMain, Navbar, Settings, TagsView } from './components';
// 5. 引入粒子组件 (路径根据你实际存放位置微调)
import CyberParticles from '@/components/CyberParticles/index.vue';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { initWebSocket } from '@/utils/websocket';
import { initSSE } from '@/utils/sse';

const settingsStore = useSettingsStore();
const theme = computed(() => settingsStore.theme);
const sidebar = computed(() => useAppStore().sidebar);
const device = computed(() => useAppStore().device);
const needTagsView = computed(() => settingsStore.tagsView);
const fixedHeader = computed(() => settingsStore.fixedHeader);
const layout = computed(() => settingsStore.navType);

// 根据布局模式判断是否显示侧边栏
const showSidebar = computed(() => {
  if (sidebar.value.hide) return false;
  return layout.value === NavTypeEnum.LEFT || layout.value === NavTypeEnum.MIX;
});

const classObj = computed(() => ({
  hideSidebar: !sidebar.value.opened,
  openSidebar: sidebar.value.opened,
  withoutAnimation: sidebar.value.withoutAnimation,
  mobile: device.value === 'mobile'
}));

const { width } = useWindowSize();
const WIDTH = 992; // refer to Bootstrap's responsive design

watchEffect(() => {
  if (device.value === 'mobile') {
    useAppStore().closeSideBar({ withoutAnimation: false });
  }
  if (width.value - 1 < WIDTH) {
    useAppStore().toggleDevice('mobile');
    useAppStore().closeSideBar({ withoutAnimation: true });
  } else {
    useAppStore().toggleDevice('desktop');
  }
});

const navbarRef = ref<InstanceType<typeof Navbar>>();
const settingRef = ref<InstanceType<typeof Settings>>();

onMounted(() => {
  nextTick(() => {
    navbarRef.value?.initTenantList();
  });
});

onMounted(() => {
  const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
  initWebSocket(protocol + window.location.host + import.meta.env.VITE_APP_BASE_API + '/resource/websocket');
});

onMounted(() => {
  initSSE(import.meta.env.VITE_APP_BASE_API + '/resource/sse');
});

const handleClickOutside = () => {
  useAppStore().closeSideBar({ withoutAnimation: false });
};

const setLayout = () => {
  settingRef.value?.openSetting();
};
</script>

<style lang="scss" scoped>
@use '@/assets/styles/mixin.scss';
@use '@/assets/styles/variables.module.scss' as *;

.app-wrapper {
  @include mixin.clearfix;
  position: relative;
  height: 100%;
  width: 100%;
  // 6. 确保主背景透明，以便能看到下方的 Canvas 粒子
  background-color: transparent !important;

  &.mobile.openSidebar {
    position: fixed;
    top: 0;
  }
}

// 7. 让主面板容器背景保持半透明
.main-container {
  background-color: transparent !important;
  min-height: 100vh;
}

.drawer-bg {
  background: #000;
  opacity: 0.3;
  width: 100%;
  top: 0;
  height: 100%;
  position: absolute;
  z-index: 999;
}

// 8. 重新定义 Header 样式，融入磨砂玻璃和科技边框
.header-container {
  background: rgba(15, 23, 42, 0.3) !important;
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
}

.fixed-header {
  position: fixed;
  top: 0;
  right: 0;
  z-index: 9;
  width: calc(100% - #{$base-sidebar-width});
  transition: width 0.28s;
  background: rgba(15, 23, 42, 0.4) !important;
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
}

.hideSidebar .fixed-header {
  width: calc(100% - 54px);
}

.sidebarHide .fixed-header {
  width: 100%;
}

.mobile .fixed-header {
  width: 100%;
}
</style>
