# Namespace-Aware Integration Tests

> **状态（2026-10-28 逐条核实）**
> - 本文件是这批测试的**唯一权威文档**。原先重复的英文版 `gateway-admin/TEST_SUMMARY.md`
>   已于 2026-10-28 归档为 [`docs/archive/gateway-admin-namespace-test-summary.md`](../../../docs/archive/gateway-admin-namespace-test-summary.md)，
>   不再维护，勿据其内容操作。
> - **覆盖范围**：本文只讲 7 个 namespace 隔离测试类，共 **98 个 `@Test`**（已复核，数字正确）。
>   整个 `gateway-admin` 测试目录实为 **81 个 `.java` / 80 个 `*Test.java`**，
>   `mvn test` 跑的是全部而不只是这 98 个。
> - **⚠️ 前置条件已修正**：`mvn test` **不需要** gateway-admin:9090 或 my-gateway:80 在运行
>   （走 MockMvc，进程内）；只有 `./run-tests.sh` 会去 curl 9090。详见下方"前置条件"。

## 概述

这套测试用例专门用于验证 Gateway Admin API 的 **Nacos 命名空间隔离**功能。所有涉及配置推送的操作都包含 `instanceId` 参数，这是租户隔离的关键。

## 核心设计理念

### 为什么需要 instanceId？

在网关管理平台中，**Nacos namespace 是租户隔离的核心机制**：

```
客户端请求 (带 instanceId)
    ↓
查询数据库获取实例信息
    ↓
获取实例对应的 Nacos namespace
    ↓
使用该 namespace 进行配置操作
    ↓
配置存储到正确的租户空间
```

### 测试覆盖范围

- ✅ **98 个集成测试**，覆盖所有核心 API
  （2026-10-28 复核：10+12+12+13+14+14+23 = **98**，数字正确。
  注意 `grep -c "@Test"` 会多数出来——它同时匹配类上的 `@TestMethodOrder`/`@TestInstance`，
  得用 `grep -cE '^\s*@Test\s*$'`）
- ✅ **Namespace 隔离验证**，确保多租户数据不泄露
- ✅ **配置推送验证**，确认配置写入正确的 Nacos namespace
- ✅ **生命周期管理**，测试实例的创建、启动、停止、删除

> 范围说明：这 98 个只是本 README 覆盖的 7 个 namespace 测试类。`gateway-admin/src/test` 下实为
> **81 个 `.java`（80 个 `*Test.java`）**，`mvn test` 会跑全部。

## 测试文件清单

| 测试类 | 测试数量 | 覆盖内容 |
|--------|---------|---------|
| RouteNamespaceTest | 10 | 路由 CRUD、多服务路由、命名空间隔离 |
| ServiceNamespaceTest | 12 | 服务管理、实例管理、Nacos 发现 |
| StrategyNamespaceTest | 12 | 限流、熔断、重试策略 |
| AuthPolicyNamespaceTest | 13 | JWT、API Key 认证策略、路由绑定 |
| InstanceManagementTest | 14 | 实例 CRUD、扩缩容、生命周期 |
| AuditLogTest | 14 | 审计日志、差异对比、回滚、导出 |
| MonitoringTest | 23 | 健康检查、诊断、链路追踪 |

7 个类全部 `extends NamespaceIntegrationTest`（例：`MonitoringTest.java:22`、`AuditLogTest.java:19`）。

## 快速开始

### 前置条件

> **★ 已修正。** 本节原来写"确保 gateway-admin:9090、my-gateway:80 已启动"，
> 这对 `mvn test` **不成立**。基类是 `@SpringBootTest` + `@AutoConfigureMockMvc` +
> `@ActiveProfiles("test")`（`NamespaceIntegrationTest.java:28-30`），所有请求走 **MockMvc 进程内**，
> 既不起 HTTP 服务也不连任何外部网关。
> 9090 那个检查只存在于 `run-tests.sh` 里。**两条路径的前置条件不同，下面分开写。**

#### A. 用 `mvn test` 跑（推荐；不依赖任何运行中的服务）

真实前置来自 `src/test/resources/application-test.yml`：

