# SemiRisk API 映射文档

## 1. 统一约定

- 网关前缀：通过 `ruoyi-gateway` 暴露。
- 认证方式：登录后携带 `Authorization` Token。
- 返回结构：沿用 RuoYi-Cloud-Plus 通用响应对象。
- 分页列表：通常使用 `GET /list`，请求参数包含分页与筛选条件。
- 业务模块服务：`ruoyi-system`。
- AI 服务：`risk-ai-service`，由 `ruoyi-system` 内部调用。

## 2. 认证接口

| 方法 | 路径 | 控制器 | 前端调用 | 说明 |
| --- | --- | --- | --- | --- |
| POST | `/login` | `TokenController` | `src/api/login.ts` | 用户登录 |
| POST | `/logout` | `TokenController` | `src/api/login.ts` | 用户登出 |
| POST | `/register` | `TokenController` | `src/api/login.ts` | 用户注册 |
| POST | `/forgot-password` | `TokenController` | `src/api/login.ts` | 忘记密码 |
| GET | `/tenant/list` | `TokenController` | `src/api/login.ts` | 租户列表 |
| GET | `/code` | `CaptchaController` | `src/api/login.ts` | 验证码 |

## 3. 风险事件接口

后端控制器：`RiskEventController`

基础路径：`/risk/event`

前端封装：`src/api/risk/event.ts`

| 方法 | 路径 | 前端函数 | 说明 | 主要页面 |
| --- | --- | --- | --- | --- |
| GET | `/risk/event/list` | `listRiskEvent` | 分页查询风险事件 | 风险详情、预警中心 |
| GET | `/risk/event/{eventId}` | `getRiskEvent` | 查询事件详情 | 风险详情 |
| POST | `/risk/event` | `addRiskEvent` | 新增风险事件 | 数据上传、系统管理 |
| PUT | `/risk/event` | `updateRiskEvent` | 更新风险事件 | 风险详情 |
| PUT | `/risk/event/handle/{eventId}` | `handleRiskEvent` | 处置风险事件 | 风险详情、预警中心 |
| DELETE | `/risk/event/{eventIds}` | 未集中封装或页面内调用 | 删除风险事件 | 系统管理 |
| GET | `/risk/event/kpis` | `getRiskKpis` | 风险总览 KPI | 首页风险总览 |
| GET | `/risk/event/trend` | `getRiskTrend` | 风险趋势数据 | 首页风险总览、风险分析 |
| GET | `/risk/event/gis/nodes` | `getRiskGisNodes` | GIS 风险节点 | GIS 风险地图 |
| POST | `/risk/event/report/generate` | `generateRiskReport` | 生成 AI 风险报告 | AI 报告生成 |

## 4. 企业画像接口

后端控制器：`RiskEnterpriseController`

基础路径：`/risk/enterprise`

前端封装：`src/api/risk/enterprise.ts`

| 方法 | 路径 | 前端函数 | 说明 | 主要页面 |
| --- | --- | --- | --- | --- |
| GET | `/risk/enterprise/list` | `listRiskEnterprise` | 分页查询企业 | 企业画像、系统管理 |
| GET | `/risk/enterprise/profile` | `getEnterpriseProfile` | 查询企业画像 | 企业画像 |
| POST | `/risk/enterprise` | `addRiskEnterprise` | 新增企业 | 系统管理 |
| PUT | `/risk/enterprise` | `updateRiskEnterprise` | 更新企业 | 系统管理 |
| DELETE | `/risk/enterprise/{enterpriseIds}` | 未集中封装或页面内调用 | 删除企业 | 系统管理 |
| GET | `/risk/enterprise/kb/search` | `searchEnterpriseKb` | 企业关联知识检索 | 企业画像、知识库 |
| POST | `/risk/enterprise/report/upload` | `uploadEnterpriseReport` | 上传企业报告 | 企业画像 |

## 5. 知识库接口

后端控制器：`RiskKnowledgeController`

基础路径：`/risk/knowledge`

前端封装：`src/api/risk/enterprise.ts`

