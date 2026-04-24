@echo off
REM Gateway Admin Integration Test Runner for Windows
REM Runs all namespace-aware integration tests with proper setup

echo ==========================================
echo Gateway Admin Integration Test Suite
echo Namespace Isolation Tests
echo ==========================================
echo.

REM Check if gateway-admin is running
echo Checking if gateway-admin is running on port 9090...
curl -s http://localhost:9090/api/health >nul 2>&1
if errorlevel 1 (
    echo X Gateway Admin is NOT running on port 9090
    echo Please start gateway-admin before running tests
    pause
    exit /b 1
)
echo [OK] Gateway Admin is running
echo.

echo Running tests...
echo.

cd /d "%~dp0"

REM Run all tests
echo ----------------------------------------
echo Running All Integration Tests
echo ----------------------------------------
call mvn test -Dtest=RouteNamespaceTest,ServiceNamespaceTest,StrategyNamespaceTest,AuthPolicyNamespaceTest,InstanceManagementTest,AuditLogTest,MonitoringTest

echo.
echo ==========================================
echo All Tests Completed!
echo ==========================================
echo.
echo Test Summary Report:
echo - Route Tests: 10 tests
echo - Service Tests: 12 tests
echo - Strategy Tests: 12 tests
echo - Auth Policy Tests: 13 tests
echo - Instance Management Tests: 14 tests
echo - Audit Log Tests: 14 tests
echo - Monitoring Tests: 23 tests
echo - Total: 98 integration tests
echo.
echo See TEST_SUMMARY.md for detailed information
echo.
pause
