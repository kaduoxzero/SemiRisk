# SemiRisk

面向半导体产业链的 AI 供应链风险智能平台。项目使用 Java 21、Spring Boot 3、Maven Wrapper 多模块微服务管理，前端使用 Vue 3 + Vite 独立运行。

## 快速启动

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
- API Gateway：http://localhost:8080
- 系统不提供默认账号密码；首次注册账号自动成为 `ADMIN`
- 登录使用 Bearer Token，默认 15 分钟有效，不使用 Cookie 登录态

## 中间件地址

所有中间件默认指向虚拟机 `192.168.101.130`：

- MySQL：`192.168.101.130:3306`
- Redis：`192.168.101.130:6379`
- Elasticsearch：`192.168.101.130:9200`
- MinIO：`192.168.101.130:9000`
- RabbitMQ：`192.168.101.130:5672`
- Nacos：`192.168.101.130:8848`

当前 VM 部署目录为 `/opt/semirisk`，中间件 Compose 位于 `/opt/semirisk/middleware`，部署包位于 `/opt/semirisk/packages/semirisk-middleware-deploy.tgz`。

当前实现为可运行的前后端分离微服务版本。登录注册优先持久化到 MySQL，失败计数优先使用 Redis；公开源风险数据每 10 分钟刷新，知识库检索已接入 Elasticsearch `semirisk_knowledge`。

## 模块

- `semirisk-ui`：Vue 前端
- `semirisk-common`：公共响应结构
- `semirisk-services/semirisk-gateway`：统一 API 入口
- `semirisk-services/semirisk-data-service`：爬虫和 10 分钟刷新记录
- `semirisk-services/semirisk-risk-service`：AI 风险测算
- `semirisk-services/semirisk-ai-service`：AI API Key 和报告分析占位

## 验证

```bash
./mvnw test
```
