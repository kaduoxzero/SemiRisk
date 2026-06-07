# SemiRisk 使用文档

## 1. 系统概述

SemiRisk 是面向供应链风险管理的企业级应用，基于 RuoYi-Cloud-Plus 微服务体系扩展风险业务能力。系统包含风险总览、数据上传、风险分析、风险详情、AI 报告、预警中心、GIS 风险地图、企业画像、知识库检索和系统管理等功能。

当前交付形态包含：

- 前端：`ruoyi-ui`，Vue 3 + Vite + Element Plus。
- 后端网关：`ruoyi-gateway`。
- 认证服务：`ruoyi-auth`。
- 系统及风险业务服务：`ruoyi-modules/ruoyi-system`。
- 资源服务：`ruoyi-modules/ruoyi-resource`。
- AI 分析服务：`risk-ai-service`，FastAPI。
- 中间件：MySQL、Redis、Nacos、Nginx，推荐部署在 Linux 主机 `192.168.101.128`。

## 2. 环境要求

### 2.1 本地构建环境

- JDK 17
- Maven 3.8+
- Node.js >= 20.19.0
- npm >= 8.19.0
- Docker / Docker Compose
- Python 3.11+，仅在本地单独调试 AI 服务时需要

### 2.2 Linux 部署环境

- 操作系统：Linux
- SSH 用户：`kaduox`
- Docker Engine 与 Docker Compose Plugin
- 开放端口：
  - `80`：前端 Nginx
  - `8080`：网关
  - `8848`：Nacos
  - `3306`：MySQL
  - `6379`：Redis
  - `18088`：AI 服务

> 密码仅用于运维登录，不应写入仓库、脚本或镜像。部署时由操作人员在 SSH 交互提示中输入。

## 3. 构建验证

在项目根目录执行：

```powershell
mvn -DskipTests compile
```

前端构建：

```powershell
cd ruoyi-ui
npm install
npm run build:prod
```

AI 服务语法检查：

```powershell
python -m py_compile risk-ai-service/app.py
```

## 4. 初始化中间件

项目提供 Docker Compose 编排文件：

```text
script/docker/docker-compose.yml
```

主要服务：

- `mysql`
- `redis`
- `nacos`
- `risk-ai`
- `ruoyi-gateway`
- `ruoyi-auth`
- `ruoyi-system`
- `ruoyi-resource`
- `nginx`

推荐在 Linux 主机执行：

```bash
cd /opt/SemiRisk/script/docker
docker compose up -d mysql redis
```

等待 MySQL 就绪后导入：

```bash
docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p semi_cloud < ../../script/sql/semi-cloud.sql
docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p semi_cloud < ../../script/sql/risk-module.sql
docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p < ../../script/sql/semi-config.sql
```

随后启动 Nacos 与 AI 服务：

```bash
docker compose up -d nacos risk-ai
```

## 5. 发布配置

Nacos 配置文件位于：

```text
script/config/nacos
```

至少需要发布：

- `application-common.yml`
- `datasource.yml`
- `ruoyi-gateway.yml`
- `ruoyi-auth.yml`
- `ruoyi-system.yml`
- `ruoyi-resource.yml`

默认命名空间与配置组：

- 命名空间：`prod`
- 配置组：`DEFAULT_GROUP`

中间件采用同主机容器部署时，后端配置中的 MySQL、Redis、RabbitMQ、AI 服务地址可使用 `127.0.0.1`。如拆分部署，应将配置中的主机地址替换为实际中间件内网地址。

## 6. 一键部署脚本

Windows 管理端可执行：

```powershell
.\script\deploy-semirisk.ps1 -BuildProfile prod -ComposeFile script/docker/docker-compose.yml -NacosUrl http://127.0.0.1:8848
```

脚本会执行：

1. Maven 打包核心 Java 服务。
2. 前端依赖安装与生产构建。
3. 构建 Docker 镜像。
4. 启动 MySQL、Redis、Nacos、AI 服务。
5. 导入基础 SQL 与风险模块 SQL。
6. 发布 Nacos 配置。
7. 启动业务服务和 Nginx。

Linux 主机可执行：

```bash
cd /opt/SemiRisk
chmod +x script/deploy-semirisk.sh
BUILD_PROFILE=prod COMPOSE_FILE=script/docker/docker-compose.yml NACOS_URL=http://127.0.0.1:8848 ./script/deploy-semirisk.sh
```

如交付包已经包含预构建的 Java `target/*.jar` 和前端 `ruoyi-ui/dist`，可在 Linux 主机跳过 Maven/npm 构建：

```bash
SKIP_BUILD=true BUILD_PROFILE=prod COMPOSE_FILE=script/docker/docker-compose.yml NACOS_URL=http://127.0.0.1:8848 ./script/deploy-semirisk.sh
```

通过 SSH 进入中间件主机：

```bash
ssh kaduox@192.168.101.128
```

首次部署建议将项目放置到：

```text
/opt/SemiRisk
```

## 7. 业务操作流程

### 7.1 登录

访问：

```text
http://192.168.101.128
```

输入平台账号密码进入系统。登录后默认进入风险总览。

### 7.2 风险总览

用于查看核心 KPI：

- 风险事件总量
- 高风险事件数
- 未处置事件数
- 平均风险分
- 风险趋势和类型分布

### 7.3 数据上传

用于导入企业、事件、知识库或外部采集数据。导入后数据进入风险事件、企业画像或知识库模块。

### 7.4 风险分析

用于按时间、类型、等级、企业维度分析风险趋势，辅助判断供应链异常集中区域和高风险链路。

### 7.5 风险详情

用于查看单个风险事件详情，并执行处置动作：

- 标记处置中
- 记录处置建议
- 关闭已解决风险
- 关联企业画像

### 7.6 AI 报告

用于基于数据库内真实风险事件生成报告。未配置 `DEEPSEEK_API_KEY` 时，AI 服务使用本地规则生成离线报告；配置后调用外部大模型生成增强报告。

AI 服务健康检查：

```text
http://192.168.101.128:18088/health
```

### 7.7 预警中心

用于集中处理 `CRITICAL`、`WARNING`、`UNRESOLVED` 等状态的事件，形成闭环。

### 7.8 GIS 风险地图

用于展示带经纬度的企业和风险事件节点，支持按风险等级查看区域风险分布。

### 7.9 企业画像

用于查看企业基础信息、供应链角色、风险评分、风险等级和关联事件。

### 7.10 知识库检索

用于检索风险处置预案、制度、经验案例和规则说明。

## 8. 运维检查

容器状态：

```bash
docker compose -f script/docker/docker-compose.yml ps
```

网关健康：

```bash
curl http://127.0.0.1:8080/actuator/health
```

AI 服务健康：

```bash
curl http://127.0.0.1:18088/health
```

Nacos 控制台：

```text
http://192.168.101.128:8848/nacos
```

## 9. 交付验收

交付前至少完成：

- 后端 `mvn -DskipTests compile` 通过。
- 前端 `npm run build:prod` 通过。
- `risk-ai-service/app.py` Python 编译检查通过。
- MySQL 初始化包含 `risk-module.sql`。
- Nacos 已发布生产配置。
- 前端、网关、认证、系统、资源、AI 服务容器正常运行。
- 登录、风险总览、事件列表、报告生成、GIS 节点查询至少完成一次冒烟验证。
