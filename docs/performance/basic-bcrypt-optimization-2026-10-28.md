# Basic BCrypt 并发校验合并对照

- 同一台主机、Java/JVM 配置、生产源码差分指纹和压测参数；仅切换 `gateway.auth.basic.coalesce-enabled`。
- 两组都使用同一固定账号重复请求、BCrypt cost=10，成功结果缓存 TTL=0。优化不会降低 BCrypt 强度，也不靠跨请求成功缓存取得结果。
- 每组并发 1/16/64，每档 2 次，预热 3s、测量 8s；测量前各有 20s BCrypt 预热。
- Java 25.0.4；堆 512MiB，直接内存上限 256MiB，ActiveProcessorCount=4（不是 OS CPU 配额）。

| 并发 | 关闭合并 req/s | 开启合并 req/s | 关闭 P99 ms | 开启 P99 ms | 关闭 CPU 核数 | 开启 CPU 核数 | 保守吞吐倍数 | 失败 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 13.75–16.25 | 16.00–16.63 | 119.46 | 75.83 | 1.02–1.07 | 1.03–1.08 | 0.98x | 0 |
| 16 | 50.88–58.25 | 244.00–250.00 | 539.24 | 104.48 | 4.18–4.33 | 1.39–1.53 | 4.19x | 0 |
| 64 | 60.50–61.63 | 1042.13–1059.38 | 1227.75 | 75.37 | 4.06–4.18 | 1.36–1.65 | 16.91x | 0 |

P99 列为两次重复中较大值；保守倍数为开启组最低吞吐 / 关闭组最高吞吐，不是统计置信区间。低并发 Basic 样本少，P99 仅作初步观测。

## 适用边界

- 只合并尚未完成且凭证完全一致的校验，每个请求仍独立做路由授权。任务完成后移出共享表；默认没有后续成功缓存。
- 改密、算法变化、凭证停用不能复用旧校验；错误密码不写成功缓存；不同策略不混用。
- 隔离请求及大量不同账号/密码仍要支付 BCrypt 成本，不能把这里的同凭证吞吐套用于所有认证流量。
- 成功短缓存另有可选开关，默认关闭，最多 5s；本次两组都未开启。
- 本机闭环测试不代表生产容量，未覆盖 TLS、真实 Redis/Nacos/管理端、独立压力机和长期稳态。

## 复现

```powershell
./tools/gateway-perf/run.ps1 -Cases 'gateway-basic' -BasicCoalescing false -Levels '1,16,64' -Repetitions 2 -OutputDirectory './tools/gateway-perf/results/basic-off'
./tools/gateway-perf/run.ps1 -Cases 'gateway-basic' -BasicCoalescing true -Levels '1,16,64' -Repetitions 2 -OutputDirectory './tools/gateway-perf/results/basic-on'
node tools/gateway-perf/compare-basic.mjs <off-results.json> <on-results.json> <report.md>
```
