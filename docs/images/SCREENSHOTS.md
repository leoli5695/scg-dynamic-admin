# Feature Screenshots Guide

> Complete visual documentation of all API Gateway features - 66 annotated screenshots covering the entire platform.

---

## 1. Login (Screenshot 01)

**01.png** - 系统登录界面，支持用户名/密码认证，默认管理员账号：admin/admin123。

---

## 2. Kubernetes Cluster Management (Screenshots 02-05)

**02-05.png** - 集群管理全流程：

- **02.png**: Kubernetes集群管理主界面，展示已注册的集群列表
- **03.png**: 添加集群对话框，填写集群名称、API Server地址、认证Token等信息
- **04.png**: 集群连接测试，验证与Kubernetes API的连通性
- **05.png**: 集群详情页，展示连接状态、可用Namespace、资源配额等信息

---

## 3. Gateway Instance Management (Screenshots 06-09)

**06-09.png** - 网关实例的创建、部署和运行管理：

- **06.png**: 实例列表，展示已部署的网关实例
- **07.png**: 创建网关实例表单，配置实例名称、集群选择、规格、Namespace隔离、副本数等
- **08.png**: 实例概览，展示实例从Starting到Running的状态变化，包含心跳状态、Pod副本数、运行时长等
- **09.png**: 创建实例成功后，访问网关返回404错误，因为尚未配置任何路由

---

## 4. Nacos Configuration (Screenshots 10-11)

**10-11.png** - Nacos配置中心和注册中心界面：

- **10.png**: Nacos配置列表，展示配置中心中的配置项
- **11.png**: 服务详情，展示demo-service的实例列表，包括实例地址、权重、健康状态等

---

## 5. Service Management (Screenshot 12)

**12.png** - 服务管理界面，管理后端微服务。展示service-02和service-01等后端服务，包含健康实例列表、服务地址、权重配置等信息。

---

## 6. AI Copilot - Route Creation (Screenshots 13-16)

**13-16.png** - AI助手通过自然语言创建路由的完整流程：

- **13.png**: AI Copilot工具界面，展示可用的35+个AI工具
- **14.png**: 用户通过自然语言描述需求："创建一个路由，将/api/service01的请求转发到service-01"
- **15.png**: AI助手调用工具，自动生成路由配置并提交
- **16.png**: 通过AI助手创建的路由调用成功，Postman测试返回200 OK

---

## 7. Multi-Service Routing / Gray Release (Screenshot 17)

**17.png** - 多服务路由配置（灰度发布）。展示基于Header的流量分流策略：Header `aaa=111` 路由到service-01，Header `aaa=222` 路由到demo-service，实现精确的流量控制。

---

## 8. Strategy Configuration (Screenshots 18-20)

**18-20.png** - 支持的策略类型总览：

- **18.png**: 策略类型选择对话框，展示所有支持的策略分类
- **19.png**: 各类策略详细列表，包括IP黑名单/白名单、限流、熔断、超时、重试、降级等数十种保护策略
- **20.png**: 鉴权配置界面，创建Basic Auth鉴权策略并绑定到具体路由（service-01-api）

---

## 9. SSL Certificate Management (Screenshots 21-22)

**21-22.png** - SSL证书管理：

- **21.png**: 证书列表，展示已上传的SSL证书（如baidu-cert用于www.baidu.com），显示证书格式（PEM）、有效期、状态（Valid）等
- **22.png**: 证书详情，展示证书完整信息和绑定状态

---

## 10. Request Tracing (Screenshot 23)

**23.png** - 请求链路追踪，集成Jaeger分布式追踪系统。展示完整的请求链路详情，包括Trace ID、Span ID、各服务调用链、耗时分布等。

---

## 11. Real-time Monitoring (Screenshots 24-31)

**24-31.png** - 全面的实时监控仪表板：

- **24.png**: 监控概览，展示JVM内存、GC、线程等核心指标
- **25.png**: CPU使用率监控
- **26.png**: HTTP请求指标（QPS、响应时间、错误率）
- **27.png**: 历史趋势图，展示指标随时间的变化
- **28.png**: Pod选择器，支持选择特定Pod查看其指标
- **29.png**: 路由表状态监控
- **30-31.png**: 更多监控维度和图表展示

---

## 12. Alert Configuration (Screenshot 32)

**32.png** - 告警配置界面，支持设置邮件告警。可配置告警阈值（CPU、内存、响应时间等）、接收人邮箱、告警频率等。

---

## 13. Access Logs (Screenshots 33-35)

**33-35.png** - 访问日志管理：

- **33.png**: 访问日志配置界面，支持Kubernetes PVC存储模式
- **34.png**: 日志存储配置选项
- **35.png**: 日志查看器，实时查看网关访问日志

---

## 14. Audit Logs (Screenshots 36-37)

**36-37.png** - 审计日志，记录所有配置变更历史：

- **36.png**: 审计日志列表，展示谁在什么时候做了什么修改
- **37.png**: 变更详情，支持对比变更前后的配置差异，并提供一键回滚功能

---

## 15. System Diagnostic (Screenshots 38-39)

**38-39.png** - 系统诊断功能：

- **38.png**: 健康检查，检查Nacos连接、Redis可用性、数据库健康、JVM状态等
- **39.png**: 诊断报告，展示系统健康评分（如87分）、各组件检查状态、优化建议等

