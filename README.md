# SemiRisk

面向供应链风险管理的 AI 智能监测平台。

## 当前版本包含

- **首页风险总览** — 供应链风险态势一屏掌握，AI 自动生成风险摘要
- **数据上传** — 供应商数据批量导入，支持模板下载
- **风险分析** — 多时间窗口风险事件分析，支持人工指派跟进
- **风险详情** — 单事件深度下钻，关联信号与处置记录
- **报告生成** — 基于模板的自动化报告，支持 AI 辅助撰写
- **预警中心** — 多级告警（高/中/低），支持忽略、恢复、批量处理
- **GIS 地图** — 基于 Three.js 的 3D 全球供应链风险地理可视化
- **企业画像** — 重点企业风险评分、信用评级、事件追踪
- **知识库** — 内部 SOP + 外部情报检索，AI 问答
- **系统管理** — 用户管理、AI 模型配置、数据源管理、Agent 触发

## 技术栈

- **后端**: Java 21 · Spring Boot 3.5.7 · Spring Cloud 2024.0.1 · Spring Cloud Alibaba 2023.0.3.2
- **前端**: Vue 3.5 · Vite 8 · Pinia 3 · Vue Router 5 · Three.js
- **AI**: DeepSeek API（Spring AI 集成）
- **中间件**: MySQL 8.4 · Redis 7.4 · Elasticsearch 8.15.3 · MinIO · RabbitMQ 3.13
- **基础设施**: Nacos 2.4.3 · Zipkin 3.4 · Docker · Nginx
- **构建**: Maven（后端）· npm（前端）

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/your-org/semirisk.git
cd semirisk
```

### 2. 准备环境变量

```bash
cp .env.local.example .env
```

编辑 `.env`，至少配置以下变量：

```bash
# 必需
SEMIRISK_AI_API_KEY=sk-xxxx          # DeepSeek API Key
SEMIRISK_DB_PASSWORD=your_password    # MySQL 密码

# Docker Compose 部署还需要
MYSQL_ROOT_PASSWORD=your_root_password
MINIO_ROOT_USER=semirisk
MINIO_ROOT_PASSWORD=your_minio_password
RABBITMQ_USER=semirisk
RABBITMQ_PASS=your_rabbitmq_password
```

### 3. 启动中间件

```bash
docker compose -f compose/middleware.yml up -d
```

等待所有中间件健康检查通过（约 1-2 分钟）。

### 4. 启动后端

```bash
# 编译
./mvnw -q -DskipTests install

# 一键启动全部 6 个后端服务（需要 screen）
bash script/start-backend-services.sh
```

服务端口：

| 服务 | 端口 |
|------|------|
| Gateway | 8080 |
| Data Service | 8081 |
| Risk Service | 8082 |
| AI Service | 8083 |
| Alert Service | 8084 |
| Report Service | 8085 |

### 5. 启动前端

```bash
cd semirisk-ui
npm install
npm run dev
```

访问 **http://localhost:5173**

## 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| `admin` | `admin123` | 系统管理员 |
| `analyst` | `analyst123` | 风险分析师 |
| `operator` | `operator123` | 运维操作员 |

## 主要接口

所有 API 通过 Gateway（8080 端口）统一入口，前缀 `/api`。

```bash
# 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 风险总览
curl http://localhost:8080/api/dashboard/overview \
  -H "Authorization: Bearer <token>"

# 风险分析（按时间窗口）
curl "http://localhost:8080/api/risk/analysis?window=7d" \
  -H "Authorization: Bearer <token>"

# 告警列表
curl "http://localhost:8080/api/alerts?page=1&size=20" \
  -H "Authorization: Bearer <token>"

# 知识库搜索
curl "http://localhost:8080/api/knowledge/search?query=出口管制" \
  -H "Authorization: Bearer <token>"

# AI 问答
curl -X POST http://localhost:8080/api/knowledge/ask \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"question":"当前半导体供应链主要风险点有哪些？"}'

# 报告模板列表
curl http://localhost:8080/api/reports/templates \
  -H "Authorization: Bearer <token>"

# 企业列表
curl http://localhost:8080/api/enterprises \
  -H "Authorization: Bearer <token>"
```

## 项目结构

```
semirisk/
├── semirisk-common/              # 公共模块
│   ├── semirisk-common-core/     # 核心工具（响应体、异常、常量）
│   ├── semirisk-common-db/       # 数据库公共配置
│   ├── semirisk-common-ai/       # AI 公共配置
│   └── semirisk-common-middleware/ # 中间件公共配置
├── semirisk-services/            # 微服务
│   ├── semirisk-gateway/         # API 网关（认证、路由、限流）
│   ├── semirisk-data-service/    # 数据服务（爬虫、上传、导入）
│   ├── semirisk-risk-service/    # 风险评估服务
│   ├── semirisk-ai-service/      # AI 分析服务
│   ├── semirisk-alert-service/   # 告警服务
│   └── semirisk-report-service/  # 报告生成服务
├── semirisk-ui/                  # 前端（Vue 3 + Vite）
├── compose/                      # Docker Compose 配置
│   ├── middleware.yml            # 中间件
│   ├── backend-vm.yml            # 后端（VM 部署）
│   ├── frontend.yml              # 前端
│   └── init/                     # 数据库初始化脚本
├── deploy/                       # 部署相关（Dockerfile、Nginx、Supervisor）
├── script/                       # 运维脚本
└── pom.xml                       # Maven 父 POM
```

## 部署

### Docker Compose 一键部署

启动全部服务（中间件 + 后端 + 前端 + Nginx）：

```bash
# 配置环境变量
cp .env.local.example .env
# 编辑 .env 填入实际值

# 构建并启动
docker compose up -d --build

# 查看状态
docker compose ps

# 查看日志
docker compose logs -f
```

部署完成后访问 **http://localhost**（Nginx 80 端口，自动代理 API 到 Gateway）。

### 停止服务

```bash
docker compose down
```

### 数据持久化

以下数据通过 Docker Volume 持久化：

- `semirisk-mysql` — MySQL 数据
- `semirisk-redis-data` — Redis 数据
- `semirisk-es` — Elasticsearch 数据
- `semirisk-minio` — MinIO 对象存储
