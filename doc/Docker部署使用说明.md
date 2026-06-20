# SemiRisk Docker 部署使用说明

## 一、系统要求

### 硬件要求
| 项目 | 最低配置 | 推荐配置 |
|------|----------|----------|
| CPU | 4 核 | 8 核 |
| 内存 | 16 GB | 32 GB |
| 磁盘 | 50 GB | 100 GB SSD |

### 软件要求
| 软件 | 版本 | 用途 |
|------|------|------|
| Docker | 24+ | 容器运行时 |
| Docker Compose | v2（内置） | 服务编排 |
| Java JDK | 21 | 后端编译 |
| Maven | 3.9+ | 构建工具 |
| Node.js | 22 LTS | 前端构建 |
| rsync | 3.x+ | 远程同步（仅远程部署） |

### 验证环境
```bash
docker --version
docker compose version
java -version
mvn -version
node -v
```

---

## 二、文件清单

```
SemiRisk/
├── deploy/                        # Docker 部署配置目录
│   ├── Dockerfile.single          # 单镜像构建配方（核心）
│   ├── supervisord.conf           # 进程管理器配置
│   └── nginx.conf                 # Nginx 前端 + API 代理配置
├── compose/
│   └── init/
│       └── 01-init-schema.sql     # MySQL 首次初始化 SQL
├── script/
│   └── deploy-docker.sh           # 一键部署脚本（核心）
├── docker-compose.yml             # 服务编排文件（核心）
├── .dockerignore                  # Docker 构建排除规则
└── semirisk-ui/                   # Vue 3 前端源码
    └── ...
```

### 各文件作用

| 文件 | 作用 | 何时用到 |
|------|------|----------|
| `deploy/Dockerfile.single` | 定义如何构建单镜像（3 阶段：前端构建 → 后端构建 → 生产镜像） | `docker build` 时 |
| `deploy/supervisord.conf` | 管理容器内 7 个进程（6 个 Java + 1 个 Nginx） | 容器启动时 |
| `deploy/nginx.conf` | 前端 SPA 路由 + `/api/` 代理到 Gateway | Nginx 运行时 |
| `docker-compose.yml` | 编排 8 个容器（7 中间件 + 1 应用） | `docker compose up` 时 |
| `script/deploy-docker.sh` | 自动化：编译 → 构建镜像 → 启动服务 | 部署时 |
| `compose/init/01-init-schema.sql` | MySQL 首次启动自动建表 | MySQL 首次启动时 |

---

## 三、部署流程

### 方式一：一键部署（推荐）

```bash
# 1. 登录虚拟机
ssh kaduox@192.168.101.130

# 2. 进入项目目录
cd /opt/semirisk

# 3. 一键部署（自动完成全部步骤）
./script/deploy-docker.sh --local
```

该脚本自动执行：
1. Maven 编译后端 6 个 JAR
2. npm 构建前端
3. 构建 Docker 单镜像 `semirisk/app:latest`
4. 启动全部 8 个容器
5. 等待健康检查通过
6. 显示访问地址

### 方式二：分步部署

```bash
# 1. 编译后端
./mvnw -q -DskipTests clean install

# 2. 构建前端
cd semirisk-ui && npm ci && npm run build && cd ..

# 3. 构建 Docker 单镜像
docker build -t semirisk/app:latest -f deploy/Dockerfile.single .

# 4. 启动全部服务
docker compose up -d

# 5. 等待服务就绪（约 90 秒）
sleep 90

# 6. 查看状态
docker compose ps
```

### 方式三：仅构建镜像（不启动服务）

```bash
# 编译后端
./mvnw -q -DskipTests clean install

# 构建前端
cd semirisk-ui && npm ci && npm run build && cd ..

# 构建镜像
docker build -t semirisk/app:latest -f deploy/Dockerfile.single .

# 验证镜像
docker images | grep semirisk
docker run --rm semirisk/app:latest supervisord -t
```

---

## 四、从本机同步到虚拟机

如果项目在 Windows 本机，需要先同步到 Linux 虚拟机：

```bash
# 从 Windows 本机（Git Bash / WSL）执行
rsync -avz --delete \
  --exclude='.git' \
  --exclude='target/' \
  --exclude='node_modules/' \
  --exclude='dist/' \
  --exclude='.idea/' \
  --exclude='logs/' \
  --exclude='.env.local' \
  L:\ProjectSource\Java\SemiRisk/ \
  kaduox@192.168.101.130:/opt/semirisk/
```

或使用部署脚本的远程模式（从本机执行）：

```bash
cd L:\ProjectSource\Java\SemiRisk
./script/deploy-docker.sh --remote
```

---

## 五、服务管理

### 启动 / 停止 / 重启

```bash
# 启动全部服务
docker compose up -d

# 停止全部服务
docker compose down

# 停止并删除数据卷（⚠️ 会清空数据库）
docker compose down -v

# 重启单个服务
docker compose restart semirisk-app
docker compose restart mysql

# 重建并重启（代码变更后）
docker compose up -d --build semirisk-app
```

### 查看状态

```bash
# 查看所有容器状态
docker compose ps

# 查看实时日志
docker compose logs -f

# 查看单个服务日志
docker compose logs -f semirisk-app
docker compose logs -f semirisk-nacos

# 查看应用容器内各进程状态
docker exec semirisk-app supervisorctl status
```

### 进入容器

```bash
# 进入应用容器
docker exec -it semirisk-app /bin/bash

# 进入 MySQL 容器
docker exec -it semirisk-mysql mysql -uroot -proot semirisk

# 进入 Nacos 容器
docker exec -it semirisk-nacos sh
```

### 查看进程

