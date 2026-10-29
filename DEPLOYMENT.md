# Gateway Admin - 部署指南

## 升级前数据库迁移

生产环境使用 `ddl-auto: validate`，请在启动新版 gateway-admin 前，对其数据库执行
`gateway-admin/src/main/resources/sql/system-settings.sql`。脚本创建功能开关表并初始化默认记录，
可以重复执行，已有开关值和操作人不会被覆盖。首次默认值与旧版本一致（功能开启），管理员可在平台管理中调整。

## Docker 部署（推荐）

### 前置要求

- Docker 20.10+
- Docker Compose v2.0+

### 快速开始

1. **配置环境变量（必须）**

```bash
cp .env.example .env
# 编辑 .env，把每一个 change_me_* 占位符换成真实值
```

`docker-compose.yml` 里这些变量全部用 `${VAR:?...}` 声明，缺任何一个 compose 会**直接报错退出**并指出变量名，不会用弱默认值偷偷启动。必填项共 8 个：

| 变量 | 用途 | 生成方式 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | 容器初始化与健康检查 | `openssl rand -base64 32` |
| `MYSQL_USERNAME` | 应用连接账号 | 自定，如 `gateway_user` |
| `MYSQL_PASSWORD` | 应用连接口令 | `openssl rand -base64 32` |
| `ADMIN_USERNAME` | **首个管理员账号** | 自定 |
| `ADMIN_PASSWORD` | **首个管理员口令，至少 12 位** | 自定 |
| `GATEWAY_ADMIN_JWT_SECRET` | JWT 签名 | `openssl rand -base64 64` |
| `GATEWAY_ENCRYPTION_KEY` | AI Key / SSL keystore 加密（≥16 字符） | `openssl rand -base64 32` |
| `GATEWAY_INTERNAL_TOKEN` | 网关实例上报端点的共享密钥 | `openssl rand -base64 32` |

> `ADMIN_USERNAME` / `ADMIN_PASSWORD` 以前不在任何模板里，而 `AuthService` 只在两者都存在时才创建初始管理员，否则只打一条 WARN 就返回。加上系统没有自助注册，结果就是「栈起来了但一个能登录的账号都没有」。

2. **启动所有服务**

```bash
docker-compose up -d
```

这将启动以下服务：
- MySQL 8.0（`127.0.0.1:3306`，仅本机可连）
- Nacos（`127.0.0.1:8848` / `9848`，仅本机可连）
- Backend API（端口 8080）
- Frontend（端口 80）

