<template>
  <div class="p-6 space-y-6 text-slate-200">
    <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <div>
        <h2 class="text-lg font-semibold text-white">系统后台配置</h2>
        <p class="mt-1 text-xs text-slate-500">用户权限、AI 模型、Agent、数据源与审计日志统一管理</p>
      </div>
      <button class="rounded-lg bg-primary px-5 py-2 text-xs font-bold text-white shadow-lg hover:bg-blue-600" @click="notify('系统配置已同步刷新')">
        <Icon icon="lucide:refresh-cw" class="mr-1 inline text-sm" />
        同步配置
      </button>
    </div>

    <div class="flex flex-wrap gap-2 border-b border-border">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="[
          'px-4 py-3 text-sm transition-colors border-b-2',
          activeTab === tab.key ? 'border-primary text-primary font-bold' : 'border-transparent text-slate-500 hover:text-white'
        ]"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <section v-if="activeTab === 'users'" class="space-y-6">
      <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div class="relative">
          <Icon icon="lucide:search" class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
          <input v-model="keyword" class="cyber-input w-full rounded-lg py-2 pl-10 pr-4 text-sm lg:w-80" placeholder="搜索用户姓名或邮箱..." />
        </div>
        <button class="rounded-lg bg-primary px-5 py-2 text-xs font-bold text-white hover:bg-blue-600" @click="openAddUser">
          <Icon icon="lucide:user-plus" class="mr-1 inline text-sm" />
          新增系统用户
        </button>
      </div>

      <div class="hud-card overflow-hidden">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <table class="w-full text-left">
          <thead>
            <tr class="border-b border-border bg-white/5 text-[10px] uppercase text-slate-500">
              <th class="px-6 py-4">用户名</th>
              <th class="px-6 py-4">姓名</th>
              <th class="px-6 py-4">角色</th>
              <th class="px-6 py-4">状态</th>
              <th class="px-6 py-4">最后登录</th>
              <th class="px-6 py-4 text-right">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-white/5 text-xs">
            <tr v-for="user in filteredUsers" :key="user.username" class="transition-colors hover:bg-white/5">
              <td class="px-6 py-4 font-mono">{{ user.username }}</td>
              <td class="px-6 py-4">
                <div class="flex items-center space-x-2">
                  <span class="flex h-7 w-7 items-center justify-center rounded-full border border-primary/30 bg-primary/10 text-[10px] text-primary">{{ user.avatar }}</span>
                  <span>{{ user.name }}</span>
                </div>
              </td>
              <td class="px-6 py-4">
                <span :class="['rounded px-2 py-0.5 font-bold', user.roleClass]">{{ user.role }}</span>
              </td>
              <td class="px-6 py-4">
                <span :class="user.enabled ? 'text-success' : 'text-slate-500'">{{ user.enabled ? '活动中' : '已禁用' }}</span>
              </td>
              <td class="px-6 py-4 text-slate-500">{{ user.lastLogin }}</td>
              <td class="px-6 py-4 text-right space-x-3">
                <button class="text-primary hover:underline" @click="notify('编辑模块暂未开放')">编辑</button>
                <button class="text-slate-400 hover:text-white" @click="toggleUser(user.username)">{{ user.enabled ? '禁用' : '启用' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-else-if="activeTab === 'rbac'" class="grid grid-cols-12 gap-6">
      <div class="col-span-12 space-y-3 lg:col-span-4">
        <h3 class="text-sm font-bold text-slate-400">系统默认角色</h3>
        <div
          v-for="role in roles"
          :key="role.key"
          :class="['hud-card p-4 cursor-pointer', selectedRole === role.key ? 'border-primary/50 bg-primary/10' : 'hover:bg-white/5']"
          @click="selectedRole = role.key"
        >
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <div class="flex items-center justify-between">
            <div>
              <p class="text-sm font-bold text-white">{{ role.name }}</p>
              <p class="mt-1 text-[10px] text-slate-400">{{ role.desc }}</p>
            </div>
            <Icon icon="lucide:chevron-right" class="text-lg text-primary" />
          </div>
        </div>
      </div>

      <div class="hud-card col-span-12 p-6 lg:col-span-8">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="mb-4 flex items-center justify-between border-b border-border pb-4">
          <div>
            <h3 class="text-sm font-bold text-white">{{ currentRole?.name }} 权限分配</h3>
            <p class="mt-1 text-xs text-slate-500">勾选对应模块后立即进入配置矩阵</p>
          </div>
          <button class="rounded border border-primary bg-primary/20 px-4 py-1 text-xs text-primary" @click="notify('RBAC 架构模型保存成功')">保存分配</button>
        </div>
        <div class="space-y-5 text-sm">
          <div v-for="group in permissions" :key="group.title" class="space-y-2">
            <h4 :class="['text-xs font-bold', group.color]">{{ group.title }}</h4>
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              <label v-for="item in group.items" :key="item" class="flex items-center space-x-3 rounded border border-white/5 bg-white/5 p-3 text-xs">
                <input type="checkbox" checked class="h-4 w-4 accent-primary" />
                <span>{{ item }}</span>
              </label>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section v-else-if="activeTab === 'models'" class="space-y-6">
      <div class="hud-card border-accent/20 bg-accent/5 p-6">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="mb-2 flex items-center space-x-3">
          <Icon icon="lucide:sparkles" class="text-2xl text-accent" />
          <h3 class="text-sm font-bold text-white">模型路由智能解析器</h3>
        </div>
        <p class="text-xs text-slate-400">系统会根据任务类型自动分流：文档抽取匹配高效模型，深度长文本逻辑闭环推理优先分配给复杂思考引擎。</p>
      </div>
      <div class="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div v-for="model in models" :key="model.name" class="hud-card p-6">
          <div class="corner-br"></div>
          <div class="corner-bl"></div>
          <div class="mb-4 flex items-start justify-between">
            <h4 class="text-sm font-bold text-white">{{ model.name }}</h4>
            <span class="h-2.5 w-2.5 rounded-full bg-success animate-pulse"></span>
          </div>
          <p class="mb-4 text-[10px] leading-relaxed text-slate-400">{{ model.desc }}</p>
          <div class="space-y-3 text-xs">
            <label class="block">
              <span class="mb-1 block text-[10px] text-slate-500">API Endpoint</span>
              <input class="cyber-input w-full rounded p-2 font-mono text-xs" :value="model.endpoint" />
            </label>
            <label class="block">
              <span class="mb-1 block text-[10px] text-slate-500">API Key</span>
              <input class="cyber-input w-full rounded p-2 font-mono text-xs" type="password" value="sk-xxxxxxxxxxxxxxxxxxxx" />
            </label>
          </div>
          <button class="mt-4 w-full rounded bg-primary py-2 text-xs text-white hover:bg-blue-600" @click="notify(`${model.name} 连通性测试成功`)">连通性测试</button>
        </div>
      </div>
    </section>

    <section v-else-if="activeTab === 'agents'" class="grid grid-cols-1 gap-6 xl:grid-cols-2">
      <div v-for="agent in agents" :key="agent.name" class="hud-card p-6">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="mb-3 flex items-center justify-between border-b border-border pb-3">
          <span class="text-sm font-bold text-white">
            <Icon :icon="agent.icon" class="mr-2 inline text-primary" />
            {{ agent.name }}
          </span>
          <span class="rounded bg-success/20 px-2 py-0.5 text-[10px] font-bold text-success">运行中</span>
        </div>
        <p class="mb-4 text-xs leading-relaxed text-slate-400">{{ agent.desc }}</p>
        <div class="mb-4 grid grid-cols-2 gap-4 text-[11px] text-slate-500">
          <p>触发机制: <span class="text-white">{{ agent.trigger }}</span></p>
          <p>驱动后台: <span class="text-accent">{{ agent.model }}</span></p>
          <p>最新调用: <span class="text-white">{{ agent.lastRun }}</span></p>
          <p>发现线索: <span class="font-bold text-danger">{{ agent.clues }}</span></p>
        </div>
        <button class="rounded border border-border bg-secondary px-4 py-1.5 text-xs text-slate-300 hover:text-white" @click="notify('正在立即拉取执行，查看系统日志')">手动单步触发</button>
      </div>
    </section>

    <section v-else-if="activeTab === 'logs'" class="space-y-4">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <input v-model="logKeyword" class="cyber-input w-72 rounded-lg px-3 py-1.5 text-xs" placeholder="输入关键字搜索日志..." />
        <button class="rounded-lg border border-primary bg-primary/20 px-4 py-1.5 text-xs text-primary" @click="notify('日志导出成功')">
          <Icon icon="lucide:download" class="mr-1 inline" />
          导出审计日志
        </button>
      </div>
      <div class="hud-card p-6">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="h-[420px] overflow-y-auto rounded bg-black/40 p-4 font-mono text-[11px] space-y-2">
          <p v-for="log in filteredLogs" :key="log.text" class="text-slate-500">
            [{{ log.time }}] <span :class="log.color">[{{ log.level }}]</span> {{ log.text }}
          </p>
        </div>
      </div>
    </section>

    <section v-else class="grid grid-cols-1 gap-6 xl:grid-cols-2">
      <div v-for="source in dataSources" :key="source.name" class="hud-card p-6 space-y-4">
        <div class="corner-br"></div>
        <div class="corner-bl"></div>
        <div class="flex items-center justify-between border-b border-border pb-3">
          <div>
            <p class="text-sm font-bold text-white">{{ source.name }}</p>
            <p class="text-[10px] text-slate-500">连接状态: <span :class="source.color">{{ source.status }}</span></p>
          </div>
          <Icon :icon="source.icon" class="text-2xl text-primary" />
        </div>
        <p class="text-xs text-slate-400">{{ source.scope }}</p>
        <button class="rounded bg-primary/20 px-4 py-1.5 text-xs text-primary" @click="notify(`${source.name} 已进入配置向导`)">配置连接</button>
      </div>
    </section>
  </div>
</template>

<script setup name="RiskSystemManagement" lang="ts">
import { computed, ref } from 'vue';
import { Icon } from '@iconify/vue';
import { ElMessage } from 'element-plus';

const tabs = [
  { key: 'users', label: '用户管理' },
  { key: 'rbac', label: '权限模型 (RBAC)' },
  { key: 'models', label: 'AI 模型配置' },
  { key: 'agents', label: 'Agent 配置' },
  { key: 'logs', label: '系统日志' },
  { key: 'datasource', label: '数据源管理' }
];

const activeTab = ref('users');
const keyword = ref('');
const selectedRole = ref('admin');
const logKeyword = ref('');

const users = ref([
  { username: 'admin_root', name: '张大强', avatar: '张', role: '超级管理员', roleClass: 'bg-accent/20 text-accent', enabled: true, lastLogin: '2026-05-20 09:15' },
  { username: 'analyst_01', name: '李晓敏', avatar: '李', role: '高级分析师', roleClass: 'bg-primary/20 text-primary', enabled: true, lastLogin: '2026-05-19 14:30' },
  { username: 'auditor_v', name: '王五', avatar: '王', role: '审核员', roleClass: 'bg-secondary text-slate-400', enabled: false, lastLogin: '2026-04-12 10:00' }
]);

const roles = [
  { key: 'admin', name: '超级管理员', desc: '系统最高管理权限，掌控全系统设置' },
  { key: 'analyst', name: '高级分析师', desc: '进行风险建模与生成 AI 研判报告' },
  { key: 'operator', name: '运营人员', desc: '负责业务导入，异常工单追踪处置' }
];

const permissions = [
  { title: '数据上传与处理模块', color: 'text-primary', items: ['批量数据上传与结构解析', '触发 AI 数据清洗、补全与关联提取'] },
  { title: '决策分析与研判模块', color: 'text-accent', items: ['查看 AI 风险模型推演网络与拓扑图', '调用 LLM 深度引擎生成并导出研判报告'] },
  { title: '系统全局配置权限', color: 'text-danger', items: ['修改 LLM 密钥、Token限制与算法配置', '查询与重置系统操作审计日志'] }
];

const models = [
  { name: 'GPT-4o (核心分析)', endpoint: 'https://api.openai.com/v1', desc: '系统逻辑推理链的首选引擎，支持复杂地缘政治事件、大宗财务逻辑解构与传播研判。' },
  { name: 'Claude 3.5 Sonnet (RAG)', endpoint: 'https://api.anthropic.com/v1', desc: '负责海量研报、法律规范、历史案例 PDF 的语义关联清洗和 RAG 知识召回。' },
  { name: 'OpenAI 兼容网关', endpoint: 'https://gateway.example.com/v1', desc: '适配第三方私有模型、行业专有模型和本地化部署模型。' }
];

const agents = [
  { name: '全球新闻情报监视 Agent', icon: 'lucide:globe', trigger: '每 30 分钟', model: 'GPT-4o', lastRun: '15:10:24', clues: '12 处', desc: '定频遍历外媒，检索罢工、延期、关税、限制、天气灾害并进行语义判别。' },
  { name: '供应商多维度资质监控 Agent', icon: 'lucide:dollar-sign', trigger: '每日 02:00', model: 'Claude 3.5', lastRun: '今日 02:00', clues: '3 家', desc: '深度比对核心企业财报、涉诉舆情、股权异动，触发高危警报。' }
];

const logs = [
  { time: '15:30:12', level: 'INFO', color: 'text-primary font-bold', text: '[User: analyst_01] 成功触发 AI 报告生成任务，模型分流选择: GPT-4o.' },
  { time: '15:28:45', level: 'WARN', color: 'text-warning font-bold', text: '[Agent: 舆情监视] 新闻流抽取到高危字词，风险指数由 55% 上调至 82%.' },
  { time: '15:12:30', level: 'ERROR', color: 'text-danger font-bold', text: '[System: Collector] 港口数据 API 连接错误 502，正在启动自动重联机制.' },
  { time: '14:50:11', level: 'INFO', color: 'text-primary font-bold', text: '[User: admin_root] 修改全局 AI 引擎密钥成功，系统完成算法热加载.' }
];

const dataSources = [
  { name: 'SAP ERP 生产库存数据源', status: '已连接', color: 'text-success font-bold', icon: 'lucide:database', scope: '同步范围：供应商产能、本地备库数据。' },
  { name: '全球港口与物流 GIS 实时数据 API', status: '502 报错阻断', color: 'text-danger font-bold', icon: 'lucide:map', scope: '同步范围：港口等待时长、航线拥堵、天气扰动。' }
];

const filteredUsers = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  if (!value) return users.value;
  return users.value.filter((user) => `${user.username}${user.name}${user.role}`.toLowerCase().includes(value));
});

const currentRole = computed(() => roles.find((role) => role.key === selectedRole.value));

const filteredLogs = computed(() => {
  const value = logKeyword.value.trim().toLowerCase();
  if (!value) return logs;
  return logs.filter((log) => `${log.level}${log.text}`.toLowerCase().includes(value));
});

const notify = (message: string) => ElMessage.success(message);

const toggleUser = (username: string) => {
  const user = users.value.find((item) => item.username === username);
  if (!user) return;
  user.enabled = !user.enabled;
  notify(`${user.name} 已${user.enabled ? '启用' : '禁用'}`);
};

const openAddUser = () => {
  users.value.push({
    username: `analyst_${users.value.length + 1}`,
    name: '新成员',
    avatar: '新',
    role: '高级分析师',
    roleClass: 'bg-primary/20 text-primary',
    enabled: true,
    lastLogin: '尚未登录'
  });
  notify('系统用户已新增');
};
</script>
