#!/bin/bash

# K8s 部署脚本 —— 仅基础设施
#
# 这个脚本部署的是 Nacos / Consul / Prometheus / demo services，也就是网关运行所依赖的
# 中间件层。**它不部署网关本体**，也不部署 MySQL、Redis、exporters、Jaeger、Portainer。
#
# 之前脚本里的命名空间写的是 demo-services，而所有 manifest 都在 namespace: test，
# 加上第 6 行的 set -e，脚本必然在 [5/7] 的 rollout status 处中断。
#
# 部署完整栈还需要（按顺序，手工执行）：
#   1. 用 kubectl create secret 带外创建 gateway-internal-secrets 与 mysql-credentials
#      （k8s/*-secrets.yaml、k8s/mysql-credentials.yaml 里是 CHANGE_ME_* 占位符，
#        不要直接 apply）
#   2. kubectl apply -f mysql.yaml -f redis.yaml
#   3. kubectl apply -f my-gateway.yaml
#   4. kubectl apply -f exporters.yaml -f middleware-exporters.yaml（可选，监控）
#   5. kubectl apply -f kustomization.yaml 目前只覆盖 6/16 个文件，别指望它

set -e

echo "=========================================="
echo "开始部署服务到 k3s 集群"
echo "=========================================="

# 检查 kubectl 是否可用
if ! command -v kubectl &> /dev/null; then
    echo "错误: kubectl 未安装或不在 PATH 中"
    exit 1
fi

# 检查 k3s 集群连接
echo "检查 k3s 集群连接..."
if ! kubectl cluster-info &> /dev/null; then
    echo "错误: 无法连接到 k3s 集群"
    echo "请确保 k3s 正在运行并且 kubeconfig 已正确配置"
    exit 1
fi

echo "集群连接成功!"
echo ""

# 创建命名空间
echo "=========================================="
echo "[1/7] 创建命名空间..."
echo "=========================================="
kubectl apply -f namespace.yaml

# 部署基础设施服务
echo ""
echo "=========================================="
echo "[2/7] 部署 Nacos..."
echo "=========================================="
kubectl apply -f nacos.yaml

echo ""
echo "=========================================="
echo "[3/7] 部署 Consul..."
echo "=========================================="
kubectl apply -f consul.yaml

echo ""
echo "=========================================="
echo "[4/7] 部署 Prometheus..."
echo "=========================================="
kubectl apply -f prometheus.yaml

# 等待基础设施服务就绪
echo ""
echo "=========================================="
echo "[5/7] 等待基础设施服务就绪..."
echo "=========================================="
echo "等待 Nacos..."
kubectl rollout status deployment/nacos -n test --timeout=300s

echo "等待 Consul..."
kubectl rollout status deployment/consul -n test --timeout=300s

echo "等待 Prometheus..."
kubectl rollout status deployment/prometheus -n test --timeout=300s

# 部署应用服务
echo ""
echo "=========================================="
echo "[6/7] 部署 Demo Services..."
echo "=========================================="
kubectl apply -f demo-nacos.yaml
kubectl apply -f demo-consul.yaml

echo ""
echo "等待 Demo Nacos Services..."
kubectl rollout status deployment/demo-nacos -n test --timeout=300s

echo "等待 Demo Consul Services..."
kubectl rollout status deployment/demo-consul -n test --timeout=300s

# 显示状态
echo ""
echo "=========================================="
echo "[7/7] 部署完成!"
echo "=========================================="
echo ""
echo "服务状态:"
echo ""
kubectl get pods -n test
echo ""
echo "服务访问信息:"
echo "--------------------------------------"
echo "Nacos UI:      http://<节点IP>:30848/nacos"
echo "Consul UI:     http://<节点IP>:30500"
echo "Prometheus UI: http://<节点IP>:30090"
echo ""
echo "默认用户名/密码 (Nacos): nacos/nacos"
echo "--------------------------------------"
echo ""
echo "查看日志命令:"
echo "  kubectl logs -f -n test -l app=nacos"
echo "  kubectl logs -f -n test -l app=consul"
echo "  kubectl logs -f -n test -l app=prometheus"
echo "  kubectl logs -f -n test -l app=demo-nacos"
echo "  kubectl logs -f -n test -l app=demo-consul"
echo ""
echo "完成!"