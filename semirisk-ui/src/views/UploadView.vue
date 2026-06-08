<template>
  <section class="grid cols-2">
    <div class="panel">
      <h3>数据上传与清洗</h3>
      <div class="hint-box">
        <b>支持格式</b>
        <p>Excel (.xlsx/.xls)、CSV、PDF、ZIP 压缩包，单文件 50MB 内。</p>
        <b>处理流程</b>
        <p>上传 → 落 MinIO 对象存储 → Apache POI / CSV 真实解析行数与字段 → 风险告警入库 → SSE 实时推送日志。</p>
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
          <em class="muted" style="font-size:12px;display:block;margin-top:4px">Excel · CSV · PDF · ZIP，50MB 内</em>
        </span>
        <input hidden type="file" accept=".xlsx,.xls,.csv,.pdf,.zip" @change="actions.uploadFile" />
      </label>

      <div v-if="uploading" class="upload-progress-wrap">
        <div class="upload-progress-bar" :style="{ width: uploadProgress + '%' }" />
      </div>

      <div class="toolbar">
        <button class="btn secondary" @click="actions.downloadTemplate">下载 CSV 模板</button>
        <button class="btn" :disabled="!state.uploads.length" @click="actions.parseUploads">批量解析并导入</button>
      </div>

      <div class="table-wrap">
        <table>
          <thead><tr><th>文件名</th><th>状态</th><th>行数</th><th>字段告警</th></tr></thead>
          <tbody>
            <tr v-for="task in state.uploads" :key="task.id">
              <td>{{ task.filename }}</td>
              <td>
                <span class="badge" :class="statusBadge(task.status)">{{ task.status }}</span>
              </td>
              <td>{{ task.rows ?? '-' }}</td>
              <td>{{ task.fieldAlerts ?? '-' }}</td>
            </tr>
            <tr v-if="!state.uploads.length">
              <td colspan="4" class="muted" style="text-align:center;padding:16px">暂无上传任务</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar compact-toolbar">
        <h3>ETL 处理日志</h3>
        <button class="btn secondary tiny" @click="state.logs = []">清空</button>
      </div>
      <div ref="consoleRef" class="console">
        <p v-for="(line, i) in state.logs" :key="i" :class="logClass(line)">{{ line }}</p>
        <p v-if="!state.logs.length" class="muted">日志将在上传和解析后实时推送。</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue';

const props = defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});

const dragOver = ref(false);
const uploading = ref(false);
const uploadProgress = ref(0);
const consoleRef = ref(null);

async function onDrop(event) {
  dragOver.value = false;
  const file = event.dataTransfer?.files?.[0];
  if (!file) return;
  const allowed = ['.xlsx', '.xls', '.csv', '.pdf', '.zip'];
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

watch(() => props.state.logs, async () => {
  await nextTick();
  if (consoleRef.value) consoleRef.value.scrollTop = consoleRef.value.scrollHeight;
}, { deep: true });

function statusBadge(s) {
  if (!s) return '';
  if (s.includes('完成') || s.includes('OK') || s.includes('成功')) return 'low';
  if (s.includes('失败') || s.includes('错误') || s.includes('Error')) return 'high';
  if (s.includes('解析') || s.includes('处理')) return 'mid';
  return '';
}

function logClass(line) {
  if (!line) return 'muted';
  const l = line.toLowerCase();
  if (l.includes('error') || l.includes('失败')) return 'danger';
  if (l.includes('warn') || l.includes('警告')) return 'warning';
  if (l.includes('完成') || l.includes('success') || l.includes('ok')) return 'success';
  return 'muted';
}
</script>
