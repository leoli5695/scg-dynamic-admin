# 项目现状评估(2026-10-27,取代 2026-04-25 的自评)

> **⚠ 时点评估,已失效。** 评估基线为 commit `2e80a2e`(2026-10-27)。本文所有数字(测试数、CI 结论、
> 生产比对、安全清单)**仅截至该提交**,其后已被 2026-10-28 的实测推翻或超越,见下文各节的"实测更正"。
> 请勿把本文数字当作当前状态。
>
> **最新实测入口:** CI 现状见 GitHub `leoli5695/scg-dynamic-admin` 的 workflow `CI` 最近一次 run
> (2026-11-12 核实的 run `40879153086` @ `280acde`:Frontend=success、Backend=failure、Images build=failure;
> 两个 failure 的根因已用 job 日志 + surefire 制品证实并修复,见第一节"实测更正");
> 本机复测命令 `mvn -o -B -ntp -fae test`、`npm run lint` / `npm run type-check` / `npm run test:routes`。
> **这些数字每次提交都会漂移**——本文下表测于 2026-10-29 的 `a750757`,请用上面的命令重测,不要引用本文数值。
>
> 2026-04-25 的版本自评 4.8/5、"达到商业级"、"安全防护满分",且技术栈写的是 Java 17 / Boot 3.2。
> 这些结论与仓库自身的提交历史矛盾(见下文),已作废。本文只写**可验证**的事实,
> 每条都给出验证方式;验证不了的明确标注为未验证。
> 评估基线:commit `2e80a2e`,JDK 21,离线 `mvn -o`。

---

## 一、可验证的事实

### 测试(实测,非文档转述)

**实测更正(2026-11-12,HEAD `280acde` + 本批修复的工作树,`mvn -o -B -ntp -fae test`,只跑默认模块):**
下面那张 2026-10-29 的表已再次过期,保留它是为了留下漂移轨迹,**不要引用其数值**。本次实测:
my-gateway **1091 / 0 失败 / 0 错误 / 2 跳过**、gateway-admin **757 / 0 失败 / 80 错误**、
license-server **47**、gateway-trace-starter **160**、license-client **83**、moderation-spi 0;
默认模块合计 **2138 / 0 失败 / 80 错误**。seckill 是 profile-only 模块,本次未跑。

- **11 个真失败已修**:`AiUsageLifecycleTest` 的 mock 期望同步到 `reportDurably(...)`,
  无调用者的 `report(...)` 别名删除。
- **80 个错误全是环境性的**(无本机 MySQL:3306 / Nacos,12 个类在 context 加载期级联)。
  上一版记的 105 个错误里有 **25 个不是环境性的**:`FeatureGateFilter`(`0d75ef5` 引入)是
  `@Component` 的 servlet `Filter`,而 `@WebMvcTest` 会加载所有 `Filter`,于是本模块仅有的两个
  web 切片测试都因取不到 `SystemSettingsService` 而建不起 context。已给两个切片补 `@MockBean`,
  并新增 `FeatureGateFilterTest`(10 个测试,已做变异验证)覆盖闸门本身。
- **前端不要引用本文数字**:测量时 `gateway-ui` 正被并行会话重构(123 个测试 / 10 个失败,
  全部是测试 harness 解析不了新增的 `src/styles/palette.ts`,以及本机 Node 22.14 缺类型剥离),
  不是产品缺陷,也不是稳定基线。

**实测更正(2026-10-29,commit `a750757` 的工作树):** 评估时点(2e80a2e)的 1603 已过期。当前全反应堆
`mvn -o -B -ntp -fae test` 为 **2107 个测试、11 个断言失败、84 个错误、6 个跳过**。
84 个错误全部是缺外部依赖;**但 11 个失败是真的**——全在 `AiUsageLifecycleTest`,由 `a69fd71`
把 `TokenUsageCollectorFilter` 从 `report(...)` 改成 `reportDurably(...)` 却没同步测试造成
(测试仍 `verify(reporter).report(...)`)。用量本身采集正确,是 mock 期望过期;但该类现在是红的,
会掩盖它内部的真实回归。已用父提交的干净 worktree 对基线,同样 11 个失败,故与其后的改动无关。
(数字漂移轨迹:`ed93868` 2020 → `d42a273` 2077 → `b45d40a` 2082 → 现在 2107,**一天内四次**,
这就是不要引用本文数字的原因。)

