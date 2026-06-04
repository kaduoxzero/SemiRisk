<template>
  <section class="grid cols-2">
    <div class="panel">
      <h3>数据上传与清洗</h3>
      <div class="hint-box">
        <b>清洗对象</b>
        <p>供应商名录、BOM 物料、物流节点、合同交期、公开研报和压缩包附件。</p>
        <b>清洗目的</b>
        <p>统一字段、去重、补全缺失值、识别企业/物料实体，并把结果用于风险评分、预警派发、企业画像和报告生成。</p>
      </div>
      <label class="drop">
        <span>点击选择 Excel / CSV / PDF / ZIP，上传后进入 ETL 校验队列，单文件 50MB 内</span>
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
