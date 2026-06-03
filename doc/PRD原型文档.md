# SemiRisk PRD 原型文档

## 1. 产品目标

构建一套可以交互运行的供应链风险智能管理系统，覆盖登录准入、风险总览、数据上传、深度分析、风险详情、报告生成、预警中心、GIS 地图、企业画像、知识库和后台系统配置。

## 2. 用户角色

- 系统管理员：用户、权限、模型、Agent、数据源配置
- 风险分析师：风险分析、详情追踪、报告生成、知识检索
- 供应链运营人员：数据上传、告警处理、GIS 监控、企业画像
- 管理层：风险总览、AI 摘要、闭环指标

## 3. 页面原型与交互

### 3.1 登录页 `index.html`

- 输入账号、密码，支持记住密码
- 登录按钮调用 `/api/auth/login`
- 5 分钟内失败 5 次锁定 30 分钟
- 忘记密码跳转 `forgot-password.html`

### 3.2 忘记密码 `forgot-password.html`

- 输入企业邮箱
- 调用 `/api/auth/password-reset/request`
- 返回 15 分钟有效的一次性 Token

### 3.3 首页风险总览 `dashboard.html`

- 每 30 秒轮询 `/api/dashboard/overview`
- 展示 KPI、热点、原材料风险、AI 摘要
- 高危项可跳转风险详情或企业画像

### 3.4 数据上传与清洗 `data-upload.html`

- 支持点击/拖拽上传 Excel、CSV、PDF、ZIP
- 单文件大小限制 50MB
- 模板下载调用 `/api/data/templates/supplier`
- 上传调用 `/api/data/uploads`
- AI 清洗日志通过 `/api/data/uploads/logs` SSE 推送
- 解析按钮调用 `/api/data/uploads/{id}/parse`

### 3.5 AI 风险深度分析 `risk-analysis.html`

- 支持近 24 小时、近 7 天、近 30 天切换
- 调用 `/api/risk/analysis?window=...`
- 展示 AI 摘要、风险维度、推理链、替代方案

### 3.6 风险详情 `risk-detail.html`

- 读取 URL 参数 `id`
- 调用 `/api/risk/events/{id}`
- 指派负责人调用 `/api/risk/events/{id}/assign`
- 生成处置报告调用 `/api/risk/events/{id}/dispatch-report`

### 3.7 报告生成 `report-generation.html`

- 模板选择、参数配置、格式选择
- 点击立即生成调用 `/api/reports/jobs`
- 轮询 `/api/reports/jobs/{id}`
- 完成后调用 `/api/reports/{id}/download`

### 3.8 预警中心 `alert-center.html`

- 告警列表调用 `/api/alerts`
- 计数器调用 `/api/alerts/counts`
- 支持关键词、等级、状态筛选
- 忽略调用 `/api/alerts/{id}/ignore`
- 批量处理调用 `/api/alerts/batch-process`

### 3.9 GIS 风险地图 `gis-map.html`

- 图层勾选触发 `/api/gis/map`
- 支持热力图、供应商、港口航道、物流路径图层
- 点位详情可跳转风险详情

### 3.10 企业画像 `enterprise-profile.html`

- 企业搜索调用 `/api/enterprises/profile?keyword=...`
- 展示工商字段、风险评分、信用等级、拓扑关系、历史事件

### 3.11 知识库 `knowledge-base.html`

- Ctrl+K 聚焦搜索框
- 检索调用 `/api/knowledge/search`
- 标签点击触发一键检索
- 预览调用 `/api/knowledge/preview/{id}`

### 3.12 系统管理 `system-management.html`

- 总览调用 `/api/system/overview`
- 新增用户调用 `/api/system/users`
- 禁用用户调用 `/api/system/users/{id}/status`
- 模型 Ping 调用 `/api/system/models/ping`
- Agent 手动触发调用 `/api/system/agents/{name}/trigger`
- 数据源重连调用 `/api/system/datasources/{name}/reconnect`

## 4. 验收标准

- 页面不是纯静态，用户操作必须产生 API 请求和状态反馈
- `./mvnw test` 通过
- `./mvnw spring-boot:run` 可启动
- 登录页、上传页、告警页、报告页、系统管理页至少各有一个真实可验证交互
- 中间件配置默认指向 `192.168.101.128`

