# SemiRisk

面向半导体产业链的 AI 供应链风险智能平台。项目使用 Java 21、Spring Boot 3、Maven Wrapper 多模块微服务管理，前端使用 Vue 3 + Vite 独立运行。

## 快速启动

如果本机不能直连 `192.168.101.130`，先通过中转机建立本地隧道：

```bash
SEMIRISK_SSH_JUMP_PASSWORD=*** SEMIRISK_VM_PASSWORD=*** ./script/start-vm-tunnels.sh
VM_HOST=127.0.0.1 ./script/check-vm-middleware.sh
```

`.env.local` 中保持：

```bash
SEMIRISK_MIDDLEWARE_HOST=127.0.0.1
SEMIRISK_ES_URL=http://127.0.0.1:9200
```

再启动项目：

```bash
./script/start-backend-services.sh
./script/start-ui.sh
```

单独启动某个后端模块时，先安装公共模块依赖：

```bash
./mvnw -DskipTests install
./mvnw -pl semirisk-services/semirisk-gateway spring-boot:run
```

访问：

- Vue 前端：http://localhost:5173
- API Gateway：http://localhost:8080，构建前端后同样优先托管 `semirisk-ui/dist`
- 启动管理员：`kaduoxli / 123qwe123`，角色 `ADMIN`
- 后续 QQ 邮箱注册用户默认为 `OPERATOR`
- 登录使用 Bearer Token，默认 30 分钟滑动有效，不使用 Cookie 登录态

## 中间件地址

所有中间件默认指向虚拟机 `192.168.101.130`：

- MySQL：`192.168.101.130:3306`
- Redis Cluster：`192.168.101.130:6379`(master), `:6380-6382`(replicas)
- Elasticsearch：`192.168.101.130:9200`
- MinIO：`192.168.101.130:9000`
- RabbitMQ：`192.168.101.130:5672`
- Nacos：`192.168.101.130:8848`

当前 VM 部署目录为 `/opt/semirisk`，中间件 Compose 位于 `/opt/semirisk/middleware`，部署包位于 `/opt/semirisk/packages/semirisk-middleware-deploy.tgz`。
本机通过中转机访问 VM 时，使用 `script/start-vm-tunnels.sh` 将上述端口映射到 `127.0.0.1`。

当前实现为可运行的前后端分离微服务版本。**所有业务数据均为真实数据并持久化到 MySQL，不使用任何虚假/写死的演示数据**：登录注册、Bearer Token、公开源爬虫信号、风险快照、告警状态、企业画像、知识库文档、AI 报告、上传任务等统一以 MySQL 为事实源，中间件暂不可达时给出明确「待采集/待接入」兜底而非伪造。公开源风险数据由 `data-service` 实时爬取（含政策法规源）并每 12 小时刷新；知识库检索接入 Elasticsearch `semirisk_knowledge`；AI 问答与本日报告在配置 Key 后真实调用 DeepSeek；上传文件真实落 MinIO 并用 Apache POI / CSV 解析；系统监控对中间件做真实健康探测。

## 模块

- `semirisk-ui`：Vue 3 + Vite 前端
- `semirisk-common`：公共响应结构、SQL 模板、AI/中间件默认值
- `semirisk-services/semirisk-gateway`：统一 API 入口、持久化枢纽（Token/告警/企业/知识/AI 报告入库）、DeepSeek 调用、MinIO 存储、中间件健康探测
- `semirisk-services/semirisk-data-service`：公开 RSS/Atom 与政策法规源实时爬虫，12 小时自动刷新
- `semirisk-services/semirisk-risk-service`：AI 风险测算
- `semirisk-services/semirisk-ai-service`：AI API Key 管理与本日风险报告真实生成（拉取公开源 + 调用 DeepSeek）

## 部署方式

### Docker 一键部署（推荐）

```bash
# 一键部署（自动编译、构建镜像、启动全部容器）
./script/deploy-docker.sh --local

# 或分步执行
./mvnw -q -DskipTests clean install
cd semirisk-ui && npm ci && npm run build && cd ..
docker compose up -d
```

详细文档：[Docker部署使用说明.md](doc/Docker部署使用说明.md) | [公网访问部署指南.md](doc/公网访问部署指南.md)

### 本地开发

如果本机不能直连 `192.168.101.130`，先通过中转机建立本地隧道：

```bash
SEMIRISK_SSH_JUMP_PASSWORD=*** SEMIRISK_VM_PASSWORD=*** ./script/start-vm-tunnels.sh
VM_HOST=127.0.0.1 ./script/check-vm-middleware.sh
```

`.env.local` 中保持：

```bash
SEMIRISK_MIDDLEWARE_HOST=127.0.0.1
SEMIRISK_ES_URL=http://127.0.0.1:9200
```

再启动项目：

```bash
./script/start-backend-services.sh
./script/start-ui.sh
```

单独启动某个后端模块时，先安装公共模块依赖：

```bash
./mvnw -DskipTests install
./mvnw -pl semirisk-services/semirisk-gateway spring-boot:run
```

访问：

- Vue 前端：http://localhost:5173
- API Gateway：http://localhost:8080，构建前端后同样优先托管 `semirisk-ui/dist`
- 启动管理员：`kaduoxli / 123qwe123`，角色 `ADMIN`
- 后续 QQ 邮箱注册用户默认为 `OPERATOR`
- 登录使用 Bearer Token，默认 30 分钟滑动有效，不使用 Cookie 登录态

## 中间件地址

所有中间件默认指向虚拟机 `192.168.101.130`：

- MySQL：`192.168.101.130:3306`
- Redis Cluster：`192.168.101.130:6379`(master), `:6380-6382`(replicas)
- Elasticsearch：`192.168.101.130:9200`
- MinIO：`192.168.101.130:9000`
- RabbitMQ：`192.168.101.130:5672`
- Nacos：`192.168.101.130:8848`

当前 VM 部署目录为 `/opt/semirisk`，中间件 Compose 位于 `/opt/semirisk/middleware`，部署包位于 `/opt/semirisk/packages/semirisk-middleware-deploy.tgz`。
本机通过中转机访问 VM 时，使用 `script/start-vm-tunnels.sh` 将上述端口映射到 `127.0.0.1`。

## Gateway 服务层架构

Gateway 核心业务层按领域拆分为以下服务类：

| 服务类 | 职责 |
|---|---|
| `SemiRiskStore` | 核心协调：用户认证、风险快照、上传、报告任务、Dashboard 聚合 |
| `AlertService` | 告警生命周期管理：状态跟踪、持久化、公开告警查询（单一数据源） |
| `TranslationService` | 中英文标题翻译、风险等级/状态/维度名称映射 |
| `GisService` | GIS 地理编码、风险点位计算、供应链路径生成 |
| `EnterpriseService` | 企业画像、公开公司数据库、Wikipedia 信息查询、互联网搜索 |
| `AiChatService` | DeepSeek API 调用、RAG 上下文构建、知识库问答、答案结构化 |
| `HealthProbeService` | 中间件（MySQL/Redis Cluster/ES/MinIO/RabbitMQ/Nacos）健康探测，支持集群多节点探测 |

## 验证

```bash
./mvnw test
```