| 方法 | 路径 | 前端函数 | 说明 | 主要页面 |
| --- | --- | --- | --- | --- |
| GET | `/risk/knowledge/list` | `listRiskKnowledge` | 查询知识库 | 知识库检索 |
| POST | `/risk/knowledge` | `addRiskKnowledge` | 新增知识 | 知识库检索、系统管理 |
| PUT | `/risk/knowledge` | 未集中封装或页面内调用 | 更新知识 | 系统管理 |
| DELETE | `/risk/knowledge/{knowledgeIds}` | 未集中封装或页面内调用 | 删除知识 | 系统管理 |

## 6. 报告接口

后端控制器：`RiskReportController`

基础路径：`/risk/report`

前端封装：`src/api/risk/enterprise.ts`

| 方法 | 路径 | 前端函数 | 说明 | 主要页面 |
| --- | --- | --- | --- | --- |
| GET | `/risk/report/list` | `listRiskReport` | 查询历史报告 | AI 报告生成 |
| GET | `/risk/report/{reportId}` | `getRiskReport` | 查看报告详情 | AI 报告生成 |
| DELETE | `/risk/report/{reportIds}` | 未集中封装或页面内调用 | 删除报告 | AI 报告生成 |

## 7. 数据源接口

后端控制器：`RiskDataSourceController`

基础路径：`/risk/source`

前端封装：`src/api/risk/enterprise.ts`

| 方法 | 路径 | 前端函数 | 说明 | 主要页面 |
| --- | --- | --- | --- | --- |
| GET | `/risk/source/list` | `listRiskSource` | 查询风险数据源 | 系统管理 |
| POST | `/risk/source` | `addRiskSource` | 新增数据源 | 系统管理 |
| PUT | `/risk/source` | 未集中封装或页面内调用 | 更新数据源 | 系统管理 |
| DELETE | `/risk/source/{sourceIds}` | 未集中封装或页面内调用 | 删除数据源 | 系统管理 |

## 8. AI 服务接口

服务：`risk-ai-service`

默认地址：`http://127.0.0.1:18088`

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 运维、健康检查 | 返回服务状态 |
| POST | `/analyze` | `RiskAiService` | 基于风险事件生成报告内容 |

`/analyze` 请求示例：

```json
{
  "templateType": "供应链风险研判报告",
  "dateRange": "最近30天",
  "events": [
    {
      "eventTitle": "关键供应商延期交付",
      "enterpriseName": "华东精密制造有限公司",
      "category": "履约物流",
      "riskLevel": "CRITICAL",
      "status": "UNRESOLVED",
      "riskScore": 79.12,
      "sourceName": "供应商周报",
      "description": "核心轴承批次延迟交付"
    }
  ]
}
```

响应示例：

```json
{
  "content": "# 供应链风险研判报告\n..."
}
```

## 9. 前端路由映射

| 路由 | 组件 | 页面 |
| --- | --- | --- |
| `/risk/dashboard` | `views/dashboard/index.vue` | 首页风险总览 |
| `/risk/upload` | `views/risk/upload/index.vue` | 数据上传 |
| `/risk/analysis` | `views/risk/analysis/index.vue` | 风险分析 |
| `/risk/detail` | `views/risk/detail/index.vue` | 风险详情 |
| `/risk/report` | `views/risk/report/index.vue` | AI 报告生成 |
| `/risk/alert` | `views/risk/alert/index.vue` | 预警中心 |
| `/risk/gis` | `views/risk/gis/index.vue` | GIS 风险地图 |
| `/risk/profile` | `views/risk/profile/index.vue` | 企业画像 |
| `/risk/kb` | `views/risk/kb/index.vue` | 知识库检索 |
| `/risk/system` | `views/risk/system/index.vue` | 系统管理 |

## 10. 数据表映射

| 表名 | 领域对象 | 说明 |
| --- | --- | --- |
| `risk_enterprise` | `RiskEnterprise` | 企业画像和风险评分 |
| `risk_event` | `RiskEvent` | 风险事件主表 |
| `risk_knowledge` | `RiskKnowledge` | 风险知识库 |
| `risk_report` | `RiskReport` | AI 报告结果 |
| `risk_data_source` | `RiskDataSource` | 风险数据源配置 |
