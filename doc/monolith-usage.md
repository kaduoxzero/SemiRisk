# SemiRisk 单体版使用文档

## 1. 当前交付形态

当前版本是课设演示用单体架构，一个 Spring Boot 进程同时提供前端页面、鉴权、风险业务 API、真实数据爬取、H2 数据库存储和 PDF 报告生成。

访问地址：

```text
Windows 本机：http://localhost:8080/?v=cyber-20260608e
局域网访问：http://192.168.1.101:8080/?v=cyber-20260608e
健康检查：http://localhost:8080/actuator/health
```

默认账号：

```text
管理员：admin / password
普通用户：user / password
```

登录、注册、忘记密码均会读写数据库。业务接口使用 Bearer Token，token 有效期为 30 分钟。管理员可以进入系统管理、数据源和爬虫配置；普通用户不能访问系统管理入口和管理员接口。

## 2. 构建与启动

Windows PowerShell：

```powershell
cd L:\ProjectSource\Project\SemiRisk
.\mvnw.cmd -pl semirisk-monolith -am -DskipTests package
java -jar .\semirisk-monolith\target\semirisk-monolith.jar
```

当前主机已配置计划任务运行服务：

```powershell
schtasks /Run /TN SemiRiskMonolith
```

Linux / macOS：

```bash
cd SemiRisk
./mvnw -pl semirisk-monolith -am -DskipTests package
java -jar semirisk-monolith/target/semirisk-monolith.jar
```

## 3. 数据存储

数据库使用 H2 文件模式：

```text
semirisk-monolith/data/semirisk-db
```

主要存储内容：

- 用户、角色、状态、密码哈希。
- 30 分钟有效 token。
- 真实风险事件。
- 真实数据源同步记录。
- 本地知识库条目。
- PDF 报告记录。

系统会迁移旧版 `data/risk-store.json` 中的真实事件到 H2。业务页面不生成虚假数据；接口没有真实返回时显示空态。

## 4. 真实数据来源

内置公开数据源：

```text
CISA Known Exploited Vulnerabilities
https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json

USGS Significant Earthquakes
https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_week.geojson
```

系统管理中可以输入 URI 手动爬取。当前仅支持 HTTP/HTTPS，且同一 URI 至少间隔 60 秒请求一次，避免高频抓取。自定义 URI 目前支持 CISA KEV JSON 和 GeoJSON `features` 两类结构。

## 5. 页面功能

- 登录页：登录、注册、忘记密码，账号信息入库。
- 首页风险总览：展示真实事件总量、今日新增、闭环数、风险指数、趋势、高风险事件和最新事件。
- 数据上传：支持 CSV 导入真实事件，页面可下载模板。
- 风险详情：查询、分页、查看详情、标记处理中、标记闭环。分页为首页 3 页 + 省略号 + 末尾 3 页。
- 风险分析：基于真实事件计算风险指数、趋势、等级分布、来源追踪、影响主体和处置建议。
- GIS 风险地图：3D 地球 canvas，可水平拖动旋转；带经纬度的真实事件落点可点击查看详情。
- 企业画像：本地模式基于数据库知识库和真实风险事件聚合；互联网模式跳转企查查搜索，不做未授权爬取。
- AI 报告生成：只生成 PDF，下载接口返回 `application/pdf`。
- 预警中心：按真实未闭环高风险事件形成预警列表，可筛选等级和状态。
- 知识库检索：检索本地知识库和真实事件衍生知识，可按日期、企业、标题、来源等关键词查找。
- 系统管理：仅管理员可用，展示数据源、爬取状态、手动爬取和自定义 URI 爬取。

## 6. 常用接口

登录：

```bash
curl -X POST http://localhost:8080/prod-api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"password\"}"
```

业务接口示例：

```bash
curl http://localhost:8080/prod-api/risk/event/kpis \
  -H "Authorization: Bearer <token>"

curl "http://localhost:8080/prod-api/risk/event/list?pageNum=1&pageSize=20" \
  -H "Authorization: Bearer <token>"

curl http://localhost:8080/prod-api/risk/event/gis/nodes \
  -H "Authorization: Bearer <token>"
```

管理员接口示例：

```bash
curl -X POST http://localhost:8080/prod-api/risk/crawler/run \
  -H "Authorization: Bearer <admin-token>"

curl http://localhost:8080/prod-api/risk/crawler/status \
  -H "Authorization: Bearer <admin-token>"

curl -X POST http://localhost:8080/prod-api/risk/crawler/run-uri \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d "{\"uri\":\"https://example.com/feed.geojson\"}"
```

## 7. 验收流程

1. 打开 `http://localhost:8080/?v=cyber-20260608e`。
2. 使用管理员账号登录。
3. 首页确认总风险事件、风险指数和最新事件不为空。
4. 进入风险详情，测试查询、分页、详情、处理和闭环。
5. 进入 GIS 风险地图，拖动地球并点击风险点。
6. 进入企业画像，测试本地分析和企查查跳转。
7. 进入 AI 报告生成，生成并下载 PDF。
8. 进入系统管理，查看数据源、爬虫状态，管理员触发爬取。
9. 退出后用普通用户登录，确认系统管理入口不可见。

## 8. 常见问题

### Windows 主机能打开，局域网打不开

确认同网段访问：

```text
http://192.168.1.101:8080/?v=cyber-20260608e
```

如果仍不能访问，检查 Windows 防火墙是否允许 8080 端口入站。

### 页面提示登录过期

token 有效期是 30 分钟，重新登录即可。

### 数据为空

检查服务健康和爬虫状态：

```bash
curl http://localhost:8080/actuator/health
```

管理员登录后进入系统管理，点击立即爬取真实数据。

### PDF 下载打不开

确认下载接口响应头为：

```text
Content-Type: application/pdf
```

当前版本已使用 OpenPDF 生成标准 PDF 文件。
