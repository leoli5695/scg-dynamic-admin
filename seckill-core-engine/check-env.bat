@echo off
echo ============================================================
echo 本地开发环境健康检查
echo ============================================================
echo.

echo [1/6] 检查 MySQL...
mysql -h 127.0.0.1 -P 3306 -u root -p123456 -e "SELECT VERSION();" 2>&1 | findstr /v "Warning"
if errorlevel 1 (
    echo [FAIL] MySQL 未运行
) else (
    echo [OK] MySQL 正常
)
echo.

echo [2/6] 检查 Redis...
redis-cli -h localhost -p 30379 PING 2>&1
if errorlevel 1 (
    echo [FAIL] Redis 未运行
) else (
    echo [OK] Redis 正常
)
echo.

echo [3/6] 检查 RocketMQ...
echo 检查 NameServer...
kubectl get pods -n test | findstr rocketmq
if errorlevel 1 (
    echo [WARN] 请确认 K8s 中 RocketMQ 已运行
)
echo.

echo [4/6] 检查 Elasticsearch...
curl -s http://localhost:30920/_cluster/health 2>&1 | findstr "green"
if errorlevel 1 (
    echo [WARN] ES 可能未运行
) else (
    echo [OK] ES 正常
)
echo.

echo [5/6] 检查数据库...
mysql -h 127.0.0.1 -P 3306 -u root -p123456 -e "SHOW DATABASES LIKE 'seckill_db_%%';" 2>&1 | findstr /v "Warning"
echo.

echo [6/6] 检查表结构...
mysql -h 127.0.0.1 -P 3306 -u root -p123456 seckill_db_0 -e "SHOW TABLES;" 2>&1 | findstr /v "Warning"
echo.

echo ============================================================
echo 检查完成
echo ============================================================
pause
