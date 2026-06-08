# SemiRisk 单体版使用文档

## 1. 运行目标

单体版用于课设演示和本地部署，一个 Spring Boot 进程同时提供：

- 前端页面：`http://localhost:8080/`
- 风险管理 API：`/prod-api/risk/**`
- 真实数据爬取：CISA KEV、USGS GeoJSON

系统不内置假业务数据。启动后会爬取真实公开数据源；如果外部源不可用，页面会显示空态或保留已有真实数据。

## 2. 环境要求

- JDK 17+
- Maven Wrapper：项目已包含 `mvnw` / `mvnw.cmd`
- 可访问公网数据源：
  - `https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json`
  - `https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_week.geojson`

## 3. 构建与启动

Windows PowerShell：

```powershell
cd L:\ProjectSource\Project\SemiRisk
.\mvnw.cmd -pl semirisk-monolith -am -DskipTests package
java -jar .\semirisk-monolith\target\semirisk-monolith.jar
```

Linux / macOS：

```bash
cd SemiRisk
./mvnw -pl semirisk-monolith -am -DskipTests package
java -jar semirisk-monolith/target/semirisk-monolith.jar
```

Docker：

```bash
docker compose -f semirisk-monolith/docker-compose.yml up -d --build
```

默认访问地址：

```text
http://localhost:8080/
```

## 4. 数据爬取

启动时系统会自动爬取真实数据。页面中也可以进入“数据源”，点击“立即爬取真实数据”手动触发。

命令行触发：

```bash
curl -X POST http://localhost:8080/prod-api/risk/crawler/run
```

查看爬取状态：

```bash
curl http://localhost:8080/prod-api/risk/crawler/status
```

爬取配置位于：

```text
semirisk-monolith/src/main/resources/application.yml
```

可调整：

- `startup-enabled`：是否启动时爬取
- `fixed-delay-ms`：定时爬取间隔
- `request-timeout-seconds`：请求超时
- `cisa-kev-url`：CISA 数据源
- `usgs-earthquake-url`：USGS 数据源

## 5. 页面使用

### 总览

展示真实风险事件聚合指标：

- 总风险事件
- 今日新增
- 已闭环
- 当前风险指数
- 风险趋势
- 高风险事件
- 最新风险事件

如果某个接口失败，总览会尽量展示已成功返回的数据，并提示失败模块。

### 风险事件

支持：

- 按事件标题、企业名称、等级、状态查询
- 切换分页大小
- 查看事件详情
- 标记处理中
- 标记已闭环
- 手工写入真实事件

手工写入的数据必须来自真实来源，不要录入演示假数据。

### GIS 分布

仅展示带经纬度的真实事件。USGS 地震数据会自动携带经纬度。

### 企业画像

系统按真实事件中的 `enterpriseName` 聚合生成企业画像。可按企业名称检索。

### 经营报表导入

支持 CSV 上传。可在页面下载模板。

CSV 表头：

```csv
enterpriseName,creditCode,industry,region,supplyChainRole,eventTitle,category,riskLevel,status,sourceName,riskScore,longitude,latitude,description,occurredAt
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| enterpriseName | 企业或真实风险源名称 |
| creditCode | 统一社会信用代码，可空 |
| industry | 行业，可空 |
| region | 地区，可空 |
| supplyChainRole | 供应链角色，可空 |
| eventTitle | 事件标题，必填 |
| category | 风险分类 |
| riskLevel | `CRITICAL` / `WARNING` / `INFO` |
| status | `UNRESOLVED` / `RESOLVING` / `RESOLVED` |
| sourceName | 数据来源 |
| riskScore | 0-100 |
| longitude | 经度，可空 |
| latitude | 纬度，可空 |
| description | 事件描述 |
| occurredAt | ISO 时间或 `yyyy-MM-dd` |

### AI 报告

单体版报告基于当前真实事件生成 Markdown 文本，不调用外部假数据。

### 知识库

支持写入真实知识条目，并按标题、分类、关键词、正文检索。

### 数据源

展示已同步的真实数据源及最近同步时间。可手动新增真实数据源配置。

## 6. 常用接口

```text
GET  /prod-api/risk/event/kpis
GET  /prod-api/risk/event/list?pageNum=1&pageSize=20
GET  /prod-api/risk/event/{eventId}
PUT  /prod-api/risk/event/handle/{eventId}
GET  /prod-api/risk/event/trend
GET  /prod-api/risk/event/gis/nodes
POST /prod-api/risk/crawler/run
GET  /prod-api/risk/crawler/status
```

健康检查：

```bash
curl http://localhost:8080/actuator/health
```

## 7. 验收建议

演示前建议按顺序执行：

1. 启动单体服务。
2. 打开首页确认页面可访问。
3. 在“数据源”点击“立即爬取真实数据”。
4. 查看“总览”指标是否更新。
5. 进入“风险事件”查看详情和处理状态。
6. 进入“GIS 分布”查看带坐标的真实事件。
7. 生成一份 AI 报告。

## 8. 常见问题

### 页面能打开，但数据为空

检查外部数据源是否能访问，或手动调用：

```bash
curl -X POST http://localhost:8080/prod-api/risk/crawler/run
```

### 端口冲突

改用其他端口：

```bash
java -jar semirisk-monolith/target/semirisk-monolith.jar --server.port=18080
```

### 不想启动时自动爬取

```bash
java -jar semirisk-monolith/target/semirisk-monolith.jar --semirisk.crawler.startup-enabled=false
```

### 与旧微服务版的区别

旧版需要 nginx、gateway、auth、system、nacos、redis 等多个组件。单体版只需 `semirisk-monolith` 一个进程，适合课设演示和快速验收。
