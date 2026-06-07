
# SemiRisk 开发规范指南

为保证代码质量、可维护性、安全性与可扩展性，请在开发过程中严格遵循以下规范。本项目基于 **若依 (Ruoyi)** 架构进行二次开发与扩展。

## 一、项目基础信息

- **项目名称**：SemiRisk
- **开发作者**：kaduox
- **工作目录**：`L:\ProjectSource\Project\SemiRisk`
- **操作系统**：Windows 11
- **核心开发语言**：Java 17
- **工具链**：Maven, Spring Boot 3.x

## 二、目录结构规范

项目采用模块化分层结构，遵循 Maven 多模块管理规范。主要模块划分如下：

```text
SemiRisk
├── ruoyi-api/                    # 接口定义层
│   ├── ruoyi-api-bom/
│   ├── ruoyi-api-resource/       # 资源服务接口
│   ├── ruoyi-api-system/         # 系统服务接口
│   └── ruoyi-api-workflow/       # 工作流服务接口
├── ruoyi-common/                 # 通用工具层
│   ├── ruoyi-common-core/        # 核心工具
│   ├── ruoyi-common-doc/         # 接口文档 (SpringDoc + Therapi)
│   ├── ruoyi-common-mybatis/     # 持久层封装
│   ├── ruoyi-common-redis/       # 缓存 (Redisson + Lock4j)
│   ├── ruoyi-common-web/         # Web容器
│   ├── ruoyi-common-security/    # 安全认证 (Sa-Token)
│   ├── ruoyi-common-satoken/     # Sa-Token核心
│   ├── ruoyi-common-idempotent/  # 幂等性
│   ├── ruoyi-common-tenant/      # 多租户
│   ├── ruoyi-common-elasticsearch/# 搜索引擎
│   └── ...
├── ruoyi-modules/                # 业务模块层
│   ├── ruoyi-system/             # 系统模块
│   ├── ruoyi-resource/           # 资源模块
│   ├── ruoyi-workflow/           # 工作流模块
│   └── ...
├── ruoyi-gateway-mvc/            # 网关模块
├── ruoyi-auth/                   # 认证中心
├── ruoyi-example/                # 示例模块
└── ruoyi-ui/                     # 前端资源
```

## 三、技术栈与依赖规范

### 核心框架
- **主框架**：Spring Boot 3.x
- **JDK 版本**：Java 17.0.18
- **构建工具**：Maven

### 核心依赖库
1.  **Web与容器**：
    - `spring-boot-starter-web` (排除 Tomcat，使用 **Undertow** 提升性能)
    - `spring-boot-starter-undertow`
2.  **数据访问**：
    - `mybatis-plus-spring-boot3-starter` (MyBatis-Plus 3.5.5+)
    - `easy-es-boot-starter` (可选，用于 Elasticsearch)
3.  **缓存与分布式锁**：
    - `redisson-spring-boot-starter`
    - `lock4j-redisson-spring-boot-starter`
    - `caffeine` (本地缓存)
4.  **安全与认证**：
    - `sa-token-spring-boot3-starter` (权限认证)
    - `warm-flow-mybatis-plus-sb3-starter` (工作流引擎)
5.  **服务治理**：
    - `spring-cloud-starter-alibaba-nacos-discovery` (服务注册与发现)
    - `dubbo-spring-boot-starter` (可选，RPC)
6.  **API 文档**：
    - `springdoc-openapi-starter-webmvc-api`
    - `therapi-runtime-javadoc` (JavaDoc 运行时解析)

## 四、通用开发规则总结

### 1. 分层架构 (严格遵循)
遵循标准的 Spring Boot 三层架构，业务逻辑严禁跨层访问。

| 层级 | 包路径示例 | 职责说明 | 约束 |
| :--- | :--- | :--- | :--- |
| **Controller** | `controller` | 接收请求，参数校验，调用 Service，返回 DTO/VO | **禁止**直接操作数据库或 Entity |
| **Service** | `service`/`service.impl` | 业务逻辑实现，事务管理，调用 Repository | 通过接口调用，返回 DTO |
| **Repository/DAO** | `mapper` | 数据库 CRUD 操作 | 继承 MyBatis-Plus Mapper，或使用 Easy-ES Mapper |
| **Entity** | `domain.entity` | 数据库表映射 | **禁止**直接返回给前端，需转换为 DTO/VO |

### 2. 数据传输对象 (DTO/VO) 命名规范
- **Domain Object (DO)**: 映射数据库表，仅持久化使用。
- **Data Transfer Object (DTO)**: 用于接口传输，包含业务逻辑需要的数据。
- **Value Object (VO)**: 用于前端展示，包含格式化数据。
- **Business Object (BO)**: 用于封装复杂业务逻辑参数。

### 3. 异常与日志规范
- **日志框架**：使用 `@Slf4j`，支持 Logback/Logstash (SkyWalking)。
- **异常处理**：使用统一异常处理器 (`GlobalExceptionHandler`)，返回标准错误码。
- **SQL 注入**：严格使用 MyBatis-Plus 的 Wrapper 或注解查询，禁止拼接 SQL 字符串。

### 4. 接口与实现分离
- 所有业务接口（如 `UserService`）定义在 `service` 包。
- 所有实现类（如 `UserServiceImpl`）定义在 `service.impl` 包。
- 所有 Controller 返回对象需为 DTO 或 VO。

### 5. 代码注释规范
- **多语言支持**：所有类、方法、字段的注释请使用 **中文** 编写，确保团队成员阅读无障碍。
- **Javadoc**：必须包含 `@author` (作者) 和 `@description` (描述)。

## 五、安全与性能规范

### 输入校验
- 使用 `@Valid` 配合 Spring Validation (Jakarta 依赖)，对 DTO 进行参数校验。
- 敏感字段（如密码）需加密存储或脱敏处理。

### 性能优化
- **数据库**：使用 MyBatis-Plus 的 `@EntityGraph` 避免 N+1 查询问题。
- **缓存**：热点数据建议使用 Redisson 分布式锁或 Caffeine 本地缓存。
- **并发**：分布式锁推荐使用 `Lock4j` 组件。

### 安全规范
- **SQL 注入**：严禁手动拼接 SQL。
- **XSS/CSRF**：利用 `ruoyi-common-web` 中的过滤器进行防护。
- **权限控制**：在 Controller 或 Service 层使用 `@SaCheckPermission` 等注解进行权限校验。

## 六、代码风格规范

### 命名规范
| 类型 | 规则 | 示例 |
| :--- | :--- | :--- |
| 类名 | UpperCamelCase (大驼峰) | `UserServiceImpl` |
| 方法/变量 | lowerCamelCase (小驼峰) | `getUserById()` |
| 常量 | UPPER_SNAKE_CASE (全大写下划线) | `MAX_LOGIN_ATTEMPTS` |

### Lombok 使用
为简化代码，强制使用 Lombok 注解：
- **实体类**：`@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`
- **Service**：`@Slf4j` (日志)

## 七、编码原则总结

| 原则 | 说明 |
| :--- | :--- |
| **SOLID** | 高内聚、低耦合，增强可维护性与可扩展性 |
| **DRY** | 避免重复代码，提高复用性 |
| **KISS** | 保持代码简洁易懂 |
| **YAGNI** | 不实现当前不需要的功能 |
| **OWASP** | 防范常见安全漏洞（SQL注入、XSS、CSRF） |
