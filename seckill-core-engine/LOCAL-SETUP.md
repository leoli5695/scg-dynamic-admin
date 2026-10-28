# Seckill Core Engine - 本地开发环境配置指南

> **适用范围**：本指南只针对**本地开发**与 K8s `test` 命名空间。`seckill-core-engine`
> **不在生产运行**——生产服务器上只有 `gateway-admin`、`my-gateway` 两个容器，
> 本文提到的中间件 NodePort 也不属于生产环境。

## 环境要求

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时（`pom.xml:38` `<java.version>21</java.version>`） |
| Maven | 3.8+ | 构建工具 |
| MySQL | 8.x | 持久化存储 |
| Redis | 7.x | 缓存/分布式锁 |
| RocketMQ | 5.x | 消息队列 |
| Elasticsearch | 8.x | 异构索引 |

## 中间件部署

所有中间件已部署在 K8s `test` 命名空间：

| 中间件 | NodePort 地址 | 用途 |
|--------|--------------|------|
| MySQL | 127.0.0.1:3306 (本地) | root/123456 |
| Redis | localhost:30379 | 无密码 |
| RocketMQ NameServer | localhost:30976 | 消息队列 |
| RocketMQ Broker | 集群内 10911（ClusterIP，无 NodePort） | 消息队列；由 NameServer 寻址，本机不可直连 |
| RocketMQ Console | localhost:30808 | 控制台/监控 API |
| Elasticsearch | localhost:30920 | 搜索 |
| Nacos | localhost:30848 | 配置中心 |

## 数据库初始化

8 个分库已创建并初始化：

```bash
# 检查数据库
mysql -h 127.0.0.1 -P 3306 -u root -p123456 -e "SHOW DATABASES LIKE 'seckill_db_%';"

# 检查表结构
mysql -h 127.0.0.1 -P 3306 -u root -p123456 seckill_db_0 -e "SHOW TABLES;"
```

## 启动步骤

### 方式一：使用启动脚本

```bash
# 检查环境
./check-env.bat

# 启动应用
./start-local.bat
```

### 方式二：手动启动

```bash
# 1. 编译
mvn clean compile

# 2. 启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## 环境变量

`.env` **不在版本库中**（`.gitignore:79` 的 `**/.env`），克隆仓库后需要**自行创建**
`seckill-core-engine/.env`；否则 `start-local.bat:8-14` 只会打印
`[WARN] 未找到 .env 文件，使用默认配置`，而 `:41` 的 MySQL 预检查会带着空的
`%MYSQL_PASSWORD%` 去连库并直接失败退出。

```
MYSQL_USER=root
MYSQL_PASSWORD=123456
REDIS_HOST=localhost
REDIS_PORT=30379
ROCKETMQ_NAMESRV=localhost:30976
ES_URIS=http://localhost:30920
```

变量归属说明（避免误以为改 `.env` 就能改应用配置）：

- `MYSQL_USER` / `MYSQL_PASSWORD` 只被 `start-local.bat` 的 mysql 预检查使用，**Spring 不读这两个名字**；
  应用侧的账号密码键是 `SECKILL_DB_USER` / `SECKILL_DB_PASSWORD`（`application.yml:39-40`，无默认值）。
- `REDIS_HOST` / `REDIS_PORT` / `ROCKETMQ_NAMESRV` / `ES_URIS` 是 `application.yml`
  真实读取的键（`:21-22,:50,:58,:70`），但 `dev` profile 已在 `application-dev.yml:22-52`
  写死了同样的值（localhost:30379 / localhost:30976 / http://localhost:30920 / root:123456），
  所以用 `-Dspring-boot.run.profiles=dev` 启动时 `.env` 里这几项是冗余的。
- 秒杀身份校验（`/seckill/**` 需要 JWT）读取 `SECKILL_IDENTITY_ISSUER` /
  `SECKILL_IDENTITY_AUDIENCE` / `SECKILL_IDENTITY_PUBLIC_KEY_PEM` 或 `SECKILL_IDENTITY_JWK_SET_URI`
  （`application.yml:177-182`），未配置时会拒绝所有用户请求。

## 验证

启动成功后访问：

- 健康检查: http://localhost:8080/actuator/health
- Prometheus 指标: http://localhost:8080/actuator/prometheus
- H2 控制台 (如启用): http://localhost:8080/h2-console

## 测试秒杀接口

`/seckill/**` 受 OAuth2 资源服务器保护（`SeckillIdentitySecurity.java:59`），
必须带 `Authorization: Bearer <jwt>`，否则返回 401；body 里的 `userId` 不是凭证，
会被 JWT 中的用户 ID 覆盖（`SeckillController.java:86`）。

```bash
curl -X POST http://localhost:8080/seckill/do \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt>" \
  -d '{
    "userId": 10001,
    "seckillId": 1,
    "productId": 1,
    "quantity": 1
  }'
```

## 常见问题

### 1. RocketMQ 未就绪

RocketMQ 正在拉取镜像，请等待几分钟后检查：

```bash
kubectl get pods -n test | grep rocketmq
```

### 2. 编译失败 (Lombok)

确保 IDE 已启用 Lombok 插件：
- IDEA: Settings → Plugins → 搜索并安装 Lombok Plugin

### 3. Redis 连接失败

检查 Redis 是否运行：

```bash
kubectl get pods -n test | grep redis
```

## 项目结构

```
seckill-core-engine/
├── src/main/java/com/seckill/
│   ├── SeckillApplication.java
│   ├── annotation/       # @InternalApi 等注解
│   ├── config/           # 配置类（含 SeckillIdentitySecurity、RedisConfig）
│   ├── controller/       # 控制器
│   ├── service/          # 业务逻辑
│   ├── mapper/           # 数据访问层
│   ├── entity/           # 实体类
│   ├── dto/              # 数据传输对象
│   ├── enums/            # 枚举
│   ├── exception/        # 异常处理
│   ├── interceptor/      # InternalApiInterceptor（IP 白名单校验）
│   ├── mq/               # 消息队列
│   │   ├── listener/     # 事务监听器
│   │   └── consumer/     # 消费者
│   ├── redis/lua/        # Lua 脚本封装
│   └── util/             # SnowflakeIdGenerator
├── src/main/resources/
│   ├── application.yml       # 主配置
│   ├── application-dev.yml   # 本地开发 profile
│   ├── application-prod.yml  # 生产 profile
│   ├── sharding-config.yaml  # 分库分表配置（8 个分库）
│   ├── lua/                  # 5 个 Lua 脚本
│   ├── mapper/               # MyBatis XML（OrderMapper.xml）
│   ├── sql/                  # 4 个迁移脚本（indexes / order-delivery / order-stock-release / sharding-init）
│   ├── grafana/              # seckill-dashboard.json
│   └── prometheus/           # seckill-alerts.yml
├── db/init.sql               # 数据库初始化脚本
├── docs/stress-test-guide.md # 压测配置指南
├── k8s/                      # 8 个中间件 yaml + MIDDLEWARE-DEPLOYMENT-GUIDE.md
├── scripts/                  # 迁移与规划脚本（.mjs）
├── check-env.bat             # 环境检查
├── start-local.bat           # 本地启动
└── pom.xml
```
