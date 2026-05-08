@echo off
echo ============================================================
echo Seckill Core Engine - 本地启动脚本
echo ============================================================
echo.

REM 加载环境变量
if exist .env (
    for /f "tokens=1,* delims==" %%a in (.env) do (
        set %%a=%%b
    )
    echo [OK] 已加载 .env 文件
) else (
    echo [WARN] 未找到 .env 文件，使用默认配置
)

echo.
echo ============================================================
echo 环境检查
echo ============================================================

REM 检查 Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未找到 Java，请先安装 JDK 17+
    pause
    exit /b 1
)
echo [OK] Java 已安装

REM 检查 Maven
mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未找到 Maven，请先安装 Maven
    pause
    exit /b 1
)
echo [OK] Maven 已安装

REM 检查 MySQL
mysql -h 127.0.0.1 -P 3306 -u root -p%MYSQL_PASSWORD% -e "SELECT 1;" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] MySQL 未运行或配置错误
    pause
    exit /b 1
)
echo [OK] MySQL 已连接

echo.
echo ============================================================
echo 正在编译项目...
echo ============================================================
mvn clean compile -DskipTests

if errorlevel 1 (
    echo.
    echo [ERROR] 编译失败，请检查代码
    pause
    exit /b 1
)

echo.
echo ============================================================
echo 启动应用...
echo ============================================================
mvn spring-boot:run -Dspring-boot.run.profiles=dev

pause