- **MySQL**：`127.0.0.1:3306`，库 `gateway_admin_test`（`:8`，`createDatabaseIfNotExist=true`，
  用户 `root`）；test profile 是 `ddl-auto: create-drop`。**硬依赖**——连不上时 Spring context 会在
  `entityManagerFactory` 处报 `Communications link failure` / `Connection refused`
  （本机没有 MySQL 时 gateway-admin 那 80 个 error 就是这么来的）。
- **Redis**：`127.0.0.1:${REDIS_PORT:30379}`（`:27`）。**不是可选**——ShedLock 用 Redis 锁提供者，
  缺了定时任务会失败。
- **Nacos**：`127.0.0.1:8848`，namespace `gateway-test`（`:33-41`）。
- **不需要**：运行中的 gateway-admin(9090)、my-gateway(80)。

#### B. 用 `./run-tests.sh` / `run-tests.bat` 跑

除了 A 里的 MySQL / Redis / Nacos，脚本**额外**会先探活：

```bash
# run-tests.sh:15-22
if curl -s http://localhost:9090/api/health > /dev/null 2>&1; then ... else exit 1; fi
```

即 **gateway-admin 必须已在 9090 端口运行**，否则脚本直接 `exit 1`（哪怕测试本身根本不需要它）。
脚本不检查 my-gateway:80。

### 运行所有测试

> ⚠️ 下面这条是**路径 B**（脚本），需要 gateway-admin 已在 9090 运行，否则 `exit 1`。
> 若只想跑测试本身，用**路径 A**：`cd gateway-admin && mvn test`（跑整个模块）
> 或 `mvn test -Dtest=RouteNamespaceTest,...`（只跑这 7 个类）。

```bash
# Linux/Mac
cd gateway-admin
chmod +x run-tests.sh
./run-tests.sh

# Windows
run-tests.bat
```

### 运行特定测试类

```bash
# 只运行路由测试
mvn test -Dtest=RouteNamespaceTest

# 只运行服务测试
mvn test -Dtest=ServiceNamespaceTest

# 只运行策略测试
mvn test -Dtest=StrategyNamespaceTest

# 只运行认证策略测试
mvn test -Dtest=AuthPolicyNamespaceTest

# 只运行实例管理测试
mvn test -Dtest=InstanceManagementTest

# 只运行审计日志测试
mvn test -Dtest=AuditLogTest

# 只运行监控测试
mvn test -Dtest=MonitoringTest
```

### 运行单个测试方法

```bash
mvn test -Dtest=RouteNamespaceTest#test01_CreateRoute_WithNamespace
```

## 关键测试场景

### 1. Namespace 隔离验证

每个测试类都会验证：
```java
// 创建两个不同实例
String instanceA = createTestInstance("tenant-a");
String instanceB = createTestInstance("tenant-b");

// 在 instanceA 中创建资源
createRoute(instanceA, "route-for-a");

// 验证 instanceB 看不到 instanceA 的资源
assertFalse(canSeeRoute(instanceB, "route-for-a"));
```

### 2. 配置推送到正确 Namespace

```java
// 创建带 instanceId 的路由
mockMvc.perform(post("/api/routes")
    .param("instanceId", testInstanceId)  // 关键参数
    .contentType(MediaType.APPLICATION_JSON)
    .content(routeJson))
```

系统会自动：
1. 根据 instanceId 查询数据库
2. 获取对应的 Nacos namespace
3. 将配置推送到该 namespace

### 3. 多租户场景模拟

```java
// 创建多个租户实例
String[] tenants = {"tenant-a", "tenant-b", "tenant-c"};
for (String tenant : tenants) {
    createInstance(tenant, "ns-" + tenant);
}

// 验证每个租户有独立的配置空间
```

## 测试数据结构

### 测试实例创建

```java
ObjectNode request = objectMapper.createObjectNode();
request.put("instanceName", "test-instance");
request.put("specType", "small");
request.put("replicas", 1);
request.put("namespace", TEST_NAMESPACE);  // 专用 namespace
```

### API 调用带 instanceId