| 模块 | run | fail | err | skip | 原因 |
|---|---|---|---|---|---|
| my-gateway | 1035 | **11** | 0 | 2 | 11 个失败全在 `AiUsageLifecycleTest`(`report` → `reportDurably` 未同步测试) |
| gateway-admin | 572 | 0 | 80 | 0 | `entityManagerFactory` → 无本机 MySQL:3306,其余为同 context 级联 |
| gateway-trace-starter | 160 | 0 | 0 | 0 | — |
| license-client | 83 | 0 | 0 | 0 | — |
| license-server | 40 | 0 | 0 | 0 | — |
| gateway-moderation-spi | 0 | 0 | 0 | 0 | 契约模块,只有一个 SPI 接口,尚无测试 |
| seckill-core-engine | 217 | 0 | 4 | 4 | 3 × 无 Redis:30379;1 × 要 Docker |
| **合计** | **2107** | **11** | **84** | **6** | |

(评估时点 2e80a2e 的旧表为 my-gateway 684 / admin 458 / trace-starter 147 / seckill 191,合计 1603;已作废。)

- 主代码 0 个 `FIXME`/`HACK`,**6** 个 `TODO`(评估时写 7,实测为 6)。0 个 `@Disabled`。
- **实测更正:** 前端 `gateway-ui` **不是 0 个测试**:`tests/` 下有 **15** 个 `*.test.mjs`、
  共 **51 个测试全部通过**;`npm run lint` 与 `npm run type-check` 当前**退出码均为 0**
  (评估时"`npm run lint` 退出码 1"已在 `cfb0050` 修复后失效)。
  注意 `npm run test:routes` 需要 Node ≥22.18(类型剥离默认开启);本机 22.14.0 上要临时加
  `--experimental-strip-types`,但该旗标在 Node 26 已被移除,不要写进脚本。

### 交付验证(最弱的一项)

- **实测更正(事实基线 §5):** "ci.yml 自述从未执行过一次" 是**错的**。该 workflow 已运行 39+ 次;
  HEAD 那次(run `40658113604`)Images build=**success**、Frontend(gateway-ui)=**success**、
  Backend(Java 21, Maven)=**failure**(失败步骤 = `Build and test`)。ci.yml 顶部"从未执行"的旧注释
  已在 `0251e18` 改成实测状态。
- **实测更正:** "1603 没有任何独立机器复核过" 也不准确:CI 的 Backend job 确实在独立机器(GitHub runner)
  上跑过,只是结论为 failure,且 job 日志/annotations 匿名取不到(404),故 backend 具体哪个测试红了
  无法匿名定位。
- **实测更正(2026-11-12):"无法定位"已解除。** 用仓库凭据(只读)取到了 run `40879153086` 的
  job 日志与 `surefire-reports` 制品,两个红 job 的根因都已证实,不再是推断:
  **Backend** = my-gateway `Tests run: 1042, Failures: 11`,制品里唯一的红类就是 `AiUsageLifecycleTest`;
  **Images build** = `Build gateway-admin image` 步骤报
  `Child module /app/gateway-moderation-spi of /app/pom.xml does not exist`(`61e48a1` 加了模块却没加
  Dockerfile 的 `COPY`)。两者均已修复。
  另需更正本文与旧 README 的一个推断:**"CI 是那些 `@SpringBootTest` 类唯一真正执行的地方" 是错的**。
  `mvn -B -ntp verify` 没有 `-fae`,反应堆在 my-gateway 就停了,Reactor Summary 里
  `gateway-admin ... SKIPPED`、`demo-service ... SKIPPED`,制品也只含 4 个模块。
  即那些集成测试**在 CI 也从未跑过**;修掉 11 个失败后它们才会第一次执行。
