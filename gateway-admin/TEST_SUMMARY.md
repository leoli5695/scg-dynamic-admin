# Gateway Admin API Integration Tests with Namespace Isolation

> **Scope & status (verified 2026-10-28)**
> - **Coverage**: despite the generic file name, this document covers **only the 7 namespace-isolation
>   test classes** (plus their shared base class) — **98 `@Test` methods** in total. It is *not* a
>   summary of the whole `gateway-admin` test suite: `git ls-files 'src/test/**/*.java'` returns **81**
>   files (**80** of them named `*Test.java`), and a full `mvn test` run of this module executes far more
>   than 98 tests. A more accurate title would be *"Namespace Integration Test Summary"*.
> - **Duplicate**: this file overlaps heavily with
>   [`src/test/README_NamespaceTests.md`](./src/test/README_NamespaceTests.md).
>   **Treat `src/test/README_NamespaceTests.md` as the authoritative version** (it is the Chinese
>   counterpart, lives next to the tests, and carries the corrected prerequisite split between
>   `./run-tests.sh` and `mvn test`).
> - The 98-test breakdown below was re-counted and is **correct** (10/12/12/13/14/14/23).
>   The "Test Environment Requirements" section below *was* wrong and has been corrected.

## Overview

This test suite provides comprehensive integration tests for the Gateway Admin API with **Nacos namespace isolation** to ensure proper tenant separation. All configuration push operations include the `instanceId` parameter, which is critical for multi-tenancy as it determines the Nacos namespace used for configuration storage.

## Test Files Created

### 1. **NamespaceIntegrationTest.java** - Base Test Class
- Provides common utilities for namespace-aware testing
- Automatically creates test instances with dedicated namespaces
- Includes cleanup methods to prevent test data pollution
- Helper methods for extracting response data
- It is an `abstract` class annotated `@SpringBootTest` + `@AutoConfigureMockMvc` +
  `@ActiveProfiles("test")` + `@TestMethodOrder(OrderAnnotation)` +
  `@WithMockUser(username = "test-admin", authorities = {"ADMIN"})` (`NamespaceIntegrationTest.java:28-34`).
  The `ADMIN` authority is required because `SecurityConfig` enforces `hasAuthority("ADMIN")` on
  mutating `/api/**` calls.
- Note: a separate, older `BaseIntegrationTest.java` also exists in the same package; all 7 classes
  below extend **`NamespaceIntegrationTest`**, not `BaseIntegrationTest`.

### 2. **RouteNamespaceTest.java** - Route API Tests (10 tests)
- ✅ Create route with instanceId - isolates to namespace
- ✅ Get routes filtered by instanceId
- ✅ Get route by ID
- ✅ Disable/Enable route
- ✅ Update route
- ✅ Create multi-service route with weight-based routing
- ✅ Delete route
- ✅ Create route without instanceId (default instance)
- ✅ **Verify namespace isolation** - routes from different instances are separate

### 3. **ServiceNamespaceTest.java** - Service API Tests (12 tests)
- ✅ Create service with instanceId - isolates to namespace
- ✅ Get services filtered by instanceId
- ✅ Get service by name
- ✅ Update service
- ✅ Add/Remove instance to service
- ✅ Update instance status (enable/disable)
- ✅ Check service usage
- ✅ Delete service
- ✅ Create service without instances
- ✅ **Verify namespace isolation** - services from different instances are separate
- ✅ Get Nacos discovery services

### 4. **StrategyNamespaceTest.java** - Strategy API Tests (12 tests)
- ✅ Create rate limit strategy with namespace isolation
- ✅ Get strategies filtered by instanceId
- ✅ Get strategy by ID
- ✅ Get strategies by type (RATE_LIMIT, CIRCUIT_BREAKER, RETRY)
- ✅ Get global strategies
- ✅ Disable/Enable strategy
- ✅ Update strategy configuration
- ✅ Create circuit breaker strategy
- ✅ Create retry strategy
- ✅ Delete strategy
- ✅ **Verify namespace isolation** - strategies from different instances are separate

