#!/bin/bash

# K8s 卸载脚本
# 用于删除所有部署的服务

set -e

echo "=========================================="
echo "开始卸载 k3s 集群中的服务"
echo "=========================================="

# 删除应用服务
echo "[1/3] 删除 Demo Services..."
kubectl delete -f demo-nacos.yaml --ignore-not-found=true
kubectl delete -f demo-consul.yaml --ignore-not-found=true

# 删除基础设施服务
echo "[2/3] 删除基础设施服务..."
kubectl delete -f prometheus.yaml --ignore-not-found=true
kubectl delete -f consul.yaml --ignore-not-found=true
kubectl delete -f nacos.yaml --ignore-not-found=true

# 删除命名空间
echo "[3/3] 删除命名空间..."
kubectl delete -f namespace.yaml --ignore-not-found=true

echo ""
echo "卸载完成!"