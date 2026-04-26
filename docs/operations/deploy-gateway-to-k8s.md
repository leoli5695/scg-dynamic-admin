# Gateway 部署到 K8s 操作指南

> 手动构建镜像并部署到 Kubernetes (Rancher Desktop) 的完整流程

---

## 概述

本文档描述从代码改动到部署到 K8s 集群的完整操作流程：

```
┌─────────────────────────────────────────────────────────┐
│                    部署流程                               │
│                                                          │
│   代码改动 → Maven 编译 → Docker 构建 → K8s 滚动更新     │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 前置条件

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | Maven 编译 |
| Docker | Desktop | Rancher Desktop 内置 |
| Kubectl | 任意版本 | K8s 命令行工具 |
| Rancher Desktop | 最新版 | 本地 K8s 集群 |

---

## 第一步：编译 JAR 包

### 1.1 进入项目目录

```bash
cd d:\source\my-gateway
```

### 1.2 Maven 编译

```bash
# 跳过测试编译（推荐，避免测试代码编译问题）
mvn clean package -Dmaven.test.skip=true

# 或者跳过测试执行
mvn clean package -DskipTests
```

### 1.3 验证编译结果

```bash
ls target/my-gateway-1.0.0.jar
```

输出示例：
```
-rw-r--r-- 1 user group 65334306 日期 my-gateway/target/my-gateway-1.0.0.jar
```

---

## 第二步：构建 Docker 镜像

### 2.1 Dockerfile 位置

```
my-gateway/Dockerfile
```

### 2.2 构建镜像

```bash
cd my-gateway
docker build -t my-gateway:latest .
```

### 2.3 构建输出示例

```
#0 building with "desktop-linux" instance using docker driver
#1 [internal] load build definition from Dockerfile
#2 [1/7] FROM ghcr.io/graalvm/graalvm-community:17
#3 [6/7] COPY target/my-gateway-1.0.0.jar app.jar
#4 [7/7] RUN chown -R gateway:gateway /app
#5 exporting to image
#5 naming to docker.io/library/my-gateway:latest
```

### 2.4 验证镜像

```bash
docker images my-gateway:latest
```

---

## ⚠️ 重要：Rancher Desktop 双 Docker 环境问题

> **这是最关键的步骤！很多部署失败都是因为忽略了这个问题！**

### 问题背景

Rancher Desktop 有 **两个独立的 Docker daemon**：

| 环境 | 用途 | 命令 |
|------|------|------|
| Windows Docker | 本地开发、镜像构建 | `docker` |
| WSL rancher-desktop Docker | K8s 集群使用的镜像 | `wsl -d rancher-desktop docker` |

**K8s Pod 使用的是 WSL rancher-desktop 中的镜像，而不是 Windows Docker 的镜像！**

这意味着：
- 在 Windows 上用 `docker build` 构建的镜像，K8s **无法直接使用**
- 必须**显式导入**到 WSL rancher-desktop 环境

### 2.5 导入镜像到 WSL rancher-desktop（必须执行）

```bash
# 方法一：管道导入（推荐）
docker save my-gateway:latest | wsl -d rancher-desktop docker load

# 方法二：文件导入
docker save my-gateway:latest -o /d/temp/my-gateway.tar
wsl -d rancher-desktop docker load -i /mnt/d/temp/my-gateway.tar
```

### 2.6 验证镜像已正确导入

**对比镜像 ID，确保两边一致：**

```bash
# Windows Docker 镜像 ID
docker images my-gateway:latest --format "{{.ID}}"

# WSL rancher-desktop Docker 镜像 ID（K8s 使用的是这个）
wsl -d rancher-desktop docker images my-gateway --format "{{.ID}}"
```

**两个 ID 必须一致！否则 K8s Pod 会使用旧镜像。**

### 典型错误现象

如果忘记导入镜像，会看到以下症状：

```bash
# Pod 使用的镜像 digest 与本地镜像 ID 不一致
kubectl get pods -n test -l app=gateway-j2aabkvk -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].imageID}{"\n"}{end}'
# 输出: docker-pullable://my-gateway@sha256:12bbc4905400...  (旧 digest)

docker images my-gateway:latest --format "{{.ID}}"
# 输出: 97ac9ab5e22c  (新 ID)

# 两者不一致 → Pod 使用的是旧镜像！
```

---

## 第三步：部署到 K8s 集群

### 3.1 查看当前部署状态

```bash
# 查看 deployment
kubectl get deployments -n test

# 查看 pods
kubectl get pods -n test -l app=gateway-j2aabkvk

