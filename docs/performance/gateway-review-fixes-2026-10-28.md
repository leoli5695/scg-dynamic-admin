# 网关性能审查修复（2026-10-28，第二轮）

## 实现

1. 通用路由重试默认只缓存已知长度、最多 64 KiB 的 POST/PUT/PATCH 正文；实例内预算为 16 MiB，按两份缓存开销预留。未知长度、超过阈值或预算不足时，本层只执行一次并透传正文。完成、异常、取消均释放额度。实例切换重试保持独立预算。
2. WAF 新增 64 MiB 并发预算，按实际读取正文的四倍计入，覆盖聚合、字节数组和解码副本的估算开销；独立 2 线程、8 个排队任务。内存或任务准入失败返回 503，单包超限仍返回 413，安全检查不会降级放行。
3. 请求/响应转换共享一个 128 MiB 预算和一个 2 线程、8 个排队任务的执行器。按实际读取正文的八倍计入，正文解析、映射、脱敏与序列化在独立工作线程执行。并发预算或任务额度不足返回 503；这是过载保护，而不是承诺任意并发均成功。倍数是资源估算，不代表任意自定义转换规则的精确堆占用。
4. AI 语义缓存的字符串、向量与条目估算开销受实例级 32 MiB 和 1000 条双重限制。查询锁拆为 16 个 scope 哈希分片，淘汰在当前分片内进行；其他分片占满额度且当前分片无可淘汰条目时跳过写入。单个热点 scope 可使用全部预算，并未将其容量缩减为 1/16。后台写入使用独立有界执行器和 8 MiB 排队/执行预算。
5. 过滤器详情默认按请求采样 1%，同一请求的所有包装过滤器共享一次采样决定；每请求的熔断检查、结果反馈仍保留。去掉统计更新中的每过滤器 `ConcurrentHashMap.compute` 串行段。慢请求阈值改为初始化时设置，避免逐请求重复写配置和日志。现有网关请求指标仍可观察全量流量，过滤器详情统计代表采样流量。
6. Token 用量上报采用 1024 条/1 MiB 队列，单条最多 16 KiB，每 50 ms 最多取 32 条，发送中的原始记录也计入预算。独立单连接池，整次发送 2 秒截止；失败不重试、计入 `token.usage.report.failures`。管理端增加内部批量接口，用 JDBC batch 和事务落库，显式填充时间字段，避免 IDENTITY 主键使 JPA saveAll 逐行插入。
7. 多维限流在 Redis 单机/Sentinel 模式合并为一次 Lua 调用；沿用原来的维度 key 和窗口算法，FIRST_HIT 在脚本内停止后续扣减，ALL_HIT 保持逐维计数。Redis Cluster 保留原路径，避免跨 slot 多 key 脚本。批量调用失败时直接走本地检查，不再重复发 Redis 扣减。

取消 CPU 任务的请求不会立即返还仍被任务使用的内存额度：工作任务持有额外引用，直到真正结束才释放。排队和执行都受额度约束。

## 配置

| 配置 | 默认值 |
| --- | --- |
| `gateway.retry.max-cached-body-bytes` | 65536 |
| `gateway.retry.max-inflight-body-bytes` | 16777216 |
| `gateway.security.max-inflight-body-bytes` | 67108864 |
| `gateway.transform.max-inflight-body-bytes` | 134217728 |
| `gateway.ai.cache.max-bytes` | 33554432 |
| `gateway.monitor.filter-tracking.sampling-rate` | 0.01（0～1） |
| `gateway.rate-limiter.batch-enabled` | true |

重试、WAF、转换、缓存分别管理预算；这些是局部资源上限，不是整个 JVM 的统一内存上限。部署时须结合堆、直接内存和实际正文大小调整。

上线先更新管理端，再更新网关：新网关使用 `/api/token-quota/internal/record-usage/batch`，旧管理端没有此接口。旧单条接口继续保留；内部接口仍受共享 Token 保护。覆盖 MySQL URL 时继续保留 `rewriteBatchedStatements=true`。

Token 用量上报仍为尽力而为。过载或管理端故障可能丢失用量明细，需用失败指标对账；批量发送不改变 Redis 配额扣减逻辑，也不提供持久化消息投递保证。

## 验证边界

使用 Java 21，在 `.local-runtime/perf-review-validation` 独立源码快照运行 Maven，避免共享工作目录的并行构建改写 class 文件。

新增回归覆盖实际正文计费、WAF 过载拒绝与取消释放、重试未知长度透传与取消释放、CPU 任务取消后继续持有预算、队列饱和、转换线程与完整正文、语义缓存字节淘汰、采样与熔断反馈、批量上报快照及队列上限、批量 JDBC 字段和整批回滚。Lua 合约测试用 LuaJ 执行真实生成的脚本，以内存 Redis 命令模型验证 FIRST_HIT、ALL_HIT、窗口过期和序列 key；不代替真实 Redis Cluster/故障链路压测。

未连接共享业务 MySQL 执行集成测试；未部署、未重启业务服务。本轮不以源码优化推算吞吐提升，生产收益需要隔离压力机和真实上游 A/B 压测。
## 最终结果

- 网关：795 项测试通过，失败/错误为 0。
- 管理端：390 项不依赖外部数据库的测试通过，失败/错误为 0；依赖共享 MySQL 的集成类未执行。
- license-client：83 项通过，失败/错误为 0。
- 专项覆盖包含新增回归；其结果包含在上述总数中，不重复累加。
- 本次涉及的 22 个生产源码/配置/构建文件与验证快照逐字节一致。
- 网关和管理端安装包在独立验证目录构建；未部署。

原始日志：`.local-runtime/perf-review-final-tests.log`、`.local-runtime/perf-review-focused-tests.log`、`.local-runtime/perf-review-package.log`。

推送前另外以 `HEAD + 仅本次暂存的 31 个文件` 构建独立快照，排除其他未提交路由/发现/健康检查改动：网关 772 项、管理端 390 项、license-client 83 项全部通过。网关测试数与上文组合工作区快照不同，原因是本次提交不包含其他并行任务新增的路由测试。日志：`.local-runtime/perf-review-commit-tests.log`。