```bash
# 应用容器内所有进程
docker exec semirisk-app supervisorctl status

# 预期输出：
# gateway              RUNNING   pid 15, uptime 0:05:23
# data-service         RUNNING   pid 14, uptime 0:05:23
# risk-service         RUNNING   pid 13, uptime 0:05:23
# ai-service           RUNNING   pid 12, uptime 0:05:23
# alert-service        RUNNING   pid 11, uptime 0:05:23
# report-service       RUNNING   pid 10, uptime 0:05:23
# nginx                RUNNING   pid 9,  uptime 0:05:23

# 查看 Redis 集群状态
docker exec semirisk-redis-master redis-cli -p 6379 cluster info

# 查看 Redis 集群节点
docker exec semirisk-redis-master redis-cli -p 6379 cluster nodes

# 检查所有节点连通性
docker exec semirisk-redis-master redis-cli -p 6379 ping   # master
docker exec semirisk-redis-replica-1 redis-cli -p 6380 ping # replica-1
docker exec semirisk-redis-replica-2 redis-cli -p 6381 ping # replica-2
docker exec semirisk-redis-replica-3 redis-cli -p 6382 ping # replica-3
```

---

## 六、访问地址

| 服务 | 地址 | 说明 |
|------|------|------|
| **前端 UI** | http://192.168.101.130:80 | Vue 3 单页应用 |
| **API Gateway** | http://192.168.101.130:8080 | REST API 入口 |
| **Swagger UI** | http://192.168.101.130:8080/swagger-ui.html | API 文档 |
| **Nacos** | http://192.168.101.130:8848/nacos | 服务注册/配置中心（nacos/nacos） |
| **Zipkin** | http://192.168.101.130:9411 | 链路追踪 |
| **MinIO** | http://192.168.101.130:9001 | 对象存储控制台（semirisk/semirisk123） |
| **RabbitMQ** | http://192.168.101.130:15672 | 消息队列控制台（guest/guest） |
| **MySQL** | 192.168.101.130:3306 | 数据库（semirisk/semirisk） |
| **Redis Cluster** | 192.168.101.130:6379(master), :6380-6382(replicas) | 缓存 / 限流 / 验证码 / 幂等 |
| **Elasticsearch** | 192.168.101.130:9200 | 搜索引擎 |

---

## 七、环境变量配置

可通过 `.env.local` 文件或命令行覆盖默认值：

```bash
# 在项目根目录创建 .env.local
cat > .env.local << 'EOF'
# AI 配置（必须设置）
SEMIRISK_AI_API_KEY=sk-your-deepseek-key-here

# MySQL 密码
MYSQL_ROOT_PASSWORD=root123
MYSQL_PASSWORD=semirisk123

# AI 模型
SEMIRISK_AI_MODEL=deepseekv4-pro
SEMIRISK_AI_ENDPOINT=https://api.deepseek.com/v1

# 爬虫源
SEMIRISK_CRAWLER_SOURCES=https://www.reuters.com/markets/commodities/,https://www.freightwaves.com/news
EOF
```

docker-compose.yml 会自动读取同目录下的 `.env.local`。

---

## 八、常见问题

### Q1: 服务启动后显示 Unhealthy

```bash
# 查看详细日志
docker compose logs semirisk-app

# 检查中间件是否健康
docker compose ps

# 检查 Nacos 是否就绪（首次启动需要 60 秒）
docker logs semirisk-nacos | tail -20
```

### Q2: Nacos 中看不到服务注册

```bash
# 检查应用容器能否连接到 Nacos
docker exec semirisk-app curl -sf http://semirisk-nacos:8848/nacos/actuator/health

# 查看应用日志中的 Nacos 连接信息
docker logs semirisk-app 2>&1 | grep -i nacos
```

### Q3: 前端页面 404

```bash
# 确认前端 dist 已构建
docker exec semirisk-app ls /usr/share/nginx/html/semirisk/

# 确认 Nginx 配置正确
docker exec semirisk-app cat /etc/nginx/http.d/default.conf

# 重启 Nginx
docker exec semirisk-app supervisorctl restart nginx
```

### Q4: 内存不足

```bash
# 查看内存使用
free -h

# 减少 Java 堆内存（编辑 deploy/supervisord.conf）
# 将 -Xmx512m 改为 -Xmx256m

# 重建镜像
docker compose up -d --build semirisk-app
```

### Q5: 端口被占用

```bash
# 查看端口占用
ss -tlnp | grep -E '80|8080|8848'

# 停止冲突服务
docker compose down

# 或使用不同端口
docker compose -p semirisk2 up -d
```

---

## 九、备份与恢复

### 备份数据库

```bash
# 导出 MySQL 数据
docker exec semirisk-mysql mysqldump -uroot -proot semirisk > semirisk-backup.sql

# 压缩备份
gzip semirisk-backup.sql
```

### 恢复数据库

```bash
# 导入 SQL
docker exec -i semirisk-mysql mysql -uroot -proot semirisk < semirisk-backup.sql
```

### 备份 MinIO 数据

```bash
# 备份 MinIO 卷
docker run --rm -v semirisk-minio:/data -v $(pwd):/backup alpine tar czf /backup/minio-backup.tar.gz -C /data .
```

---

## 十、更新部署

### 更新应用代码

```bash
# 1. 拉取最新代码
cd /opt/semirisk
git pull

# 2. 重新构建镜像
docker compose down semirisk-app
docker compose up -d --build semirisk-app

# 3. 等待就绪
sleep 90
docker compose ps
```

### 更新中间件

```bash
# 更新 MySQL
docker compose up -d --force-recreate mysql

# 更新 Nacos
docker compose up -d --force-recreate nacos

# 更新全部
docker compose pull
docker compose up -d
```
