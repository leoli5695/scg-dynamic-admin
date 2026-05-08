# Seckill Core Engine - 本地开发环境配置指南

## 环境要求

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 运行时 |
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
| RocketMQ Broker | localhost:30911 | 消息队列 |
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

已在 `.env` 文件中配置：

```
MYSQL_USER=root
MYSQL_PASSWORD=123456
REDIS_HOST=localhost
REDIS_PORT=30379
ROCKETMQ_NAMESRV=localhost:30976
ES_URIS=http://localhost:30920
```

## 验证

启动成功后访问：

- 健康检查: http://localhost:8080/actuator/health
- Prometheus 指标: http://localhost:8080/actuator/prometheus
- H2 控制台 (如启用): http://localhost:8080/h2-console

## 测试秒杀接口

```bash
curl -X POST http://localhost:8080/seckill/do \
  -H "Content-Type: application/json" \
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
│   ├── config/           # 配置类
│   ├── controller/       # 控制器
│   ├── service/          # 业务逻辑
│   ├── mapper/           # 数据访问层
│   ├── entity/           # 实体类
│   ├── dto/              # 数据传输对象
│   ├── enums/            # 枚举
│   ├── mq/               # 消息队列
│   │   ├── listener/     # 事务监听器
│   │   └── consumer/     # 消费者
│   ├── redis/lua/        # Lua 脚本封装
│   └── exception/        # 异常处理
├── src/main/resources/
│   ├── application.yml   # 主配置
│   ├── sharding-config.yaml  # 分库分表配置
│   └── lua/              # Lua 脚本
└── db/init.sql           # 数据库初始化脚本
```