> **这套 compose 只包含控制面**及其两个数据存储。数据面 `my-gateway`、Redis、`license-server` 都还不在里面——也就是说你能登录、能配路由，但没有网关真正转发流量。
>
> Prometheus **已经在** compose 里，但是 opt-in：`prometheus` 服务带 `profiles: ["monitoring"]`，`docker compose up` 会忽略它，要跑得起 `docker compose --profile monitoring up -d`。镜像 `prom/prometheus:v2.53.0`，端口映射 `127.0.0.1:9091:9090`（只绑回环），挂载 `./prometheus/prometheus.yml` 与 `seckill-core-engine` 的告警规则；启动前还要按 `prometheus/prometheus.yml` 里的注释生成 `prometheus/internal-token`（`printf '%s' "$GATEWAY_INTERNAL_TOKEN" > prometheus/internal-token`）；服务发现 job 通过 `credentials_file` 以 bearer 方式带上它，因为原生 `http_sd_configs` 发不了自定义的 `X-Internal-Token` 头（`prometheus/prometheus.yml:66-79`）。
>
> **授权版本**：`license-server` 不在这套栈里，但这**不代表**授权被关掉。compose 给 backend 设的是 `LICENSE_ENABLED: "true"` + `LICENSE_FALLBACK_EDITION: "ENTERPRISE"`。理由有两层：
>
> 1. `LICENSE_ENABLED=false` 会让应用**直接起不来**。这个开关 gate 的是整个 `LicenseAutoConfiguration`（`@ConditionalOnProperty(prefix = "license.client", name = "enabled", ...)`），`LicenseValidator` / `LicenseStore` / `FeatureGatekeeper` 三个 bean 一起消失；而 `LicenseFeatureHelper` 在 gateway-admin 和 my-gateway 里都是无条件的 `@Component` 且构造注入它们，于是启动报 `required a bean of type 'com.leoli.license.client.validator.LicenseStore' that could not be found`。它从来不是「省掉连接尝试」的降噪开关。
> 2. 不存在「连不上 license-server 就退回 BASIC」这回事。license 校验是**纯离线 RSA 验签**（`LicenseValidator`），`license-client` 里没有任何 HTTP 客户端，也没有 `LICENSE_SERVER_URL` 这种配置项；没有 license 时加载的是本地构造的 fallback license，档位取 `license.client.fallback-edition`，而两个应用的 yml 都写成 `${LICENSE_FALLBACK_EDITION:ENTERPRISE}`（`my-gateway/.../application.yml:471`、`gateway-admin/.../application.yml:421`），配额全为 `-1`（无限）。BASIC（1 实例 / 10 路由 / 5 服务 / 100 QPS，见 `LicenseValidator.buildFallbackLicense`）只有你显式设 `LICENSE_FALLBACK_EDITION=BASIC` 才会生效。
>
> 所以要按真实授权码跑，需要把 `license-server` 加进栈，并配 `GATEWAY_LICENSE_CODE` + `LICENSE_TRUSTED_PUBLIC_KEYS`（钉住签发公钥，否则任何人都能自签一张通过验签；留空时验签 fail-closed，退回 fallback 档）。
>
> 内存：四个服务合计约 3.4GB。compose 已给每个服务设了 `mem_limit`，JVM 按容器上限而非宿主机内存计算堆大小（此前 backend 的 ENTRYPOINT 是 exec 形式，`JAVA_OPTS` 根本不生效）。

3. **查看日志**

```bash
docker-compose logs -f
```

4. **访问应用**

打开浏览器访问: http://localhost

登录凭据就是你在 `.env` 里设的 `ADMIN_USERNAME` / `ADMIN_PASSWORD`。

> **不存在 `admin` / `admin123` 这样的默认账号**（旧版文档这么写，是错的：仓库里搜不到 `admin123`，而且 8 位口令连 `AuthService` 的 12 位下限都过不了）。初始管理员**只在首次启动时**、且仅在两个环境变量都存在时才创建；没有自助注册入口，所以漏设就只能清库重来。

### 常用命令

```bash
# 停止所有服务
docker-compose down

# 重启服务
docker-compose restart

# 查看服务状态
docker-compose ps

# 重新构建并启动
docker-compose up -d --build

# 清理数据卷（谨慎使用！）
docker-compose down -v
```

### 生产环境部署

1. **使用外部数据库**

修改 `docker-compose.yml`，移除mysql服务，更新backend的环境变量：

```yaml
environment:
  MYSQL_HOST: your-external-db-host
  MYSQL_PORT: "3306"
  MYSQL_USERNAME: ${MYSQL_USERNAME}
  MYSQL_PASSWORD: ${MYSQL_PASSWORD}
```

> 变量名必须和 `gateway-admin/src/main/resources/application.yml` 里读的一致（`MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`，后两个**没有默认值**）。此前 compose 传的是 `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`，五个名字代码一个都不读，容器启动即 `Could not resolve placeholder 'MYSQL_USERNAME'` 退出。其中 `DB_NAME` 是纯死变量——库名硬编码在 JDBC URL 里。

2. **使用HTTPS**

在nginx.conf中配置SSL证书：

```nginx
server {
    listen 443 ssl http2;
    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    # ... 其他配置
}
```

3. **资源限制**

compose 里已经给每个服务设了 `mem_limit`（mysql 768m / nacos 1024m / backend 1536m / frontend 64m），backend 的 `JAVA_OPTS` 也真正生效了（`-XX:MaxRAMPercentage=60`，按容器上限算堆）。要调 CPU 或改成 Swarm 语义的 `deploy.resources`，再按下面覆盖：

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
    reservations:
      memory: 512M