- **后续实测(同日,run `43209492897` @ `7047282`):上一句的预言兑现了。** Images build 转 **success**、
  my-gateway 全绿,gateway-admin 第一次真正执行,报出 **8 失败 + 2 错误**
  (`RouteApiTest` / `RouteNamespaceTest` / `StrategyApiTest`)。单一根因:`2209032`
  (**正是 CI 首次转红的那个提交**)在 `RouteService.java:327` 加了"命名空间必须存在"的守卫,
  而这三个类建路由时不传 `instanceId`,于是 create 返回 400、其后断言全部级联失败。
  `2209032` 早于 `a69fd71`,所以**admin 的失败才是最初那个,my-gateway 的 11 个失败后来把它挡住了**。
  据此再更正一条:旧 README 推断"CI 另有一个本机看不到的失败"——**方向是对的**,
  我先前据 `280acde` 制品写下"没有别的失败、2209032 的红已被修掉"是**错的**(制品只含 4 个模块,
  是被 fail-fast 截断的,不是全量结论)。已改:三个测试传它们本来就已创建的 instance,
  "不传 instanceId"那例改为断言 400。
- 生产部署(见下)已核实包含全部安全修复,但部署流程此前**没有**"换 jar 后校验 JVM 确实重启"
  这一步;2026-10-27 起由 `scripts/deploy-gateway.sh` 补齐(强制重启 + 断言 JVM 启动时间晚于
  jar 落盘时间 + 健康检查 + 失败回滚)。

### 安全(依据提交历史,非独立审计)

2026-10-25 ~ 10-26 修复过以下**真实存在过**的缺陷,说明此前的安全自评严重失准:

- `/payment/query` 按订单号越权查任意订单支付状态(IDOR)
- 访问日志配置路径穿越;登录锁定可被用作 DoS
- 审计日志的操作者 IP 由调用方自述
- 热更新到不了运行中的网关(路由/服务/认证/证书/策略/插件/访问日志)
- 秒杀降级模式发出订单却不扣库存;两个对账器从绑定值而非真值写库存
- `docker-compose.yml` 不是合法 YAML;`LICENSE_ENABLED=false` 使两个应用同时不可用

这些现已修复并有对应测试(如 `/internal/**` 守卫)。但**自评校准偏差如此之大,未审计面应假定仍有问题**。

- **实测更正:** "现已修复"的结论在评估基线(2e80a2e, 10-27)之后**再次被打破**。2026-10-28 又有一批
  真实安全缺陷被修复,全部晚于评估日期:`1581ec2`(14:21,XFF 伪造/网络访问边界)、
  `836f5b1`(14:08,全局限流加固)、`6c6eb75`(14:47,Copilot 会话隔离)、
  `812600d`(14:51,秒杀可信身份——一类新的 IDOR)。这进一步印证上句"未审计面应假定仍有问题",
  也说明评估时点的安全结论不能外推到其后任何提交。

### 生产部署(2026-10-27 核实)

- 应用服务器跑 `gateway-admin` 与 `my-gateway` 两个容器,jar 为 bind mount;
  **MariaDB 10.11.18**(不是 MySQL)/Redis 在另一台基础设施服务器上(事实基线 §7)。
- 线上 jar 经字符串标记比对,包含上表全部安全修复;JVM 启动时间晚于 jar 落盘时间,确认加载生效。
- 进一步逐条目比对:线上两个 jar 与前端 dist 和 `2e80a2e` 的构建产物**内容完全一致**
  (gateway-admin 827/827、my-gateway 709/709 个 jar 条目 CRC-32 相同;dist 75/75 文件 SHA-256 相同;
  唯一差异是嵌套 `license-client-1.0.0.jar` 的外壳时间戳,其内部 40/40 条目相同)。
  注意:该比对**只针对评估基线 `2e80a2e`**。
