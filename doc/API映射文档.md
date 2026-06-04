# SemiRisk API 映射文档

## 1. 通用响应

```json
{
  "success": true,
  "message": "OK",
  "data": {},
  "timestamp": "2026-06-03T15:00:00Z"
}
```

## 2. 认证

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| index.html | POST | `/api/auth/login` | 账号密码登录，失败计数与锁定 |
| Vue 首页 | POST | `/api/auth/register` | 注册账号，默认运营人员角色；邮箱必须为 QQ 邮箱 |
| Vue 应用 | GET | `/api/auth/me` | 查询当前登录态 |
| Vue 应用 | GET | `/api/auth/permissions/{module}` | 校验模块权限 |
| index.html | POST | `/api/auth/logout` | 注销 Session |
| forgot-password.html | POST | `/api/auth/password-reset/request` | 发送重置链接，Token 15 分钟有效 |

Vue 前端默认通过 Vite 代理访问 Gateway：`http://localhost:5173/api -> http://localhost:8080/api`。
未登录只允许访问 `/api/dashboard/overview` 和 `/api/risk-score/today`。

## 3. 首页看板

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| dashboard.html | GET | `/api/dashboard/overview` | KPI、热点、排行榜、材料风险、阶段状态、AI 摘要 |

## 4. 数据上传

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| data-upload.html | GET | `/api/data/templates/{type}` | 下载 CSV 模板 |
| data-upload.html | POST | `/api/data/uploads` | 上传文件，Multipart 字段名 `file` |
| data-upload.html | GET | `/api/data/uploads` | 查询上传任务队列 |
| data-upload.html | POST | `/api/data/uploads/{id}/parse` | 触发 AI 校验和导入 |
| data-upload.html | GET | `/api/data/uploads/logs` | SSE 推送清洗日志 |

## 5. 风险分析与详情

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| risk-analysis.html | GET | `/api/risk/analysis?window=24h` | 深度分析数据切片 |
| risk-detail.html | GET | `/api/risk/events/{id}` | 单一风险事件详情 |
| risk-detail.html | POST | `/api/risk/events/{id}/assign` | 指派负责人，状态变更为处理中 |
| risk-detail.html | POST | `/api/risk/events/{id}/dispatch-report` | 下发处置报告 |
| Vue 首页 | GET | `/api/risk-score/today` | 获取本日 AI 自动测算风险 |
| Vue 首页 | POST | `/api/risk-score/recalculate` | 手动触发本日风险重算 |

## 6. 报告生成

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| report-generation.html | GET | `/api/reports/templates` | 报告模板市场 |
| report-generation.html | POST | `/api/reports/jobs` | 创建异步报告任务 |
| report-generation.html | GET | `/api/reports/jobs/{id}` | 轮询报告生成进度 |
| report-generation.html | GET | `/api/reports/{id}/download` | 下载生成报告，按任务格式返回 PDF、DOCX、PPTX |

## 7. 告警中心

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| alert-center.html | GET | `/api/alerts` | 告警列表，支持 `keyword`、`level`、`status`；默认不返回已忽略公开源告警 |
| alert-center.html | GET | `/api/alerts/counts` | 高中低危未忽略计数 |
| alert-center.html | PUT | `/api/alerts/{id}/ignore` | 忽略告警，公开源告警状态写入 Gateway 状态集 |
| alert-center.html | POST | `/api/alerts/batch-process` | 批量流转选中的告警为处理中 |

## 8. GIS、企业画像、知识库

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| gis-map.html | GET | `/api/gis/map?layers=heatmap,suppliers,ports,routes` | GIS 图层、点位与多条公开源路径数据 |
| enterprise-profile.html | GET | `/api/enterprises/profile?keyword=...` | 企业画像搜索重载 |
| knowledge-base.html | GET | `/api/knowledge/search?query=...` | RAG 检索 |
| knowledge-base.html | POST | `/api/knowledge/ask` | AI 知识库智能体问答，返回回答、检索链路和引用 |
| knowledge-base.html | GET | `/api/knowledge/preview/{id}` | 文档在线预览 |

## 9. 系统管理

| 页面 | 方法 | API | 说明 |
|---|---|---|---|
| system-management.html | GET | `/api/system/overview` | 用户、模型、Agent、日志、数据源总览 |
| system-management.html | POST | `/api/system/users` | 新增成员 |
| system-management.html | PUT | `/api/system/users/{id}/status` | 启用/禁用用户并踢下线 |
| system-management.html | DELETE | `/api/system/users/{id}` | 物理删除用户 |
| system-management.html | POST | `/api/system/models/ping` | AI 模型连通性测试 |
| system-management.html | POST | `/api/system/models/config` | 保存 AI 模型 Endpoint 与 API Key |
| system-management.html | GET | `/api/system/models/config` | 查询已脱敏的模型配置 |
| system-management.html | POST | `/api/system/agents/{name}/trigger` | Agent 手动单步触发 |
| system-management.html | POST | `/api/system/datasources/{name}/reconnect` | 数据源修复重连 |

## 10. 微服务直连 API

| 服务 | 方法 | API | 说明 |
|---|---|---|---|
| semirisk-data-service:8081 | GET | `/api/crawler/records/today` | 查询本日爬虫记录 |
| semirisk-data-service:8081 | POST | `/api/crawler/refresh` | 手动刷新爬虫记录 |
| semirisk-risk-service:8082 | GET | `/api/risk-score/today` | 查询本日风险测算 |
| semirisk-risk-service:8082 | POST | `/api/risk-score/recalculate` | 手动重算风险 |
| semirisk-ai-service:8083 | POST | `/api/ai/models/config` | 保存 DeepSeek 模型 Endpoint 与 API Key |
| semirisk-ai-service:8083 | GET | `/api/ai/reports/latest` | 获取本日 AI 报告占位，默认模型 `deepseekv4-pro` |
| semirisk-alert-service:8084 | GET | `/api/alerts` | 数据库告警查询 |
| semirisk-alert-service:8084 | PUT | `/api/alerts/{id}/ignore` | 告警忽略 |
| semirisk-report-service:8085 | POST | `/api/reports/jobs` | 创建报告任务 |
| semirisk-report-service:8085 | GET | `/api/reports/jobs/{id}` | 查询报告任务进度 |
| semirisk-report-service:8085 | GET | `/api/reports/{id}/download` | 下载 PDF、DOCX、PPTX 报告 |

## 12. AI 知识库问答请求示例

```http
POST /api/knowledge/ask
Content-Type: application/json

{
  "question": "当前半导体供应链最需要关注什么风险？"
}
```

返回字段：

- `answer`：基于公开源知识库检索生成的回答
- `trace`：Query Rewrite、Knowledge Retrieval、Risk Scoring、Answer Synthesis
- `citations`：引用原文标题、来源、URL、风险分
- `modelStatus`：当前是否已配置 DeepSeek API Key

## 11. 运维与文档 API

| 服务 | 方法 | API | 说明 |
|---|---|---|---|
| semirisk-gateway:8080 | GET | `/swagger-ui.html` | OpenAPI 文档页面 |
| semirisk-gateway:8080 | GET | `/v3/api-docs` | OpenAPI JSON |
