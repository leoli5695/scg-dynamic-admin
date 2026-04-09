#!/bin/bash

# K8s 部署脚本
# 用于将服务从 Docker Compose 迁移到 k3s

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
kubectl rollout status deployment/nacos -n demo-services --timeout=300s

echo "等待 Consul..."
kubectl rollout status deployment/consul -n demo-services --timeout=300s

echo "等待 Prometheus..."
kubectl rollout status deployment/prometheus -n demo-services --timeout=300s

# 部署应用服务
echo ""
echo "=========================================="
echo "[6/7] 部署 Demo Services..."
echo "=========================================="
kubectl apply -f demo-nacos.yaml
kubectl apply -f demo-consul.yaml

echo ""
echo "等待 Demo Nacos Services..."
kubectl rollout status deployment/demo-nacos -n demo-services --timeout=300s

echo "等待 Demo Consul Services..."
kubectl rollout status deployment/demo-consul -n demo-services --timeout=300s

# 显示状态
echo ""
echo "=========================================="
echo "[7/7] 部署完成!"
echo "=========================================="
echo ""
echo "服务状态:"
echo ""
kubectl get pods -n demo-services
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
echo "  kubectl logs -f -n demo-services -l app=nacos"
echo "  kubectl logs -f -n demo-services -l app=consul"
echo "  kubectl logs -f -n demo-services -l app=prometheus"
echo "  kubectl logs -f -n demo-services -l app=demo-nacos"
echo "  kubectl logs -f -n demo-services -l app=demo-consul"
echo ""
echo "完成!"