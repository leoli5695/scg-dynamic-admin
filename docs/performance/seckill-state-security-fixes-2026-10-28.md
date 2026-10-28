# 秒杀状态与身份边界修复（2026-10-28）

本轮接续 `7a31fb2`，按用户补充要求暂缓真实支付平台接入、验签和支付回调幂等改造。旧 `/payment/callback` 仅检查字段、尚无有效验签，因此在安全链中固定拒绝访问；没有增加可绕过验签的启用开关。PaymentService 内部实现未改，后续接入时必须同时补齐支付 CAS、验签、回调持久化幂等与支付失败回补，不能直接重新开放该接口。

## 关单与退款状态

订单状态更新使用 `user_id + order_no + 当前状态` 的条件 UPDATE。过期未支付订单只能从 0 变为 2，足额退款只能从 1 变为 3。状态改变、stock_release_state=1、stock_release_id=order_no 在同一物理表的同一 SQL 中写入，更新失败不会生成回补意图。退款仍是系统内部订单状态处理，不代表已调用支付平台返还资金。

延迟回补消费者、未支付扫描和事务补偿共用 OrderLifecycleService。删除了靠 Redis processing 标记直接确认重投的路径。过期判断最终还要满足订单创建时间，延迟消息提前到达不能直接取消订单。读取到旧的待支付快照不再构成更新依据；SQL 会再次检查真实状态。

补偿任务看到“事务日志存在、订单不存在”时不再推定库存可以释放，也不先把事务改成 FAILED：消费者可能仍在落库。此类无法判定的预占保留为 PROCESSING 并记录告警日志，需要核对消息重试/死信和数据库结果；本轮选择不凭猜测放货，不能承诺所有未知故障自动恢复。过期 Redis pending 提示也不再授权回补，StockConfirmService 仅转到数据库核对。

## 持久化回补与去重

stock_release_state：0=没有回补意图，1=待确认回补，2=已确认。独立单线程每五秒扫描待回补订单，每轮至多处理查询返回的一百条；失败记录推迟三十秒，避免失效库存或坏记录一直挡住后续任务。线程只有一个固定周期任务，不使用随请求增长的内存补偿队列。

Lua 同时操作原库存 Key、购买集合、用户分片记录、`seckill:released:{activityId}` 哈希（旧格式不带花括号）。按订单号记录已回补数量，同一订单的重复执行不会再次增加库存。先校验 Key 类型、库存存在、数量和原购买/分片证据；缺失证据或库存过期时保持待处理，不凭空新建库存。Redis 返回成功后才确认数据库；如果 Redis 已执行但数据库确认失败，下次重试从 Redis 台账识别前次结果。

去重台账不设置 TTL，不能在仍有回补重试或历史订单时手动删除。新旧 Key 格式切换时必须一起迁移 released 哈希，现有迁移工具已包含该项。只恢复部分 Redis Key、手动清台账或重新使用活动 ID 都会破坏一致性前提，实际恢复必须核对库存与台账为同一版本的数据。

数据库仍是一用户一活动一订单。回补时台账同时写 `__closed_user:<userId>` 屏障，后续扣减读取该屏障，避免取消/退款后再次预占库存却永远无法通过数据库唯一索引。购买集合可以按原语义清除，但该数据库参与额度不能重新出售给同一用户。

所有数据库归档查询与删除都排除待回补订单；删除还检查当前状态，防止“查到可归档快照后，订单刚变成待回补”时丢掉持久化意图。旧取消/退款历史行统一保持 state=0，不猜测其库存是否已经归还。手动回补接口也只允许重试已存在、数量匹配的持久化意图，不能任意加库存。

## 用户身份

秒杀、退款和个人订单查询接入 Spring Security OAuth2 Resource Server。服务端验证 RS256 签名、issuer、audience、过期时间和正整数用户 ID；信任来源为配置的 RSA 公钥，或 HTTPS JWK Set。固定 RSA 公钥至少 2048 位，JWK 获取连接/读取超时各两秒。未配置身份信任时拒绝用户请求，没有默认测试密钥，也不把 X-User-Id 当作身份。

HTTP 入口使用已验证身份覆盖请求体 userId，客户端可以不传此字段。查询路径或参数指定其他用户时返回 403；订单号和退款查询都带可信 user_id 路由。运营活动订单列表需要 `scope` 中的 `seckill:admin`，包括通过 `/order/query?queryType=ACTIVITY_LIST` 进入的情况。原有内部接口 IP 白名单仍适用于其余内部/运维接口。

部署配置：

```yaml
seckill:
  identity:
    issuer: ${SECKILL_IDENTITY_ISSUER:}
    audience: ${SECKILL_IDENTITY_AUDIENCE:}
    jwk-set-uri: ${SECKILL_IDENTITY_JWK_SET_URI:}
    public-key-pem: ${SECKILL_IDENTITY_PUBLIC_KEY_PEM:}
    user-id-claim: ${SECKILL_IDENTITY_USER_ID_CLAIM:sub}
```

