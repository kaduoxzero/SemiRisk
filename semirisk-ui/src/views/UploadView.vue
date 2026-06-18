<template>
  <section class="panel">
    <h3>数据上传</h3>
    <div class="hint-box">
      <b>支持格式</b>
      <p>Excel (.xlsx/.xls)、CSV，单文件 50MB 内。</p>
      <p>上传后系统将自动进行 AI 分析，结果存入知识库。分析师和管理员均可使用。</p>
    </div>

    <!-- 拖拽上传区 -->
    <label
      class="drop"
      :class="{ 'drop-active': dragOver, 'drop-uploading': uploading }"
      @dragenter.prevent="dragOver = true"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <span v-if="uploading" class="upload-spinner">上传中… {{ uploadProgress }}%</span>
      <span v-else-if="dragOver">松开鼠标上传文件</span>
      <span v-else>
        点击选择 / 拖放文件至此
        <em class="muted" style="font-size:12px;display:block;margin-top:4px">Excel · CSV，50MB 内</em>
      </span>
      <input hidden type="file" accept=".xlsx,.xls,.csv" @change="actions.uploadFile" />
    </label>

    <div v-if="uploading" class="upload-progress-wrap">
      <div class="upload-progress-bar" :style="{ width: uploadProgress + '%' }" />
    </div>

    <div class="toolbar">
      <button class="btn secondary" @click="actions.downloadTemplate">下载 CSV 模板</button>
    </div>

    <div class="table-wrap">
      <table>
        <thead><tr><th>文件名</th><th>状态</th><th>上传时间</th></tr></thead>
        <tbody>
          <tr v-for="task in state.uploads" :key="task.id">
            <td>{{ task.filename }}</td>
            <td>
              <span class="badge" :class="statusBadge(task.status)">{{ task.status }}</span>
            </td>
            <td>{{ formatDate(task.createdAt) }}</td>
          </tr>
          <tr v-if="!state.uploads.length">
            <td colspan="3" class="muted" style="text-align:center;padding:16px">暂无上传任务</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const dragOver = ref(false);
const uploading = ref(false);
const uploadProgress = ref(0);

async function onDrop(event) {
  dragOver.value = false;
  const file = event.dataTransfer?.files?.[0];
  if (!file) return;
  const allowed = ['.xlsx', '.xls', '.csv'];
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  if (!allowed.includes(ext)) {
    return;
  }
  uploading.value = true;
  uploadProgress.value = 0;
  const interval = setInterval(() => {
    if (uploadProgress.value < 85) uploadProgress.value += Math.random() * 12;
  }, 200);
  try {
    const fakeEvent = { target: { files: [file] } };
    await props.actions.uploadFile(fakeEvent);
    uploadProgress.value = 100;
  } finally {
    clearInterval(interval);
    setTimeout(() => { uploading.value = false; uploadProgress.value = 0; }, 800);
  }
}

function statusBadge(s) {
  if (!s) return '';
  if (s === '已入库') return 'low';
  if (s === 'AI评估中') return 'mid';
  if (s.includes('失败') || s.includes('错误')) return 'high';
  if (s.includes('上传') || s.includes('解析') || s.includes('处理')) return 'mid';
  return '';
}

function formatDate(ts) {
  if (!ts) return '-';
  try {
    const d = new Date(ts);
    return d.toLocaleDateString('zh-CN') + ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  } catch {
    return ts;
  }
}
</script>