---

## 16. Traffic Topology (Screenshot 40)

**40.png** - 流量拓扑图，实时可视化展示请求流量路径：gateway-01 → route-01 → service-01/demo-service，直观呈现流量分布和服务健康状态。

---

## 17. Filter Chain Analysis (Screenshot 41)

**41.png** - 过滤器链性能分析，展示每个过滤器的自身耗时、P99延迟、性能瓶颈识别和优化建议。帮助定位性能问题的根源。

---

## 18. Stress Testing (Screenshots 42-49)

**42-49.png** - 完整的压测流程，包括压测期间的监控变化：

- **42.png**: 压测快速启动模板，预设多种测试场景
- **43.png**: 压测配置界面，配置目标路由、并发数、持续时间、请求负载等
- **44.png**: 压测执行中，实时QPS图表
- **45.png**: 压测期间响应时间变化
- **46.png**: 压测期间错误率监控
- **47.png**: 压测期间网关Pod的CPU/内存资源使用情况（演示K8s自动扩缩容潜力）
- **48.png**: 压测完成报告，包含总请求数、成功率、响应时间统计、吞吐量分析
- **49.png**: 报告导出选项，支持PDF、Excel、JSON、Markdown等格式

---

## 19. AI Copilot - Error Debugging (Screenshots 50-54)

**50-54.png** - AI助手协助调试404错误的精彩场景：

用户声称请求返回404，要求AI助手帮忙排查。但AI助手通过工具查询后发现：路由实际上是可以匹配上的！AI助手推翻了用户的404结论，展示了AI Copilot的自主分析能力和工具调用能力。

- **50.png**: 用户向AI助手提出404问题
- **51.png**: AI助手调用路由查询工具
- **52.png**: AI助手分析路由谓词，发现请求应该能匹配
- **53.png**: AI助手得出结论：请求不应该返回404，可能存在其他问题
- **54.png**: AI助手给出正确诊断，推翻用户最初的404假设

---

## 20. AI Copilot - Stress Test Analysis (Screenshots 55-63)

**55-63.png** - AI助手基于真实数据（数据库 + Prometheus）分析最近两分钟内的压测结果，并给出专业的分析与优化建议：

- **55.png**: 性能分析报告，包含P99响应时间、错误率、过滤器链瓶颈分析
- **56.png**: GC压力分析，展示压测前后的Young GC、Full GC次数变化
- **57.png**: 根因分析与CPU资源监控，识别高请求率导致的资源饱和
- **58.png**: 监控数据趋势，展示请求速率、响应时间、内存使用的变化
- **59.png**: 根因定位 - 下游服务延迟（2500+ms瓶颈）
- **60.png**: 优化建议，给出具体可执行的优化方案
- **61.png**: JVM调优建议，推荐G1GC、堆大小等参数配置
- **62.png**: 性能对比表，当前性能 vs 目标性能 vs 差距
- **63.png**: 结论总结，明确指出网关性能良好，问题在于下游服务响应慢

---

## 21. AI Copilot - Route Disabled Analysis (Screenshots 64-66)

**64-66.png** - 用户禁用了所有路由后，再次询问为什么访问404。AI助手给出了正确的回答：

由于所有路由均被禁用了（enabled=false），导致请求匹配不到任何路由，所以返回404。AI助手通过工具查询路由配置，详细分析了路由状态、谓词、过滤器、后端服务等信息，准确定位了404的根本原因。

- **64.png**: 路由管理界面，显示所有路由均被禁用（Disabled状态）
- **65.png**: AI 404错误分析报告，验证路由未匹配，路由已禁用
- **66.png**: AI详细路由配置分析，展示路由ID、谓词、过滤器、后端服务健康状态等完整信息

---

## Quick Reference

| 功能模块 | 截图编号 | 截图数量 |
|---------|---------|---------|
| 登录 | 01 | 1 |
| Kubernetes集群管理 | 02-05 | 4 |
| 网关实例管理 | 06-09 | 4 |
| Nacos配置中心 | 10-11 | 2 |
| 服务管理 | 12 | 1 |
| AI助手创建路由 | 13-16 | 4 |
| 多服务路由（灰度） | 17 | 1 |
| 策略配置 | 18-20 | 3 |
| SSL证书管理 | 21-22 | 2 |
| 请求链路追踪 | 23 | 1 |
| 实时监控 | 24-31 | 8 |
| 告警配置 | 32 | 1 |
| 访问日志 | 33-35 | 3 |
| 审计日志 | 36-37 | 2 |
| 系统诊断 | 38-39 | 2 |
| 流量拓扑 | 40 | 1 |
| 过滤器链分析 | 41 | 1 |
| 压力测试 | 42-49 | 8 |
| AI助手调试404 | 50-54 | 5 |
| AI助手压测分析 | 55-63 | 9 |
| AI助手路由禁用分析 | 64-66 | 3 |
| **总计** | **01-66** | **66** |

---

## Usage in Documentation

在功能文档中引用这些截图：

```markdown
![限流配置](images/13.png)
```

对于多截图的功能：

```markdown
### 压测配置

| 步骤 | 截图 |
|------|------|
| 配置测试 | [42.png](images/42.png) |
| 运行测试 | [43.png](images/43.png) |
| 查看结果 | [48.png](images/48.png) |
```