issuer、audience 和一个可信验签密钥来源都必须配置。user-id-claim 应指向登录系统发出的应用用户数字 ID；如果 sub 是 UUID，应配置实际数字用户 claim，不要猜测或转用请求体 ID。网关需要将原始 Bearer 凭证转发给服务，并配置与服务一致的真实签发方；旧的“只透传用户 ID 头”调用方式不再接受。

资源服务实现参考 [Spring Security 官方 JWT 文档](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)。Lua 回复转换与异常行为参考 [Redis Lua API](https://redis.io/docs/latest/develop/programmability/lua-api/)。

## Redis 降级

没有独占库存配额时停止新下单，不再按每个实例的库存比例或过期快照上架。LocalStockShelvingService 返回零且不读取共享库存推算配额；SeckillService 在 Redis 降级时直接拒绝，不调用本地扣减。原本地库存辅助代码保留用于旧数据核对和测试，不是新的下单通道。此策略降低故障期间可用性，以避免扩容、网络分区或多实例重启时把同一份库存卖多次；将来恢复降级销售需要先实现持久化、独占的库存配额。

## 升级与回滚

1. 停止秒杀写入，处理旧版本在途事务和已有本地降级缓冲，备份 MySQL 与 Redis。
2. 先执行 `seckill-core-engine/src/main/resources/sql/order-stock-release-migration.sql`，为现有八库订单表增加状态、流水号、退避时间和索引。首次安装的初始化脚本也已同步；关闭分片的部署应对实际 `seckill_order` 表增加相同列和索引。
3. 停流期间，从各物理订单表导出仍可参与活动的已有订单用户，字段依次为 seckill_id、user_id，使用 MySQL batch 模式导出无表头 TSV。用下面的工具生成参与屏障；确认 Key 版本后通过 redis-cli --pipe 应用。它只写参与屏障，不推断历史回补结果，也不增加库存。导出要覆盖全部物理库表；不能遗漏旧取消/退款订单，否则这些旧用户仍可能再次预占并遇到唯一索引冲突。
4. 配置真实登录系统 issuer、audience 和验签公钥/JWK，保持上一轮订单路由/Redis Key 版本不变；完整替换旧写入实例，再恢复流量。不要让旧版无条件更新与新版 CAS 流程混跑。
5. 观察 `seckill.stock.release.failed`、各物理订单表 state=1 数量与最长等待时间、PROCESSING 超时事务、MQ 重试和死信。

```powershell
# 仅生成文件；legacy/tagged 必须与部署中的 stock-hash-tag-enabled 一致。
node seckill-core-engine/scripts/plan-participation-barriers.mjs D:/migration/orders.tsv D:/migration/barriers.resp legacy
```

旧版本代码不认识持久化回补意图，直接回滚代码可能造成直接加库存与台账补偿重复执行。需要先停流、停止后台回补、核对台账与所有待回补行，再决定回退；不能只更换 Jar。不要自动把历史 state=0 批量改为 1。

本轮没有修改真实数据库/Redis、没有部署或推送。支付平台接入、验签、支付回调的专属幂等标记和原 PaymentService 内部竞态按要求暂缓；关闭的回调入口应保持关闭，后续启用前必须补齐。

## 验证

H2 MySQL 模式执行真实 MyBatis SQL：并发关单只有一个赢家，状态与回补意图共同回滚，已支付/已退款状态不被旧关单覆盖，错误金额或用户无法退款，待回补订单不能归档，失败记录退避且确认使用正确流水号。

真实 Spring Security 过滤链与 MVC 测试使用临时 RSA 密钥签发 JWT，验证签名、issuer、audience、过期/缺少有效期、非法用户 claim、伪造身份头、覆盖请求体 ID、跨用户路径拒绝、管理员权限、订单号查询携带归属以及旧支付回调拒绝。单元测试覆盖 Redis 成功但数据库确认失败后的重试、无库存或无持久化意图不确认回补、降级一百个实例也不上架共享库存。

Lua 完成语法和内存 Redis 命令桩校验，包括同一订单重复回补不增加库存、数量不一致拒绝、缺失预占拒绝、错误 Key 类型不产生部分修改。没有真实 Redis/Docker；Redis 集群、MySQL/ShardingSphere 故障切换、存量迁移演练及生产容量压测仍需集成环境验证，内存桩和 H2 不替代这些检查。

最终秒杀模块本地回归 209 项通过，零失败/错误；新增真实 Redis 回补脚本集成用例 4 项因无 Docker 跳过。原 SeckillStockConcurrencyTest、LuaSlidingWindowRateLimitTest 未运行，StockSyncLuaIntegrationTest 在无 Docker 时中止初始化，以上通过数不包含它们。Node 迁移工具测试 4 项通过，Maven package 通过。实际登录签发方/JWK 服务联调尚未执行。