### 5. **AuthPolicyNamespaceTest.java** - Auth Policy API Tests (13 tests)
- ✅ Create JWT auth policy with namespace isolation
- ✅ Get auth policies filtered by instanceId
- ✅ Get auth policy by ID
- ✅ Get auth policies by type (JWT, API_KEY)
- ✅ Disable/Enable auth policy
- ✅ Update auth policy
- ✅ Get usage example
- ✅ Create API Key auth policy
- ✅ Bind auth policy to route
- ✅ Get bindings for policy
- ✅ Get binding count
- ✅ Delete auth policy

### 6. **InstanceManagementTest.java** - Instance Management Tests (14 tests)
- ✅ Create gateway instance with dedicated namespace
- ✅ Get all instances
- ✅ Get instance by ID and UUID
- ✅ Get available specs
- ✅ Update instance replicas (scaling)
- ✅ Update instance spec (resource allocation)
- ✅ Update instance image
- ✅ Refresh instance status
- ✅ Get instance pods
- ✅ Start/Stop instance
- ✅ Delete instance
- ✅ **Create multiple instances** - verify multi-tenancy support

### 7. **AuditLogTest.java** - Audit Log Tests (14 tests)
- ✅ Get audit logs with pagination
- ✅ Filter by target type (ROUTE, SERVICE, etc.)
- ✅ Filter by operation type (CREATE, UPDATE, DELETE)
- ✅ Filter by time range
- ✅ Get audit log by ID
- ✅ Get audit log diff (before/after comparison)
- ✅ Get audit log statistics
- ✅ Get target types and operation types
- ✅ Get timeline of events
- ✅ Export as CSV
- ✅ Export as JSON
- ✅ Get cleanup stats
- ✅ Trigger cleanup of old logs

### 8. **MonitoringTest.java** - Monitoring & Health Tests (23 tests)
#### Health Checks
- ✅ Comprehensive health check
- ✅ Liveness probe
- ✅ Readiness probe
- ✅ Component health details

#### Diagnostics
- ✅ Full diagnostic
- ✅ Quick diagnostic
- ✅ Database diagnostic
- ✅ Redis diagnostic
- ✅ Config center (Nacos/Consul) diagnostic
- ✅ Routes diagnostic
- ✅ Auth diagnostic
- ✅ Performance diagnostic
- ✅ Health score
- ✅ Diagnostic history
- ✅ Score trend

#### Request Tracing
- ✅ Get trace statistics
- ✅ Get traces with pagination
- ✅ Get recent errors
- ✅ Get slow traces
- ✅ Delete old traces

#### Multi-Instance Comparison
- ✅ Compare all instances
- ✅ Get instance ranking
- ✅ Get performance outliers

## Total Test Count: **98 Integration Tests**

> Re-verified 2026-10-28: the 7 classes contain exactly **98** `@Test` methods
> (10 + 12 + 12 + 13 + 14 + 14 + 23), so this total and the per-class numbers above are **correct**.
> Counting caveat: `grep -c "@Test"` over-reports (it also matches the class-level `@TestMethodOrder`
> and `@TestInstance` annotations, yielding 111 across these files). Use `grep -cE '^\s*@Test\s*$'`.

## Key Features Tested

### Namespace Isolation
All tests verify that:
1. Each instance has its own Nacos namespace
2. Configuration is stored in the correct namespace
3. Different tenants cannot see each other's data
4. instanceId parameter properly routes requests to the correct namespace

### Configuration Push with Namespace
Every create/update/delete operation includes:
- `instanceId` query parameter for namespace resolution
- Verification that config is pushed to the correct Nacos namespace
- Cleanup to prevent test data pollution

### Multi-Tenancy Support
Tests verify:
- Multiple instances can coexist
- Each instance has isolated configuration
- Cross-tenant data leakage is prevented

## Running the Tests

### Run All Tests
```bash
cd gateway-admin
mvn test
```
> Note: a bare `mvn test` runs the **entire** `gateway-admin` module (80 `*Test.java` classes), not just
> the 98 namespace tests documented here. To run only this suite, use the per-class `-Dtest=` forms below
> or `./run-tests.sh`.

