# SemiRisk PRD 原型文档

## 1. 产品目标

构建一套前后端分离、Vue 驱动、微服务后端支撑的供应链风险智能管理系统，覆盖登录准入、风险总览、数据上传、深度分析、风险详情、报告生成、预警中心、GIS 地图、企业画像、知识库和后台系统配置。

## 2. 用户角色

- 系统管理员：用户、权限、模型、Agent、数据源配置
- 风险分析师：风险分析、详情追踪、报告生成、知识检索
- 供应链运营人员：数据上传、告警处理、GIS 监控、企业画像
- 管理层：风险总览、AI 摘要、闭环指标

## 3. 页面原型与交互

### 3.1 登录视图 `semirisk-ui`

- 输入账号、密码，支持记住密码
- Gateway 启动时初始化最高权限账号 `kaduoxli / 123qwe123`，角色为 `ADMIN`
- 公开注册账号默认为 `OPERATOR`，用于普通操作人员自助准入
- 注册与登录必须通过后端 API 校验，账号、密码、邮箱、昵称均做后端二次校验
- 注册信息写入 MySQL `system_user`，密码使用 PBKDF2 哈希保存，不允许明文落库
- 当 VM/MySQL 暂不可达时，启动管理员账号使用本地内存兜底；MySQL 恢复后自动同步落库
- 登录和注册成功后返回 Bearer Token，不使用 Cookie 登录态
- Token 默认 30 分钟滑动有效；30 分钟内刷新任意页面需恢复当前页面并重新拉取当前页面数据，30 分钟后刷新任意页面必须回到首页重新登录
- 正常路由跳转只复用当前 Token，不要求重新登录
- 登录失败、锁定、CSRF 异常必须显示明确 Toast 提示；CSRF 失效时前端自动刷新 Token 并重试一次
- 所有写接口请求必须先获取 `/api/auth/csrf` 并携带 `X-CSRF-Token`
- 登录按钮调用 `/api/auth/login`
- 5 分钟内失败 5 次锁定 30 分钟
- 忘记密码跳转 `forgot-password.html`

### 3.2 忘记密码

- 输入注册 QQ 邮箱
- 调用 `/api/auth/password-reset/request`
- 返回 15 分钟有效的一次性 Token

### 3.3 首页风险总览

- 每 30 秒轮询 `/api/dashboard/overview`
- 展示 KPI、热点、原材料风险、AI 摘要
- 页面采用固定视口长度与分页面板，不允许通过无限下滑堆叠内容
- 高危项可跳转风险详情或企业画像
- 展示近三天公开源爬虫信号与 AI 自动风险测算结果
- 支持手动触发重新测算

### 3.4 数据上传与清洗

- 支持点击/拖拽上传 Excel、CSV、PDF、ZIP
- 单文件大小限制 50MB
- 模板下载调用 `/api/data/templates/supplier`
- 上传调用 `/api/data/uploads`
- AI 清洗日志通过 `/api/data/uploads/logs` SSE 推送
- 解析按钮调用 `/api/data/uploads/{id}/parse`
- 页面必须提示“清洗上传什么、为什么清洗”：包括供应商主数据、BOM、物流节点、财务/合规研报，用于补齐缺失字段、识别异常值、映射风险实体并进入风险分析
- 上传后任务状态需显示“风险分析中/待解析/导入成功/失败”等明确状态
- 数据服务启动时实时爬取公开 RSS/Atom，之后每 12 小时自动重新爬取；手动刷新接口必须立即重新爬取公开源，不允许使用写死记录

### 3.5 AI 风险深度分析 `risk-analysis.html`

- 支持近 24 小时、近 7 天、近 30 天切换
- 调用 `/api/risk/analysis?window=...`
- 切换按钮必须可点击并显示当前激活窗口，后端按窗口返回不同时间范围聚合
- 展示 AI 摘要、风险维度、推理链、替代方案

### 3.6 风险详情 `risk-detail.html`

- 从预警中心进入时读取选中告警 `id`
- 页面左侧为公开源告警概览列表并分页，右侧只在点击某条后展示该条公开源告警解释
- 调用 `/api/risk/events/{id}` 后只展示当前这一条公开源告警的信息
- 必须展示来源、原文链接、发布时间、等级、状态、风险评分
- 必须展示 `translation.zh` 与 `translation.en`，并提供中英文对照表
- 从预警中心进入的详情页不展示 SOP、定损、传导路径和批量处置内容
- 指派负责人 `/api/risk/events/{id}/assign` 与报告下发 `/api/risk/events/{id}/dispatch-report` 作为兼容处置流接口保留，不作为预警详情默认交互