# 查看当前镜像
kubectl get pods -n test -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'
```

### 3.2 滚动重启 Deployment

由于镜像使用 `imagePullPolicy: Never`（本地镜像），只需重启即可使用新镜像：

```bash
kubectl rollout restart deployment gateway-j2aabkvk -n test
```

### 3.3 等待滚动更新完成

```bash
kubectl rollout status deployment gateway-j2aabkvk -n test --timeout=120s
```

输出示例：
```
Waiting for deployment "gateway-j2aabkvk" rollout to finish: 1 out of 2 new replicas have been updated...
Waiting for deployment "gateway-j2aabkvk" rollout to finish: 1 old replicas are pending termination...
deployment "gateway-j2aabkvk" successfully rolled out
```

### 3.4 验证新 Pod 状态

```bash
kubectl get pods -n test -l app=gateway-j2aabkvk
```

输出示例：
```
NAME                                READY   STATUS    RESTARTS   AGE
gateway-j2aabkvk-7b6f47dbc7-c9htx   1/1     Running   0          56s
gateway-j2aabkvk-7b6f47dbc7-mk42q   1/1     Running   0          90s
```

---

## 第四步：验证部署

### 4.1 查看日志

```bash
# 查看所有 Pod 日志
kubectl logs -n test -l app=gateway-j2aabkvk --tail=50

# 查看单个 Pod 日志
kubectl logs -n test gateway-j2aabkvk-7b6f47dbc7-c9htx
```

### 4.2 查看访问日志（stdout）

如果配置了 `logToConsole=true`，访问日志输出到 stdout：

```bash
# 过滤访问日志（JSON 格式）
kubectl logs -n test -l app=gateway-j2aabkvk | grep '{"@timestamp"'
```

### 4.3 检查健康状态

```bash
# 通过 Service 访问健康检查端点
curl http://localhost:30081/actuator/health
```

---

## 一键脚本

将上述步骤合并为脚本（**包含关键的第 3 步镜像导入**）：

```bash
#!/bin/bash
# deploy-gateway.sh
# Rancher Desktop 环境完整部署脚本

set -e

echo "=== Step 1: Build JAR ==="
cd d:/source/my-gateway
mvn clean package -Dmaven.test.skip=true

echo "=== Step 2: Build Docker Image (Windows) ==="
docker build -t my-gateway:latest .

echo "=== Step 3: Import Image to WSL rancher-desktop (关键步骤!) ==="
# 这一步是 Rancher Desktop 环境特有的，必须执行！
docker save my-gateway:latest | wsl -d rancher-desktop docker load

echo "=== Step 4: Verify Image Sync ==="
WIN_ID=$(docker images my-gateway:latest --format "{{.ID}}")
WSL_ID=$(wsl -d rancher-desktop docker images my-gateway --format "{{.ID}}" 2>/dev/null | head -1)
echo "Windows Image ID: $WIN_ID"
echo "WSL Image ID:     $WSL_ID"
if [ "$WIN_ID" != "$WSL_ID" ]; then
    echo "WARNING: Image IDs mismatch! Please re-import."
    exit 1
fi
echo "Image sync verified!"

echo "=== Step 5: Deploy to K8s ==="
kubectl rollout restart deployment gateway-j2aabkvk -n test
kubectl rollout status deployment gateway-j2aabkvk -n test --timeout=120s

echo "=== Step 6: Verify Pod Image ==="
kubectl get pods -n test -l app=gateway-j2aabkvk -o custom-columns="NAME:.metadata.name,IMAGE_ID:.status.containerStatuses[0].imageID"

echo "=== Deployment Complete ==="
```

### PowerShell 版本（Windows 用户）

```powershell
# deploy-gateway.ps1

Write-Host "=== Step 1: Build JAR ===" -ForegroundColor Green
Set-Location D:\source\my-gateway
mvn clean package -Dmaven.test.skip=true

Write-Host "=== Step 2: Build Docker Image ===" -ForegroundColor Green
docker build -t my-gateway:latest .

Write-Host "=== Step 3: Import Image to WSL (关键步骤!) ===" -ForegroundColor Yellow
docker save my-gateway:latest | wsl -d rancher-desktop docker load

Write-Host "=== Step 4: Verify Image Sync ===" -ForegroundColor Green
$winId = docker images my-gateway:latest --format "{{.ID}}"
$wslId = wsl -d rancher-desktop docker images my-gateway --format "{{.ID}}" 2>$null
Write-Host "Windows ID: $winId"
Write-Host "WSL ID:     $wslId"

Write-Host "=== Step 5: Deploy to K8s ===" -ForegroundColor Green
kubectl rollout restart deployment gateway-j2aabkvk -n test
kubectl rollout status deployment gateway-j2aabkvk -n test --timeout=120s

Write-Host "=== Deployment Complete ===" -ForegroundColor Green
kubectl get pods -n test -l app=gateway-j2aabkvk
```

---

## K8s 配置文件参考

### Deployment YAML (`k8s/my-gateway.yaml`)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gateway-j2aabkvk
  namespace: test
spec:
  replicas: 2
  selector:
    matchLabels:
      app: gateway-j2aabkvk
  template:
    spec:
      containers:
      - name: gateway
        image: my-gateway:latest
        imagePullPolicy: Never  # 使用本地镜像
        ports:
        - containerPort: 80
          name: http
        - containerPort: 9091
          name: management
        env:
        - name: NACOS_SERVER_ADDR
          value: "nacos.test.svc.cluster.local:8848"
        - name: GATEWAY_ADMIN_URL
          value: "http://host.rancher-desktop.internal:9090"
        - name: REDIS_HOST
          value: "redis.test.svc.cluster.local"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9091
          initialDelaySeconds: 60
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9091
          initialDelaySeconds: 30
```

