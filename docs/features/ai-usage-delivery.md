# AI 用量可靠投递

网关完成用量解析和配额结算后，在 `boundedElastic` 线程上把上报快照写入本地 outbox。每条记录生成固定的 `eventId`，写入临时文件并 `force(true)`，再重命名为待投递文件。只有管理端返回 2xx 后才删除该文件。

发送批次最多 32 条，失败从 250ms 开始指数退避，最长 30 秒。进程重启后继续读取同一目录中的待投递文件。管理端使用 `event_id` 唯一索引去重，因此管理端已提交、网关未收到响应时也可以安全重发。历史数据及未携带 `eventId` 的旧客户端保留原来的写入方式。

## 部署

1. 已有数据库先执行 `gateway-admin/src/main/resources/sql/alter-token-usage-outcome.sql`（若尚未执行），再执行 `alter-token-usage-event-id.sql`。两份脚本均只执行一次。
2. 先升级管理端，再升级网关，确保接收端已经支持去重。
3. 设置 `GATEWAY_INTERNAL_TOKEN`，并为每个网关进程配置独立的持久化目录 `GATEWAY_USAGE_OUTBOX_DIRECTORY`。默认目录为 `${user.dir}/data/token-usage/${gateway.instance-id}`。同一目录只能被一个进程持有，重复使用或无法初始化目录时启动失败。
4. 根据管理端不可用的容忍时长配置容量。默认最多 4096 条、64MiB，每条最多 16KiB。环境变量为 `GATEWAY_USAGE_OUTBOX_MAX_RECORDS` 和 `GATEWAY_USAGE_OUTBOX_MAX_BYTES`。恢复的积压超过新配置容量时，需要提高容量才能启动并排空。

## 观测与边界

- `token.usage.report.queued` / `token.usage.report.queued.bytes`：尚未确认的本地记录数和字节数。
- `token.usage.report.retries`：投递或确认清理失败的次数；记录仍在本地保留。
- `token.usage.report.failures`：无法写入本地 outbox 的记录数，包括容量用尽、磁盘故障和记录过大。应对增长设置告警；持久化失败日志每 30 秒最多输出一次。

这里提供的是成功写入本地 outbox 后的至少一次投递及接收端去重。目录必须在容器替换后保留；文件系统及持久卷的持久性决定机器故障后的恢复能力。进程在写入 outbox 之前崩溃、磁盘故障或容量耗尽仍可能留下缺失记录。满容量时保留已有记录，拒绝新记录并计数。

该机制不改变配额结算，也不自动修复 `UNKNOWN` 或结算失败的记录；这些仍需后续对账流程处理。不要把用量投递去重理解为配额结算的事务保证。
