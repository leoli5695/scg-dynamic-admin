# AI 请求配额账本与降级恢复

启用 Token 周期配额的生产过滤器使用 `TokenQuotaLedger`，为每个请求生成固定 UUID。预扣、最终补扣/退款、提前终止退款、本地降级回补均复用这个 ID，并冻结租户、月/日周期、额度范围和初始配置。

## 正常请求

1. 在独立持久化目录写入 `PREPARING` 意图，文件强制刷盘后重命名。
2. Redis Lua 在一次执行中检查并预扣月、日额度，保存该请求的回执。额度不足直接拒绝，不再转为只读检查后继续放行。预扣估算乘数只应用一次。
3. 确认预扣及其额度基线持久化后，写入 `ADMITTED`，再向上游放行。Redis 写入超时不会再触发第二次本地放行；该请求返回 503，账本保存同 ID 的取消意图并继续恢复退款。
4. 响应结束后，先持久化最终用量，再执行 `FINAL`。Redis 从该请求回执中读取已计额度，只调整差额，同时更新两个桶和回执。重复执行不会再次扣减或退款。账本按实际 Token 精确结算，不沿用旧退款接口的小额跳过/退款上限。
5. 最终状态交给持久化用量 outbox 后，才删除请求意图文件。outbox 暂满或管理端不可用时，记录仍被保留。

缺失最终用量的 `ADMITTED` 记录在进程恢复时转为 `REVIEW`，保留预扣，不猜测退款。`PREPARING` 尚未向代理返回放行，恢复时自动取消为 0。具有已知最终用量的记录继续按原意图结算。

## 本地降级

Redis 明确不可用且配置允许 `localCacheFallback` 时，可从最近确认并持久化的月/日额度基线放行。每次本地放行都先写请求账本；额度判断计入尚未确认回补的本地用量，遵守 `fallbackQuotaPercent`。

月/日基线单独写文件，生命周期不依赖请求文件是否已清理。进程重启不会把已用额度重置成 0。缺少有效基线、存在未确认的 Redis 操作、关闭本地降级、账本空间不足或持久化失败时，拒绝新请求。基线文件数量上限为 `max(10000, 2 × max-pending)`；满时逐出旧基线，缺失基线会使对应租户在 Redis 不可用期间停止新放行。

Redis 恢复后，尚未结束的本地请求可以先按预扣量回补；请求结束后再结算差额。已结束的本地请求直接回补最终应计量。两者共用同一 Redis 回执，网络超时和进程重启不会产生第二笔回补。跨日/月请求继续写原始周期桶，不挪到新周期。

降级限额仍然是每个实例基于其最近已知数据的保守判断，不能在 Redis 不可用期间提供全局精确限额。恢复后各实例把自己的请求按固定 ID 回补。

## 自动恢复与人工核对

默认每 5 秒轮转处理最多 64 条记录。上报事件的 `eventId` 与请求 UUID 相同；管理端仅允许同一条意图从 `quotaLedgerState=PENDING` 前进到 `READY`，不会被乱序旧消息降级，也不会覆盖人工核对后的用量。

自动恢复仍在进行时，控制台和服务端均拦截人工核对。需要转人工的失败记录先在 Redis 写入 `BLOCKED` 回执，阻止迟到的自动命令再改额度，再标记可人工处理。无法确认阻断的记录继续保持自动恢复中，不开放人工补偿。Redis 对已越过重放时限的新调整拒绝执行；已存在的完成回执仍可用于确认结果。

## 部署

1. 在前序迁移完成后执行 `gateway-admin/src/main/resources/sql/alter-token-quota-ledger.sql`，先升级管理端，再升级网关。
2. 升级前确认旧版本内存中的本地降级积压已经清空。已随旧进程丢失的内存用量无法由新账本追溯恢复。
3. 为每个网关进程设置独立的 `GATEWAY_QUOTA_LEDGER_DIRECTORY` 持久卷，同时保留已有用量 outbox 持久卷。默认账本目录 `${user.dir}/data/token-quota/${gateway.instance-id}`，同目录不能被多个进程占用。目录不可初始化时启动失败。初始化权限见下条。
4. 账本和 outbox 目录由**容器运行用户**创建。若 `${user.dir}`（即模块目录）本身属 root——为了让 root:600 的 env 文件不落在应用用户可写的目录里，通常正是这种属主——应用无法自行创建 `data/`，启动会以 `AccessDeniedException` 连带 `tokenQuotaLedger`、`tokenUsageReporter`、`tokenQuotaPreCheckFilter` 一起失败，表现为健康检查超时而不是明显的配置错误。必须在宿主机预建并交给运行用户：`install -d -o <uid> -g <gid> -m 755 <模块目录>/data`，uid/gid 取 `docker inspect -f '{{.Config.User}}'`。不要把模块目录整体 chown 给应用用户，那会让容器进程能改写 env 与 run.sh。`scripts/deploy-gateway.sh` 的 jar 前置检查会在目录缺失时按容器运行用户自动补建、属主不符时直接中止。
5. 配置 `GATEWAY_INTERNAL_TOKEN`。未配置用量上报 Token 时，账本管理的配额请求返回 503，避免记录无法可靠交付后堆满磁盘；不绑定 Token 配额策略的流量不走此路径。
6. `GATEWAY_QUOTA_LEDGER_MAX_PENDING` 默认 4096，每条意图最多 16KiB；保留更新记录所需的逻辑容量，不逐出未完成的财务意图。基线文件另行限量，每个最多 1KiB。
7. `GATEWAY_QUOTA_LEDGER_REPLAY_HOURS` 默认 168，可设 1..720。Redis 请求回执保留到请求截止时间后至少 24 小时；原周期额度桶保留到周期结束加重放时长。应按请求速率评估 Redis 回执容量，不能在窗口内逐出或清理回执。键格式沿用现有部署，跨槽 Redis Cluster 尚不支持。
8. `GATEWAY_QUOTA_LEDGER_RETRY_MS` 默认 5000。过期、无效桶或冲突会转待人工复核；网络错误保留原操作继续恢复。

文件采用刷盘和原子替换恢复进程崩溃；机器掉电、Redis 主从切换后的保留能力仍取决于文件系统、持久卷及 Redis 持久化/复制配置。账本和 outbox 必须跟随同一网关身份迁移。

## 验证与观测

`TokenQuotaLedgerTest` 使用真实文件、重启和可注入故障的 Redis 回执契约，覆盖不确定预扣取消、确认丢失、额度基线恢复、未知用量保留、提前退款、回补及并发结算。管理端测试覆盖事件乱序、意图不可变更和人工补偿互斥。

设置专用测试环境变量 `GATEWAY_TEST_REDIS_HOST`，可选 `GATEWAY_TEST_REDIS_PORT`、`GATEWAY_TEST_REDIS_PASSWORD` 后，可运行 `TokenQuotaLedgerRedisTest` 验证实际 Lua；未配置时该测试跳过。该测试只使用随机前缀临时键并清理自己的键。真实 MySQL/Redis 故障演练与容量压测仍需单独执行。

关注 `token.quota.ledger.pending`、`token.quota.ledger.local.pending`、`token.quota.ledger.local.grants`、`token.quota.ledger.retries`、`token.quota.ledger.persistence.failures`、`token.quota.ledger.review.required` 和 `token.quota.ledger.uncertain.admission`。

Lua 在任何额度写入前校验两个桶，并在支持时预检写命令权限；使用服务端时间判断重放截止时间。复制语义参考 [Redis Lua 官方文档](https://redis.io/docs/latest/develop/programmability/eval-intro/)。
