<template>
  <section class="grid cols-2">
    <div class="panel">
      <h3>数据上传与清洗</h3>
      <label class="drop">
        <span>点击选择 Excel / CSV / PDF / ZIP，单文件 50MB 内</span>
        <input hidden type="file" @change="actions.uploadFile" />
      </label>
      <div class="toolbar">
        <button class="btn secondary" @click="actions.downloadTemplate">下载模板</button>
        <button class="btn" @click="actions.parseUploads">开始校验并导入</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>文件</th><th>状态</th><th>行数</th></tr></thead>
          <tbody>
            <tr v-for="task in state.uploads" :key="task.id">
              <td>{{ task.filename }}</td>
              <td>{{ task.status }}</td>
              <td>{{ task.rows }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="panel">
      <h3>AI 清洗日志</h3>
      <div class="console">
        <p v-for="line in state.logs" :key="line">{{ line }}</p>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  actions: { type: Object, required: true },
  state: { type: Object, required: true }
});
</script>
