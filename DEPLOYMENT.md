# Gateway Admin - 部署指南

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

> **这套 compose 只包含控制面**及其两个数据存储。数据面 `my-gateway`、Redis、`license-server`、Prometheus 都还不在里面——也就是说你能登录、能配路由，但没有网关真正转发流量。
>
> **授权版本**：`license-server` 不在这套栈里，backend 因此设了 `LICENSE_ENABLED=false`。要说清楚的是——**这个开关不解锁任何功能**：`enabled=true` 时 admin 会去连容器内的 `localhost:8090`（即它自己）、失败、再加载 fallback；`enabled=false` 时 `LicenseStore.initialize()` 直接加载同一个 fallback。两种情况拿到的都是 **BASIC 版（1 实例 / 10 路由 / 5 服务 / 100 QPS）**，而 admin 侧的 `LicenseFeatureHelper` 是无条件查询 gatekeeper 的（没有 `enabled` 短路），所以审计导出这类 ENTERPRISE 功能一样被挡住。设成 `false` 只是省掉无意义的连接尝试和日志噪音。要真正按授权跑，得把 `license-server` 加进栈并配 `LICENSE_ENABLED=true` + `LICENSE_SERVER_URL` + `LICENSE_CODE`。
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

> 注意 `deploy.resources` 只在 Swarm 模式下被 `docker compose up` 完整解释；单机部署请继续用 `mem_limit`。另外**不要**同时设 `-Xmx` 和 `-XX:MaxRAMPercentage`——前者会让后者完全失效（`my-gateway/Dockerfile` 里就还是这个自相矛盾的组合）。

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