### Run Specific Test Class
```bash
# Route tests
mvn test -Dtest=RouteNamespaceTest

# Service tests
mvn test -Dtest=ServiceNamespaceTest

# Strategy tests
mvn test -Dtest=StrategyNamespaceTest

# Auth policy tests
mvn test -Dtest=AuthPolicyNamespaceTest

# Instance management tests
mvn test -Dtest=InstanceManagementTest

# Audit log tests
mvn test -Dtest=AuditLogTest

# Monitoring tests
mvn test -Dtest=MonitoringTest
```

### Run Single Test Method
```bash
mvn test -Dtest=RouteNamespaceTest#test01_CreateRoute_WithNamespace
```

## Test Environment Requirements

> **★ Corrected.** This section previously claimed that a running **Gateway Admin on port 9090** and a
> running **My-Gateway on port 80** were prerequisites. **That is false for `mvn test`.**
> The base class is `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`
> (`NamespaceIntegrationTest.java:28-30`), so every request goes through **MockMvc in-process** — no HTTP
> server is started and no external gateway is contacted. The only place the port-9090 check exists is the
> `run-tests.sh` wrapper, which `curl`s `http://localhost:9090/api/health` and `exit 1`s if it fails
> (`run-tests.sh:15-22`); that is a quirk of the script, not a requirement of the tests.

**Actually required** (from `src/test/resources/application-test.yml`):

- **MySQL**: reachable at `127.0.0.1:3306`, database `gateway_admin_test`
  (`application-test.yml:8`; `createDatabaseIfNotExist=true`, user `root`). JPA is
  `ddl-auto: create-drop` for the test profile. **This is a hard requirement** — without it the Spring
  context fails at `entityManagerFactory` with `Communications link failure` / `Connection refused`,
  which is exactly the cause of the 80 errors seen when `gateway-admin` tests run on a machine with no
  local MySQL.
- **Redis**: `127.0.0.1`, port `${REDIS_PORT:30379}` (`application-test.yml:27`). **Not optional** —
  ShedLock uses the Redis lock provider, so scheduled tasks fail without it. (The yml carries a comment
  explaining why the port mirrors the main config instead of the usual 6379.)
- **Nacos**: `127.0.0.1:8848`, namespace `gateway-test` (`application-test.yml:33-41`) for
  discovery/config operations.

**Not required**: a running `gateway-admin` on port 9090, or a running `my-gateway` on port 80
(only `run-tests.sh` probes 9090).

## Important Notes

### Namespace Parameter Usage
The `instanceId` parameter is passed as a **query parameter** in most API calls:
```java
.param("instanceId", testInstanceId)
```

The system resolves the Nacos namespace from the database using the instanceId:
```
Request → Lookup instance in DB → Get nacosNamespace → Use namespace for Nacos operations
```

### Test Data Cleanup
Each test class:
1. Creates a dedicated test instance with unique namespace in `@BeforeAll`
2. Cleans up all test data in `@BeforeEach`
3. Deletes the test instance in `@AfterAll`

This ensures:
- No test data pollution between runs
- Proper namespace isolation verification
- Clean test environment for each test class

### Assertions
All tests verify:
- HTTP status codes (200 OK, 404 Not Found, etc.)
- Response structure (JSON fields present)
- Business logic correctness (values match expectations)
- Namespace isolation (cross-tenant data not visible)

## Coverage Summary

| Module | Endpoints Covered | Test Count |
|--------|------------------|------------|
| Routes | CRUD, Enable/Disable, Multi-service | 10 |
| Services | CRUD, Instances, Usage | 12 |
| Strategies | CRUD, Rate Limit, Circuit Breaker, Retry | 12 |
| Auth Policies | CRUD, Bindings, JWT, API Key | 13 |
| Instances | CRUD, Lifecycle, Scaling | 14 |
| Audit Logs | Query, Filter, Export, Cleanup | 14 |
| Monitoring | Health, Diagnostics, Tracing | 23 |
| **Total** | **98 endpoints tested** | **98** |

## Future Enhancements

Potential additions:
- AI Copilot API tests
- Filter chain analysis tests
- Stress test controller tests
- SSL certificate management tests
- Kubernetes deployment tests
- Email notification tests
- Alert configuration tests
- Request replay tests
- Traffic topology tests