```java
// GET 请求
mockMvc.perform(get("/api/routes")
    .param("instanceId", testInstanceId))

// POST 请求
mockMvc.perform(post("/api/routes")
    .param("instanceId", testInstanceId)
    .contentType(MediaType.APPLICATION_JSON)
    .content(routeJson))

// PUT 请求
mockMvc.perform(put("/api/routes/{id}", routeId)
    .param("instanceId", testInstanceId)
    .contentType(MediaType.APPLICATION_JSON)
    .content(updatedRouteJson))
```

## 测试清理机制

每个测试类都有完整的清理流程：

```java
@BeforeAll
static void setup() {
    // 创建专用测试实例和 namespace
    testInstanceId = createTestInstance("test-xxx");
}

@BeforeEach
void setUp() {
    // 清理上一个测试的数据
    cleanAllData();
}

@AfterAll
static void cleanup() {
    // 删除测试实例
    deleteTestInstance(testInstanceId);
}
```

## 常见问题

### Q: 为什么有些测试失败了？

A: 检查以下几点（**按实际失败率排序**）：
1. **MySQL 是否可达**：`127.0.0.1:3306/gateway_admin_test`（`application-test.yml:8`）。
   连不上会表现为 `entityManagerFactory` 创建失败 → `Communications link failure` →
   `Connection refused`，随后同一个 context 的其余测试报
   `ApplicationContext failure threshold exceeded`（级联，不是独立缺陷）。
2. **Redis 是否可达**：`127.0.0.1:${REDIS_PORT:30379}`（`application-test.yml:27`）。
   ShedLock 用 Redis 锁提供者，缺了会 `RedisConnectionFailureException`。
3. **Nacos 是否可访问**：`127.0.0.1:8848`，namespace `gateway-test`。
4. 是否有足够的权限创建/删除资源。

> ❗ **不要**去检查"gateway-admin 是否在 9090 端口运行"——`mvn test` 走 MockMvc，根本不连它。
> 只有 `./run-tests.sh` 会 curl 9090 并在失败时 `exit 1`。

### Q: 测试会污染生产数据吗？

A: 不会。测试使用：
- 专用的测试 namespace（随机生成）
- 专用的测试实例
- 自动清理机制
- 独立的测试库 `gateway_admin_test`（`ddl-auto: create-drop`），不是生产库 `gateway_admin`

### Q: 如何查看详细的测试输出？

A: 运行测试时添加 `-X` 参数：
```bash
mvn test -Dtest=RouteNamespaceTest -X
```

### Q: 测试运行需要多长时间？

A: 全部 98 个测试大约需要 2-5 分钟，具体取决于：
- 网络速度（Nacos 访问）
- 数据库性能
- 系统负载

## 扩展测试

如需添加新的测试，请遵循以下规范：

1. **继承 NamespaceIntegrationTest**
   ```java
   public class MyNewTest extends NamespaceIntegrationTest {
       // ...
   }
   ```

2. **使用 @BeforeAll 创建实例**
   ```java
   @BeforeAll
   static void setup() throws Exception {
       testInstanceId = createTestInstance("my-test");
   }
   ```

3. **所有 API 调用带上 instanceId**
   ```java
   .param("instanceId", testInstanceId)
   ```

4. **添加清晰的测试描述**
   ```java
   @DisplayName("测试描述 - 应该验证什么行为")
   ```

## 参考资料

- [docs/archive/gateway-admin-namespace-test-summary.md](../../../docs/archive/gateway-admin-namespace-test-summary.md)
  - 原 `gateway-admin/TEST_SUMMARY.md`，同一批测试的英文版总结，内容与本文重复，**已归档、以本文为准**
  （历史坑：它原来被写成 `../TEST_SUMMARY.md`，从 `src/test/` 出发会指向不存在的 `src/TEST_SUMMARY.md`）
- [NamespaceIntegrationTest.java](./java/com/leoli/gateway/admin/NamespaceIntegrationTest.java) - 基础测试类
  （原链接文字误写为 `BaseIntegrationTest.java`；同包下确实另有一个 `BaseIntegrationTest.java`，
  但这 7 个测试类继承的是 `NamespaceIntegrationTest`）
- 各测试类的 JavaDoc 注释

## 贡献

欢迎提交新的测试用例或改进现有测试。请确保：
- 新测试包含 namespace 隔离验证
- 所有 API 调用都使用 instanceId
- 测试后有适当的清理
- 添加清晰的 @DisplayName 描述
