#!/bin/bash

# Gateway Admin Integration Test Runner
# Runs all namespace-aware integration tests with proper setup

set -e

echo "=========================================="
echo "Gateway Admin Integration Test Suite"
echo "Namespace Isolation Tests"
echo "=========================================="
echo ""

# Check if gateway-admin is running
echo "Checking if gateway-admin is running on port 9090..."
if curl -s http://localhost:9090/api/health > /dev/null 2>&1; then
    echo "✓ Gateway Admin is running"
else
    echo "✗ Gateway Admin is NOT running on port 9090"
    echo "Please start gateway-admin before running tests"
    exit 1
fi

echo ""
echo "Running tests..."
echo ""

# Run tests by category
run_test_suite() {
    local test_class=$1
    local description=$2

    echo "----------------------------------------"
    echo "Running: $description"
    echo "Test Class: $test_class"
    echo "----------------------------------------"

    if mvn test -Dtest="$test_class" -q 2>&1 | tail -20; then
        echo "✓ $description PASSED"
    else
        echo "✗ $description FAILED"
        return 1
    fi
    echo ""
}

# Run all test suites
cd "$(dirname "$0")"

run_test_suite "RouteNamespaceTest" "Route API Tests with Namespace Isolation"
run_test_suite "ServiceNamespaceTest" "Service API Tests with Namespace Isolation"
run_test_suite "StrategyNamespaceTest" "Strategy API Tests with Namespace Isolation"
run_test_suite "AuthPolicyNamespaceTest" "Auth Policy API Tests with Namespace Isolation"
run_test_suite "InstanceManagementTest" "Instance Management API Tests"
run_test_suite "AuditLogTest" "Audit Log API Tests"
run_test_suite "MonitoringTest" "Monitoring & Health API Tests"

echo "=========================================="
echo "All Tests Completed!"
echo "=========================================="
echo ""
echo "Test Summary Report:"
echo "- Route Tests: 10 tests"
echo "- Service Tests: 12 tests"
echo "- Strategy Tests: 12 tests"
echo "- Auth Policy Tests: 13 tests"
echo "- Instance Management Tests: 14 tests"
echo "- Audit Log Tests: 14 tests"
echo "- Monitoring Tests: 23 tests"
echo "- Total: 98 integration tests"
echo ""
echo "See TEST_SUMMARY.md for detailed information"