---

## 常见问题

### Q1: 测试代码编译失败

**原因**: 测试代码与主代码不同步

**解决**: 使用 `-Dmaven.test.skip=true` 跳过测试编译

```bash
mvn clean package -Dmaven.test.skip=true
```

### Q2: 镜像拉取失败

**原因**: `imagePullPolicy` 设置错误

**解决**: 对于本地镜像，使用 `imagePullPolicy: Never` 或 `IfNotPresent`

```yaml
imagePullPolicy: IfNotPresent
```

### Q3: Pod 启动失败

**排查步骤**:

```bash
# 查看 Pod 状态
kubectl describe pod <pod-name> -n test

# 查看 Pod 日志
kubectl logs <pod-name> -n test

# 查看事件
kubectl get events -n test --sort-by='.lastTimestamp'
```

### Q4: 滚动更新卡住

**排查步骤**:

```bash
# 查看 rollout 状态
kubectl rollout status deployment gateway-j2aabkvk -n test

# 查看 ReplicaSet
kubectl get rs -n test -l app=gateway-j2aabkvk

# 手动回滚（必要时）
kubectl rollout undo deployment gateway-j2aabkvk -n test
```

### Q5: ⚠️ 部署后代码没有更新（最常见问题）

**症状**:
- `kubectl rollout restart` 后 Pod 重建成功
- 但日志显示的还是旧代码逻辑
- Pod 的 `imageID` digest 与本地镜像 ID 不一致

**根本原因**: Rancher Desktop 双 Docker 环境问题，忘记导入镜像到 WSL

**排查步骤**:

```bash
# 1. 检查 Pod 使用的镜像 digest
kubectl get pods -n test -l app=gateway-j2aabkvk -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].imageID}{"\n"}{end}'

# 输出示例: docker-pullable://my-gateway@sha256:12bbc4905400...

# 2. 检查 Windows Docker 镜像 ID
docker images my-gateway:latest --format "{{.ID}}"

# 输出示例: 97ac9ab5e22c

# 3. 检查 WSL rancher-desktop 镜像 ID（K8s 实际使用的）
wsl -d rancher-desktop docker images my-gateway --format "{{.ID}}"

# 如果 WSL ID 与 Windows ID 不一致，说明镜像未同步！
```

**解决方案**:

```bash
# 重新导入镜像到 WSL rancher-desktop
docker save my-gateway:latest | wsl -d rancher-desktop docker load

# 再次 rollout restart
kubectl rollout restart deployment gateway-j2aabkvk -n test
kubectl rollout status deployment gateway-j2aabkvk -n test --timeout=120s

# 验证新 Pod 的 imageID 是否更新
kubectl get pods -n test -l app=gateway-j2aabkvk -o custom-columns="NAME:.metadata.name,IMAGE_ID:.status.containerStatuses[0].imageID"
```

### Q6: 如何快速判断镜像是否正确同步？

**一键检查命令**:

```bash
# 对比 Windows 和 WSL 的镜像 ID
echo "Windows: $(docker images my-gateway:latest --format '{{.ID}}')"
echo "WSL:     $(wsl -d rancher-desktop docker images my-gateway --format '{{.ID}}' 2>/dev/null | head -1)"

# 对比 Pod 实际使用的 digest（应该包含 WSL 镜像 ID）
kubectl get pods -n test -l app=gateway-j2aabkvk -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'
```

如果输出类似：
```
Windows: 97ac9ab5e22c
WSL:     97ac9ab5e22c
Pod:     docker-pullable://my-gateway@sha256:97ac9ab5e22c...
```

三者 ID 一致 → 部署正确！

### Q7: Docker 构建缓存导致旧 JAR被打包

**症状**: Docker 镜像构建成功，但里面包含的是旧的 JAR 文件

**原因**: Maven 编译时间早于 Docker 构建时间，Docker 使用了缓存的旧 JAR

**解决**: 确保 JAR 编译完成后再构建镜像，或使用 `--no-cache`

```bash
# 1. 先重新编译 JAR
cd my-gateway
mvn clean package -Dmaven.test.skip=true

# 2. 无缓存构建镜像（确保使用新 JAR）
docker build --no-cache -t my-gateway:latest .

# 3. 导入到 WSL
docker save my-gateway:latest | wsl -d rancher-desktop docker load

# 4. 部署
kubectl rollout restart deployment gateway-j2aabkvk -n test
```

---

## 相关文档

- [Kubernetes Integration](features/kubernetes-integration.md) - Admin UI K8s 管理
- [K8s 环境日志采集推荐配置](#) - stdout 日志采集方案
- [Access Log 配置](features/access-log.md) - 访问日志功能说明