- **实测更正(事实基线 §1):** "生产已等于 HEAD,无需部署" 已**失效**。2026-10-28 实测线上为 `ed93868`,
  而 HEAD 已前进(事实基线时点为 `0251e18`,落后 2 个提交,其中 `cfb0050` 影响前端产物;此后 HEAD 仍在
  继续前进)。即生产落后于 HEAD,需要部署;上句 827/709/75 的一致性结论只对 `2e80a2e` 成立。
  注意:构建时间早于提交时间(构建 10-26 21:25/22:26,提交 23:23),
  所以"提交时间晚于部署时间"不能用来推断线上版本落后。

---

## 二、评分

> **下表为 2026-10-27 时点打分,不随代码更新。** 6 个维度中有 3 个(测试、可复现性/交付验证、安全)的
> 依据已被其后实测推翻(见第一节各"实测更正");重算需重新实测,本文不代劳重算分数,故分数保持原值。
> 下表"依据"列中的个别事实数字已按 2026-10-28 实测更正,但**分数未变**。

| 维度 | 分 | 依据 |
|---|---|---|
| 架构设计 | 4.0 | 控制面/数据面分离、模块依赖单向、父 pom 集中管版本。扣分:gateway-admin 仍有 1600 行级 service |
| 代码质量 | 4.0 | 19 万行 0 FIXME/HACK;ToolExecutor 已从 4241 行拆至 553 行 |
| 测试 | 3.5 | 2107 测试、**11 个真失败**(实测更正,测于 `a750757`;评估时点为 1603、当时记 0 真失败)。11 个失败全在 `AiUsageLifecycleTest`,由 `a69fd71` 改用 `reportDurably` 未同步测试造成(**2026-11-12 已修,见第一节;本行分数仍为时点值,不代为重算**)。**按本行自己的扣分逻辑,今天的分数应低于 3.5**——但本表是 2026-10-27 的时点打分,不代为重算;扣分:前端测试覆盖仍薄(现 51 个,评估时误记 0)、trace-starter 曾用 300 次同义反复断言撑数字 |
| 文档准确性 | 2.5 | 体量与散文诚实度优秀;但测试表曾 6 行错 4 行、自称"没有 CI",即本文取代的旧自评 |
| 可复现性/交付验证 | 2.0 | CI 已运行但 Backend job 当前 failure 且日志匿名不可取(实测更正,原"CI 从未执行"为错);三个外部依赖只文档化了两个;部署校验 10-27 才补上 |
| 安全(现状) | 3.0 | 已知缺陷已修且有测试;扣分依据是历史自评与真实缺陷的偏差,而非现存证据 |

**综合 3.2 / 5。** 与旧自评 4.8 的差距不在代码,而在"声称"与"验证"之间的距离。

---

## 三、已知缺口(按性价比排序)

1. **实测更正(2026-11-12):** CI 并非"首跑从未发生";它已运行 67 次。Backend 与 Images build 两个红 job
   的根因**已定位并修复**(见第一节),不再是"需在有权限的环境排查"。当前真实缺口转为:
   **Backend job  fail-fast,把 gateway-admin / demo-service 整个跳过**,所以那 12 个集成测试类在 CI 也从未执行;
   给该 job 加 `-fae` 才能一次拿到全模块结论(会改变 CI 的阻塞语义,未擅自改)。
   评估时"首跑会在 frontend lint 失败"的预测已被证伪(Frontend job 在 `cfb0050` 后转绿)。
2. **实测更正:** 前端并非 0 测试,现 `tests/` 有 15 个 `*.test.mjs`、51 个测试全过(测于 `a750757`);缺口转为**覆盖广度**
   (相对庞大的 UI 而言 25 个仍偏少),而非"完全没有"。
3. 84 个环境错误在本机永远跑不了;要覆盖需隔离的测试 MySQL/Redis 或 Testcontainers。
4. gateway-admin 的巨型 service(PrometheusService 1609 行、GatewayInstanceService 1511 行、
   StressTestService **1163** 行,实测更正,评估时误记 1475)尚未拆分。

---

**评估日期:2026-10-27(评估基线 `2e80a2e`;本文数字截至该提交,之后已失效,见文首提示)**
**评估方式:离线构建 + 全量测试 + 生产环境只读核查;未做独立安全审计**
