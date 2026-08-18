# Gateway Admin - 部署指南

## Docker 部署（推荐）

### 前置要求

- Docker 20.10+
- Docker Compose v2.0+

### 快速开始

1. **配置环境变量**

```bash
cp .env.example .env
# 编辑 .env 文件，设置安全的密码和JWT密钥
```

生成JWT密钥：
```bash
openssl rand -base64 64
```

2. **启动所有服务**

```bash
docker-compose up -d
```

这将启动以下服务：
- MySQL 8.0 (端口 3306)
- Nacos (端口 8848, 9848)
- Backend API (端口 8080)
- Frontend (端口 80)

3. **查看日志**

```bash
docker-compose logs -f
```

4. **访问应用**

打开浏览器访问: http://localhost

默认登录凭据：
- 用户名: admin
- 密码: admin123

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
  DB_HOST: your-external-db-host
  DB_PORT: 3306
  DB_PASSWORD: ${DB_PASSWORD}
```

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

在docker-compose.yml中添加资源限制：

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
    reservations:
      memory: 512M
```

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
