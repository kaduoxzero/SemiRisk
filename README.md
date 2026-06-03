# SemiRisk

面向半导体产业链的 AI 供应链风险智能平台。项目使用 Java 21、Spring Boot 3、Maven Wrapper 管理，前端复用 `123.57.239.56` 的页面视觉并接入真实后端 API。

## 快速启动

```bash
./mvnw spring-boot:run
```

访问：

- 登录页：http://localhost:8080/index.html
- 默认账号：admin / password
- 分析师账号：analyst / risk2026

## 中间件地址

所有中间件默认指向虚拟机 `192.168.101.128`：

- MySQL：`192.168.101.128:3306`
- Redis：`192.168.101.128:6379`
- Elasticsearch：`192.168.101.128:9200`
- MinIO：`192.168.101.128:9000`
- RabbitMQ：`192.168.101.128:5672`
- Nacos：`192.168.101.128:8848`

当前实现为可运行的 Spring Boot 单体，MySQL/Redis 配置已预留；业务状态默认使用内存，便于无中间件时直接启动演示。

## 验证

```bash
./mvnw test
```

