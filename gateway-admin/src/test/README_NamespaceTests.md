# Namespace-Aware Integration Tests

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
- ✅ **Namespace 隔离验证**，确保多租户数据不泄露
- ✅ **配置推送验证**，确认配置写入正确的 Nacos namespace
- ✅ **生命周期管理**，测试实例的创建、启动、停止、删除

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

## 快速开始

### 前置条件

确保以下服务已启动：
- **gateway-admin**: 运行在端口 9090
- **my-gateway**: 运行在端口 80
- **MySQL/H2**: 数据库已配置
- **Nacos**: 配置中心可用

### 运行所有测试

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

A: 检查以下几点：
1. gateway-admin 是否在 9090 端口运行
2. 数据库连接是否正常
3. Nacos 是否可访问
4. 是否有足够的权限创建/删除资源

### Q: 测试会污染生产数据吗？

A: 不会。测试使用：
- 专用的测试 namespace（随机生成）
- 专用的测试实例
- 自动清理机制

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

- [TEST_SUMMARY.md](../TEST_SUMMARY.md) - 详细测试总结
- [BaseIntegrationTest.java](./com/leoli/gateway/admin/NamespaceIntegrationTest.java) - 基础测试类
- 各测试类的 JavaDoc 注释

## 贡献

欢迎提交新的测试用例或改进现有测试。请确保：
- 新测试包含 namespace 隔离验证
- 所有 API 调用都使用 instanceId
- 测试后有适当的清理
- 添加清晰的 @DisplayName 描述