### 3.7 报告生成 `report-generation.html`

- 模板选择、参数配置、格式选择
- 点击立即生成调用 `/api/reports/jobs`
- 轮询 `/api/reports/jobs/{id}`
- 完成后调用 `/api/reports/{id}/download`
- PDF、Word、PPT 选择必须分别导出 `.pdf`、`.docx`、`.pptx`
- 报告生成完成后的下载接口不要求登录态，避免 Token 过期后无法下载已完成文件

### 3.8 预警中心 `alert-center.html`

- 告警列表调用 `/api/alerts`
- 计数器调用 `/api/alerts/counts`
- 支持关键词、等级、状态筛选
- 默认不展示已忽略告警，切换状态为“已忽略”后可追踪历史
- 每行展示公开源名称和原文链接
- 列表至少覆盖近三天真实公开源成功采集记录派生的告警；采集失败记录不作为真实告警展示
- 点击详情只展示该行对应告警本身，并展示中英文对照
- 复选框选择后才能执行批量处理
- 忽略调用 `/api/alerts/{id}/ignore`
- 批量处理调用 `/api/alerts/batch-process`

### 3.9 GIS 风险地图 `gis-map.html`

- 图层勾选触发 `/api/gis/map`
- 支持热力图、供应商、港口航道、物流路径图层
- 后端返回 `points` 和 `routes`，前端需要动态渲染多条路径，不能只使用固定线路
- 地图采用 3D 立体视觉，支持自动旋转；用户拖拽/鼠标控制后切换为手动旋转
- 点击任一点位在地图内弹出小窗口，展示来源、坐标、风险等级、指数、说明和跳转入口

### 3.10 企业画像 `enterprise-profile.html`

- 企业搜索调用 `/api/enterprises/profile?keyword=...`
- 展示工商字段、风险评分、信用等级、拓扑关系、历史事件
- 页面右侧采用分页形式展示工商数据、雷达指标、拓扑关系、历史事件，避免内容纵向堆叠

### 3.11 知识库 `knowledge-base.html`

- Ctrl+K 聚焦搜索框
- 页面只保留上半部分 AI 知识库智能体，不再展示下方检索结果区
- AI 问答调用 `/api/knowledge/ask`，结合本地知识库、公开源记录、Elasticsearch 检索和 DeepSeek 模型生成回答
- 问答结果必须展示回答、检索链路、引用原文和模型状态
- 问答结果必须展示 `aiCalled` 和 `usage.totalTokens`，用于确认 DeepSeek 是否真实调用
- 未配置 API Key 或模型调用失败时才回退本地 RAG 摘要
- 标签点击触发一键提问或改写问题
- 预览调用 `/api/knowledge/preview/{id}`

### 3.12 系统管理

- 总览调用 `/api/system/overview`
- 新增用户调用 `/api/system/users`
- 禁用用户调用 `/api/system/users/{id}/status`
- 模型 Ping 调用 `/api/system/models/ping`
- API Key 配置调用 `/api/system/models/config`
- Agent 手动触发调用 `/api/system/agents/{name}/trigger`
- 数据源重连调用 `/api/system/datasources/{name}/reconnect`
- 系统日志右侧固定长度分页展示，每日记录一次，可按日期查询
- AI 配置面板必须展示当前模型真实调用状态、脱敏 Key、Endpoint 和最近调用结果，不允许只展示静态样子

## 4. 验收标准

- 页面不是纯静态，用户操作必须产生 API 请求和状态反馈
- 前端必须以 Vue 应用方式运行
- 后端必须以 Maven 多模块微服务方式组织
- `./mvnw test` 通过
- `./script/start-backend-services.sh` 可启动后端
- `./script/start-ui.sh` 可启动前端
- 登录页、上传页、告警页、报告页、系统管理页至少各有一个真实可验证交互
- 中间件配置默认指向 `192.168.101.130`
- 登录/注册数据必须持久化到 MySQL；数据库不可达时只允许本地兜底演示，不作为生产路径
- 系统必须提供启动管理员 `kaduoxli / 123qwe123`，VM/MySQL 不可达时也能用于本地验证；生产可通过环境变量覆盖或关闭
- POST/PUT/DELETE 等写接口必须启用 CSRF Token 校验
- 后端必须执行输入清洗和参数校验，防止前端绕过校验
- 风险类页面优先使用公开源爬虫数据；公开源不可达时显示待采集或采集失败，不允许伪造实时事件
- 公开源爬虫必须在服务启动和手动刷新时实时抓取，自动周期为每 12 小时
- 告警忽略后默认列表和计数器不再重复出现该告警
