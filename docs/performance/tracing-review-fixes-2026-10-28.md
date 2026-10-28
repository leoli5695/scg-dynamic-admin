# 链路追踪系统修复（2026-10-28）

## 数据关联与采样

网关优先取 Reactor Observation 中的真实 Micrometer Span，其次使用当前 Span；只有不存在上层观测时才通过已配置的 Propagator 创建并结束自己的网关 Span。传出的 traceparent 使用真实上下文，响应在提交正文前设置 X-Trace-Id、X-Gateway-Span-Id。未安装 SDK 时仅保留有效上游上下文或生成 X-Trace-Id，不再随机制造一个没有导出记录的父 Span。

Starter 对 W3C sampled=00/01 都严格继承，优先于旧 X-Trace-Sampled 和本地概率；不会在每个服务重新决定采样。错误/慢请求的强制上报明确标为 SUMMARY_ONLY，不能补回此前未采集的调用细节。报告另带 openSpanCount、droppedSpanCount。异步 TaskDecorator 完成后恢复执行线程原先的上下文，兼容 CallerRuns 和嵌套执行。

## 上报与背压

Starter 管理端上报采用单发送线程，主队列、在途批次、延迟重试、降级落盘共用条数和字节额度。入队内容是有界编码后的不可变快照；重试沿用同一快照和报告 ID，不保留业务侧可变对象。

默认 gateway.trace.max-retained-bytes=8388608，按编码记录的两倍收费，编码前先预留最多两份单条额度。单条上限 gateway.trace.max-record-bytes=262144（最小 1024，最大 262144），HTTP 批次最多 100 条/约 1 MiB。条数沿用 async-queue-size。重试最多三次失败、指数退避并加随机抖动，延迟中的报告仍占额度。磁盘降级在同一专用线程执行，慢磁盘不会引入另一个无界队列；队列满时直接丢弃并计数。fallback-directory 可配置，文件按天与 50 MiB 大小滚动，每日首次写入清理七天前文件。降级日志不是持久化消息队列，不自动回灌，停机冲刷尽力而为。

管理端 HTTP 解码前限制为 2 MiB、最多四个并发接收请求；同时限制批次 100 条、单条编码 256 KiB、spans 500 条。缓冲区最多 1000 条/16 MiB 估算内存，额度包含正在落库的批次。默认 gateway.distributed-trace.max-retained-bytes=16777216。只允许一个待处理/执行中的刷新任务，数据库失败不再逐条重试。为保护不理解部分确认的旧客户端，真实缓冲区批量准入为整批成功或整批拒绝，整批拒绝返回 503。

新确认响应含 stage=ENQUEUED、accepted、rejectedIndexes、retryAfterMs。Starter 校验确认，只重试被拒绝的位置；不能解析/没有确认的旧 202 计为 unconfirmed 并降级保存，不冒充已接收。HTTP 超时也不会释放延迟重试的额度。服务端用稳定 reportId 唯一键和 JDBC 事务批次实现幂等，网络超时后的重发不会产生重复行。旧客户端没有 reportId 时按原始报告内容生成兼容 ID。

指标分层：Starter sent 代表 HTTP 成功响应，reported 代表 Admin 确认入队，rejected/failed.attempts/unconfirmed/fallback/fallback.dropped 反映失败路径；Admin stats 的 saved 代表成功完成持久化处理的报告次数（含幂等重发），并不等于新增行数。202 不代表已落库。数据库/进程故障仍可能丢失已入队诊断，需观察 saved、dropped、retainedBytes。

可选 OTLP 导出同步修复：独立单连接、单发送线程，入队字节快照最多 256 KiB，8 MiB 两倍编码预算包含在途批次；批次最多 100 条/约 1 MiB，不再逐批独立 subscribe。成功完成才增长 exported，Collector 返回 partialSuccess.rejectedSpans 时不计为完整导出成功。OTLP 成功同样仅代表接收端确认，不是后端持久化保证。

## 查询与展示

历史详情仅查数据库，不回调在线网关。总耗时优先使用持久化请求耗时。网关和任意下游都有记录，不再直接标为 COMPLETE，而是 OBSERVED、OBSERVED_WITH_ERRORS 或 PARTIAL；接口明确 completenessVerified=false，附缺失父节点、重复 Span、环、摘要、未完成异步操作、Span 限额等原因。未记录的外部父 Span 也会显示为缺失，不会声称完整。

服务时间使用已观测服务区间并集，避免把嵌套时间重复相加或把串行调用简化为最长单次调用；只有服务时间均位于网关记录窗口内才展示该值。跨机器时钟不一致、旧记录缺少起点时显示未知。过滤器自身时间包含等待，不能视为 CPU 时间；响应写入时间不能视为网络 RTT。网络时间和差额未测量时返回 null，前端显示未知，不再填 0 或构造相加等式。criticalPathVerified=false：现有数据不能证明完整因果关键路径。

瀑布图先从 Unix 毫秒时间戳减去服务起点；旧数据使用最早 Span 为基准。缺失时间和无效时长不会产生 NaN 或负宽度，原始记录不被修改。

## 升级顺序与兼容

1. 对管理端数据库执行一次 `gateway-admin/src/main/resources/sql/alter-distributed-trace-delivery.sql`，新增报告 ID/采样模式/异步及丢弃计数/开始时间字段、唯一索引，并扩大 spans/downstream_services 存储类型。已有历史行 report_id 为 NULL，不做猜测性合并。
2. 更新 Admin，再更新网关、Starter 和 UI。新 Starter 遇到旧 Admin 的空确认会转入 unconfirmed/fallback；升级顺序能避免这段过渡期。
3. 保留 MySQL URL 的 rewriteBatchedStatements=true，才能获得驱动侧批量重写收益。

没有修改共享数据库、没有部署或重启服务。本轮不承诺跨重启可靠投递，也未将 MySQL 追踪存储替换为专业时序追踪后端。旧网关 RequestTrace 的 traceId 唯一模型仍不适合在同一 TraceId 下保存多个网关入口快照，完整多入口模型属于后续演进；因此展示始终不声称全链路采集完整。

## 验证

使用 Java 21，在独立 `.local-runtime/trace-review-clean-validation` 快照运行 Maven：以已提交代码为基线，仅覆盖本轮链路追踪文件，避免并行任务修改代码和构建产物。核对过覆盖文件与工作区源文件的 SHA-256，代码与测试内容一致。回归覆盖真实 Observation 父 Span、响应提交前关联头、W3C 未采样继承、异常摘要标记、CallerRuns 上下文恢复、不可变快照、部分确认只重发拒绝记录、超时重试占用额度、超大编码拒绝、OTLP 完成计数与部分拒绝、数据库停顿下的任务合并/内存计费、幂等批次与事务回滚、历史查询离线可用及服务区间并集。

最终后端测试通过：Starter 160 项、my-gateway 855 项、Admin 413 项、license-client 83 项，无失败或错误。GatewayE2ETest 首次运行因 Nacos OFFLINE、缺少 config.gateway.access-log 缓存而在应用启动阶段失败，最终回归排除该项；外部 MySQL 集成测试也未运行，以上数量不包含这些测试。

Starter、my-gateway、Admin 及所需模块的 Maven package（跳过已完成的测试）通过。前端时间轴计算测试 3 项通过；TypeScript、相关 ESLint 检查和 Vite 生产构建通过。Vite 仍提示现有 charts/antd 分包体积较大，本轮未做全站拆包。真实 Collector、跨机器时钟、MySQL 迁移及持续容量压测未在本轮环境实测。