```

> 注意 `deploy.resources` 只在 Swarm 模式下被 `docker compose up` 完整解释；单机部署请继续用 `mem_limit`。另外**不要**同时设 `-Xmx` 和 `-XX:MaxRAMPercentage`——前者会让后者完全失效。`my-gateway/Dockerfile:54-57` 曾经就是这个自相矛盾的组合（`-Xmx512m` + `MaxRAMPercentage=75.0`），现已修掉，只留 `-Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`；backend 侧同理是 `-XX:MaxRAMPercentage=60`（`gateway-admin/Dockerfile:65`），两边都没有 `-Xmx`。

## 生产实际拓扑（2026-10-28 SSH 实测）

上面这套 compose 是本地/单机方案，**线上并不是这么跑的**。当前生产是两台阿里云 ECS 上的裸 `docker run`：

| 角色 | 内容 |
|---|---|
| 应用服务器 | 只有 2 个容器：`gateway-admin`、`my-gateway`，镜像都是 `eclipse-temurin:21-jre-jammy`，都以 `-u 1000:1000` 运行，同时挂在 `bridge` 和 `gateway-internal` 两个网络上 |
| 依赖服务器 | MariaDB **10.11.18**（不是 MySQL 8）、Redis、Nacos、Prometheus |

几个容易踩的点：

- **jar 是 bind mount**（`/opt/gateway-admin/app.jar`、`/opt/my-gateway/app.jar`），不是打进镜像的。所以换 jar **必须重启容器**才生效。部署入口是 `/opt/deploy-gateway.sh`（755 root:root），重建脚本是 `/opt/{gateway-admin,my-gateway}/run.sh`（700 root:root）。
- **nginx 只听 80 和 127.0.0.1:8091**：`*:80` 是 `gateway-ui.conf`（default_server），`127.0.0.1:8091` 是 `gh-relay.conf`。docker-proxy 侧是 `*:8080`（网关）和 `127.0.0.1:9090`（admin）。原先的 **81 端口已于 2026-10-28 人为注释停用**（`/etc/nginx/nginx.conf:46` 是 `#DISABLED-20261028:         listen 81;`），任何写「nginx 监听 80/81」的资料都已过期。
- **目录属主**：`/opt/my-gateway` 是 root:root 755（因为里面有 root:600 的 `env` 文件），`/opt/gateway-admin` 是 www:www；容器以 1000:1000 运行，所以 `/opt/my-gateway/data` 必须是 www:www(1000:1000) 可写（应用起来后会自建 `data/token-quota/{instanceId}/` 与 `data/token-usage/{instanceId}/`）。
- **`ddl-auto` 运行时是 `update` 不是 `validate`**：`gateway-admin/src/main/resources/application-prod.yml:14` 写的是 `validate`，但容器 env 里设了 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`，而 OS 环境变量优先级高于 jar 内的 profile yml。推理 schema 行为前先看运行中容器的 env。
- **稳态日志不是故障**：`Loaded 0 route(s)` 在没配路由时就是稳态；启动期一大批 `spring.cloud.gateway.*` → `spring.cloud.gateway.server.webflux.*` 的 `PropertiesMigrationListener` WARN 是正常的键改名提示；部署窗口内 my-gateway 对 `gateway-admin:9090` 心跳失败 3~4 轮（`Connection refused`）属预期。

## 本地开发部署

### 后端

```bash
cd gateway-admin
mvn spring-boot:run
```

### 前端

```bash
cd gateway-ui
npm install
npm run dev
```

## 健康检查

- Backend: http://localhost:8080/actuator/health
- Nacos: http://localhost:8848/nacos/
- Frontend: http://localhost/

## 故障排查

### 容器无法启动

```bash
# 查看详细日志
docker-compose logs backend
docker-compose logs frontend
```

### 数据库连接失败

确保MySQL已启动并健康：
```bash
docker-compose ps mysql
```

### JWT密钥未配置

错误信息: `JWT secret is required`

解决: 在.env文件中设置 `GATEWAY_ADMIN_JWT_SECRET`
