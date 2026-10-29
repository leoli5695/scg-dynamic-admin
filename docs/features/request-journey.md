# 本次请求诊断（Request Journey）

以同一个 Trace ID 把「网关实际命中的路由」「请求追踪记录」「AI Token 计量明细」关联到一张页面上，回答"我刚发的这一个请求到底发生了什么"。整条链路只读已有记录，不会重放请求、不会再次调用上游（`gateway-ui/src/locales/zh/journey.json` 的 `journey.subtitle` 也是这么写的）。

特性由提交 `3087aa2`（"connect route debugging with request traces, metering and diagnosis"）引入，同时改动了 gateway-admin、my-gateway 和 gateway-ui 三侧，部署时三者必须一起升级（见 [route-debug.md](route-debug.md) 末段）。

---

## 1. 与相邻特性的边界

这几个特性都围绕"一次请求"，职责不同，容易混：

| 特性 | 查询维度 | 数据表 | 文档 |
|---|---|---|---|
| **Request Journey（本文）** | **单个 Trace ID + 单个实例**，精确关联，不受图表时间范围影响（`RequestJourneyController.java:11` 的类注释即此意） | `request_trace` + `token_usage_history` | 本文 |
| Request Tracing | 时间范围 / 路由 / IP / 状态码的**列表与统计**，分页查询 | `request_trace` | [request-tracing.md](request-tracing.md) |
| Route Debug | **发起**一次真实请求并流式回显响应；本特性的主要入口 | 不读表，实时调用网关 | [route-debug.md](route-debug.md) |
| Filter Chain Analysis | 过滤器级别的执行统计与分位数 | `filter_chain_execution`（由 `TraceReportDispatcher.java:100` 上报到 `/api/filter-executions/internal`） | [filter-chain-analysis.md](filter-chain-analysis.md) |
| Traffic Topology | 服务间流量拓扑可视化 | `request_trace` 等 | [traffic-topology.md](traffic-topology.md) |
| Historical Comparison | 环比/同比的指标与配置对比 | Prometheus 实时查询 | [historical-comparison.md](../design/historical-comparison.md) |
| AI Usage Reconciliation | 对 `UNKNOWN`/`ESTIMATED` 用量与 `PENDING`/`FAILED` 结算做人工核对 | `token_usage_history` + `token_usage_reconciliation` | [ai-usage-reconciliation.md](ai-usage-reconciliation.md) |

**本特性不读 `distributed_trace` 表。** 跨服务/跨中间件的全链路 span 由 `FullChainTraceController` 与 `DistributedTraceService` 负责，与本文的端点无关。

本特性也**不做**任何判定以外的加工：不重放（重放见 [request-replay.md](request-replay.md)）、不核对补偿（补偿见 [ai-usage-reconciliation.md](ai-usage-reconciliation.md)，本页只提供跳转入口）。

---

## 2. 数据来源与生命周期

### 2.1 追踪侧（`data.trace`）

- 表：`request_trace`（`gateway-admin/src/main/java/com/leoli/gateway/admin/model/RequestTrace.java:15`）。
- 读取方式：`RequestTraceRepository.findByTraceId(traceId)` 返回 `Optional`（`RequestTraceRepository.java:21`），`trace_id` 列带唯一约束（`RequestTrace.java:39-40`）；随后在内存里 `.filter(trace -> instanceId.equals(trace.getInstanceId()))`（`RequestJourneyController.java:26`）——**实例不匹配等同于查不到，返回 `null`，不报错**。
- 写入方：网关 `TraceCaptureGlobalFilter.captureTrace(...)`（`:390`）组装 map（`:426` 起），经 `sendTraceToAdmin`（`:507` → `:693-695`）交给 `TraceReportDispatcher.submit`，批量 POST 到 admin 的 `/api/traces/internal/batch`（`TraceReportDispatcher.java:99`）。该派发器是有界尽力投递：队列 `ArrayBlockingQueue(256)`（`:26`）、每批最多 16 条（`:73`）、HTTP 超时 2 秒（`:111`）、队列满即丢弃（`:57`）。**因此追踪是异步入库的，请求刚发完就查可能查不到**——前端为此做了短暂轮询（见 §5.2）。
- 保留期：`request_trace` **没有自动清理任务**，只能显式调用 `DELETE /api/traces/old?daysToKeep=7`（`RequestTraceController.java:454-456`，默认 7 天；`RequestTraceService.java:422-424`）。详见 [request-tracing.md](request-tracing.md)。

### 2.2 计量侧（`data.usage`）

- 表：`token_usage_history`（`gateway-admin/src/main/java/com/leoli/gateway/admin/entity/TokenUsageHistoryEntity.java:15`）。
- 读取方式：`TokenUsageAnalyticsService.eventsByTrace(instanceId, traceId)`（`:117-120`）→ `TokenUsageHistoryRepository.findByInstanceIdAndTraceIdOrderByIdAsc`（`:35`）。**按主键 id 升序返回全部匹配行，不去重、不合并、不分页**（内部用 `PageImpl` 包了一层，但 `total`/`page`/`size` 被 controller 丢弃，只取 `content`，见 `RequestJourneyController.java:37`）。
- 保留期：由 Token 配额侧负责，调用点是 `TokenQuotaService.java:645` → `TokenUsageHistoryRepository.deleteByRequestTimeBefore`（`:78`）。本特性只读。
- 投递可靠性（outbox / `eventId` 幂等）见 [ai-usage-delivery.md](ai-usage-delivery.md)。

### 2.3 两侧可能不同步

追踪与计量是两条独立的上报通道，任一侧缺失都不代表请求没执行。前端把这种状态显式区分为 `pending` / `traceMissing` / `usageMissing`（`gateway-ui/src/features/request-journey/links.ts:15-16`），文案见 `journey.advice.traceMissing`、`journey.advice.usageMissing`。

---

## 3. API 端点

`RequestJourneyController` 只有**一个** mapping，全部核过：

| 方法 | 路径 | 参数位置 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/request-journeys/{traceId}` | `traceId` = path；`instanceId` = **query，必填**（`@RequestParam`，无 `defaultValue`，`RequestJourneyController.java:20`） | 控制台登录 JWT（任意已登录用户，**不要求 ADMIN**） |

- 鉴权依据：`/api/request-journeys/**` 不在 `InternalApiEndpoints.permitAllNoToken()` 也不在 `tokenProtected()`（`InternalApiEndpoints.java:31-71` 全表核过），落到 `SecurityConfig.java:127` 的 `anyRequest().authenticated()`；`hasAuthority("ADMIN")` 只对 POST/PUT/PATCH/DELETE 生效（`SecurityConfig.java:122-125`），本端点是 GET。
- **不需要** `X-Internal-Token`（那是 `/internal/**` 与 `tokenProtected()` 那批的规则）。

### 3.1 入参校验

`RequestJourneyController.java:21-22`：`instanceId` 空白或长度 > 64、`traceId` 空白或长度 > 128 → 抛 `ResponseStatusException(BAD_REQUEST)`。

### 3.2 成功响应（HTTP 200）

外层是 `ApiResponse` 包装（`{code, message, data, timestamp}`，`dto/ApiResponse.java:43-75`、工厂方法 `success(T)` 在 `:82`）。`data` 是 `LinkedHashMap`，键顺序固定为 `traceId` → `instanceId` → `trace` → `usage`（`RequestJourneyController.java:23-37`）：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "traceId": "6f1c2a90-...",
    "instanceId": "tj9j963zq2x1",
    "trace": {
      "id": 123,
      "traceId": "6f1c2a90-...",
      "instanceId": "tj9j963zq2x1",
      "routeId": "ai-chat",
      "method": "POST",
      "path": "/api/chat/completions",
      "statusCode": 200,
      "latencyMs": 812,
      "traceTime": "2026-10-29T01:36:50",
      "errorType": null,
      "errorMessage": null,
      "traceType": "DEBUG"
    },
    "usage": [
      {
        "id": 7,
        "eventId": "...",
        "quotaMonth": "202610",
        "quotaDay": "20261029",
        "quotaPeriod": "...",
        "quotaLedgerId": "...",
        "quotaLedgerState": "READY",
        "requestTime": "2026-10-29T01:36:50",
        "tenantId": "...",
        "routeId": "ai-chat",
        "model": "gpt-4o-mini",
        "responseFormat": "STREAM",
        "usageStatus": "CONFIRMED",
        "settlementStatus": "SETTLED",
        "provider": "...",
        "serviceId": "...",
        "chargedTokens": 128,
        "reservedTokens": 256,
        "promptTokens": 90,
        "completionTokens": 38,
        "totalTokens": 128,
        "latencyMs": 812,
        "traceId": "6f1c2a90-...",
        "instanceId": "tj9j963zq2x1"
      }
    ]
  },
  "timestamp": 1761673010000
}
```

字段来源与要点：

- `data.trace` 是 controller 里手写的 12 字段视图（`RequestJourneyController.java:28-35`），**不是 `RequestTrace` 实体本身**。实体里的 `uri`、`queryString`、`requestHeaders`、`requestBody`、`responseHeaders`、`responseBody`、`targetInstance`、`clientIp`、`userAgent`、`replayable`、`replayType`、`replayCount`、`lastReplayResult`、`createdAt` 一律**不返回**。要看完整追踪请走 `GET /api/traces/trace-id/{traceId}`（`RequestTraceController.java:216`）。
- 查不到追踪时 `data.trace` 是 `null`，**HTTP 状态仍是 200**，没有 404 分支。`ApiResponse` 类上的 `@JsonInclude(NON_NULL)`（`:43`）只作用于包装层自身属性（所以包装层的 `traceId` 为 null 时被省略），`data` 这个 Map 里的 null 值照常序列化。
- `data.usage` 每项的字段与顺序来自 `TokenUsageAnalyticsService.eventPage`（`:122-163`）。注意两条真实行为：`usageStatus`/`settlementStatus` 为 null 时兜底成字符串 `"LEGACY"`（`:138-139`）；`usageStatus` 为 `UNKNOWN` 或 `ESTIMATED` 时 `promptTokens`/`completionTokens`/`totalTokens` **被强制置 null**（`:147-151`），即"未知用量不当作 0"。
- `traceType` 除既有的 `ERROR`/`SLOW`/`ALL` 外，自 `3087aa2` 起多了一个 `DEBUG` 取值，只出现在验签通过的调试请求上（`TraceCaptureGlobalFilter.java:343-346`）。

### 3.3 错误响应

admin 侧错误体**不是** `ApiResponse`，而是 `GlobalExceptionHandler.buildErrorResponse` 拼的普通 `HashMap`，只有 `code`/`data`/`message` 三个键，**没有 `timestamp`**（`GlobalExceptionHandler.java:182-187`）：

| 场景 | HTTP | 处理位置 |
|---|---|---|
| 缺 `instanceId` query 参数 | 400 | `ServletRequestBindingException` 分支，`GlobalExceptionHandler.java:152-155` |
| `instanceId`/`traceId` 空白或超长 | 400 | `ResponseStatusException` 走 `ErrorResponse` 分支，`GlobalExceptionHandler.java:169-179` |
| 未登录 / Token 失效 | 401 | Spring Security 过滤链，不进 controller |
| Trace ID 不存在或实例不匹配 | **200** | 不是错误，`data.trace = null` |

### 3.4 curl 示例

```bash
curl -sS "http://127.0.0.1:9090/api/request-journeys/6f1c2a90-xxxx?instanceId=tj9j963zq2x1" \
  -H "Authorization: Bearer $ADMIN_JWT"
```

`ADMIN_JWT` 由 `POST /api/auth/login` 取得（该路径在 `InternalApiEndpoints.permitAllNoToken()` 白名单里）。GET 只需登录，不需 ADMIN 角色。

---

## 4. 网关侧：调试请求的强制留痕

这一节解释"为什么从路由调试页跳过来通常能查到追踪，而普通请求未必"。签名与验签的完整说明已在 [route-debug.md](route-debug.md)，此处只补与本特性直接相关的落点。

1. **签名**：`RouteDebugController.signDebugRequest`（`:133-147`）先 `removeHeader("X-Gateway-Debug-Trace")` 剥掉客户端可能自带的同名头（`:134`），再用 `gateway.admin.internal-token` 做 HMAC-SHA256，payload 为 `instanceId \n method \n path?query \n timestamp \n nonce`，头值格式 `timestamp:nonce:hex`（`:143-144`）。token 为空时直接返回去掉该头的请求（`:135`）。
2. **验签**：`my-gateway` 的 `DebugTraceVerifier.verify`（`:25-38`）。拒绝条件：secret 为空、签名缺失、签名长度 > 180、不是 3 段、nonce 不匹配 `[a-f0-9]{32}`、时间戳偏差超出 `-5000ms ~ +60000ms`；比较用 `MessageDigest.isEqual` 常量时间。
3. **打标与剥离**：`TraceIdGlobalFilter`（order = `Ordered.HIGHEST_PRECEDENCE`，`:202`）验签通过后写入 exchange 属性 `gateway.debug.trace.verified`（`:81-85`），并在转发前**移除该头**，不让控制面签名泄漏给上游（`:86-87`）。同时在 `beforeCommit` 里回写响应头 `X-Trace-Id` 与 `X-Gateway-Route-Id`（`:91-97`）——后者就是调试页判断"实际命中的路由"的依据（`gateway-ui/src/features/routes/debug/index.tsx:79`）。
4. **强制留痕**：`TraceCaptureGlobalFilter`（order `-47`，见 `FilterOrderConstants.TRACE_CAPTURE`）在三处认这个标记：
   - `:160-161` 把 `debug` 并入 `anyCaptureEnabled`，即使三个 capture 开关全关也会装响应体装饰器；
   - `:170` 跳过 `gateway.trace.body-sampling-rate` 抽样；
   - `:343-346` 无条件以 `traceType="DEBUG"` 落一条记录并 `return`，绕过 ERROR/SLOW/ALL 的抽样分支。
   
   **仍然受限**：`reservation == 0 || !reserveBodyBytes(reservation)` 这一条没有为 debug 开豁免（`:170-171`），所以 `gateway.trace.max-body-size` 为 0 或在途 body 预算耗尽时，调试请求照样拿不到 body。流式请求（WebSocket / SSE）在 `:159-165` 就更早退出了，**调试 SSE 路由不会产生带 body 的 DEBUG 追踪**。

---

## 5. 前端入口与交互

> 前端文件判断一律以 `git show HEAD:<path>` 为准（`gateway-ui/src/**` 有并行改动）。

### 5.1 路由与入口

页面路由 `/instances/:instanceId/trace/:traceId`，注册在 `gateway-ui/src/App.tsx:103`（嵌在 `/instances/:instanceId` 之下，`:97`），组件懒加载自 `./features/request-journey`（`App.tsx:34`）。可选 query `?routeId=` 表示"调试时选的那条路由"，用于和实际命中的路由做比对（`links.ts:1-2`、`index.tsx:24`）。

四个跳转入口，都走同一个 `requestJourneyUrl(instanceId, traceId, routeId)`：

| 入口 | 位置 |
|---|---|
| 路由调试页响应区的「查看本次请求」按钮（拿到 `x-trace-id` 响应头才出现） | `features/routes/debug/index.tsx:78,129` |
| 追踪详情抽屉底部按钮 | `features/trace/components/TraceDetailDrawer.tsx:194` |
| Token 计量页明细表的 Trace ID 单元格（渲染成链接） | `features/token-usage/TokenUsagePage.tsx:600` |
| Token 核对抽屉里的「查看本次请求」 | `features/token-usage/TokenUsageReconciliationDrawer.tsx:71` |

### 5.2 页面行为

- 数据获取：`getRequestJourney(instanceId, traceId)` → `GET /api/request-journeys/{traceId}?instanceId=...`（`api/requestJourney.ts:11-13`），返回类型 `{instanceId, traceId, trace: RequestTrace | null, usage: TokenUsageEvent[]}`（`:5-10`）。
- 另发一次 `getRouteById(routeId)`（`index.tsx:27`）用来判断 `route.category === 'AI'`（`:28`）；`routeId` 取"实际命中的路由"（`trace.routeId` 或 `usage[0].routeId`），退回 URL 上的 `routeId`（`:25-26`）。
- **短暂轮询**：最多 8 次、间隔 1200ms，且只在"追踪还没到，或者判定为 AI 路由但计量还没到"时触发；一旦请求出错就停止（`index.tsx:29-35`）。`instanceId`/`traceId` 变化时计数归零（`:30`）。
- 五步进度条：配置 / 调用 / 追踪 / 计量 / 排障（`index.tsx:48-54`），每步的完成判定是本地布尔，不是后端状态。
- 三个提示条：请求出错（`:55`）、实际命中路由 ≠ 调试所选路由（`:58-59`）、路由配置已不可用/被删（`:60`）。
- 四个跳转按钮：路由配置（`?highlight=<routeId>`）、返回调试（`/instances/{id}/routes/{routeId}/debug`）、追踪详情（打开 `TraceDetailDrawer`）、该路由用量（`/token-usage?instanceId=..&routeId=..`）（`index.tsx:62-65`）。
- 追踪卡片显示 routeId、`method path`、HTTP 状态（≥400 红色 Tag）、`latencyMs`、`errorType || errorMessage`（`index.tsx:70-76`）。
- 计量表格列：model、usageStatus、settlementStatus、promptTokens、completionTokens、totalTokens，以及打开 `TokenUsageReconciliationDrawer` 的操作列（`index.tsx:79-88`）；非 AI 路由的空表文案是 `journey.notAi`。
- 文案：中英双语，`gateway-ui/src/locales/{zh,en}/journey.json`，在 `i18n.ts` 注册。

### 5.3 排障判定规则

判定完全在前端，函数 `requestDiagnosis(status, usage, ai)`（`gateway-ui/src/features/request-journey/links.ts:7-18`），**自上而下短路匹配，顺序即优先级**：

| 顺序 | 条件 | 结果键 |
|---|---|---|
| 1 | `status` 为 401 或 403 | `authentication` |
| 2 | `status` 为 429 | `rateLimit` |
| 3 | `status` 为 404 | `routing` |
| 4 | `status >= 500` | `upstream` |
| 5 | `status >= 400`（其余 4xx） | `request` |
| 6 | 任一 usage 行 `settlementStatus` ∈ {`FAILED`, `PENDING`} | `settlement` |
| 7 | 任一 usage 行 `usageStatus` ∈ {`UNKNOWN`, `ESTIMATED`} | `usageUnknown` |
| 8 | `status === undefined` | 有 usage → `traceMissing`；无 → `pending` |
| 9 | `ai && usage.length === 0` | `usageMissing` |
| 10 | 其余 | `ok` |

`status` 为 `undefined` 等价于"没查到追踪"。HTTP 状态优先于计量状态，所以一个 429 的请求即使结算失败也只会显示 `rateLimit`。每个结果键对应 `journey.result.<key>` 与 `journey.advice.<key>` 两条文案。判定逻辑有单元测试覆盖：`gateway-ui/tests/requestJourney.test.mjs`。

---

## 6. 配置项

逐个核过 `application.yml` 与 `@Value` 兜底值；两者不一致时在表里都写出来。

| 配置键 | yml 位置与默认值 | 代码兜底 | 对本特性的作用 |
|---|---|---|---|
| `gateway.admin.internal-token`（网关侧键名） | **`my-gateway/src/main/resources/application.yml` 里没有这个键**（grep `internal-token` 零命中），只能靠环境变量 | `@Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}")` → 空串（`DebugTraceVerifier.java:19`、`RouteDebugController.java:33`） | 为空 ⇒ admin 不签名、网关验签恒 false ⇒ 调试请求退回常规抽样，页面上的 Trace ID 可能查不到记录 |
| `gateway.admin.security.internal-token`（admin 侧键名） | `gateway-admin/src/main/resources/application.yml:174-181`，`${GATEWAY_INTERNAL_TOKEN:}` | — | 为空 ⇒ admin **拒绝启动**（`InternalApiTokenFilter.java:57-62`）；网关侧 `/internal/**` 同样拒绝启动（`InternalApiAuthWebFilter`） |
| `gateway.instance-id` | `my-gateway/.../application.yml:185`，`${GATEWAY_INSTANCE_ID:gateway-1}` | 同上（`DebugTraceVerifier.java:20`） | 签名 payload 第一段；admin 签名用的 `instanceId` 必须与网关本项一致，否则验签失败 |
| `gateway.admin.url` | `my-gateway/.../application.yml:209-210`，`${GATEWAY_ADMIN_URL:http://127.0.0.1:9090}` | 同值（`TraceReportDispatcher.java:37`） | 追踪上报地址；不通则 `data.trace` 永远为 null |
| `gateway.trace.capture-errors` | `:177`，`${GATEWAY_TRACE_CAPTURE_ERRORS:true}` | `true`（`TraceCaptureGlobalFilter.java:73`） | 三个开关全 false 时普通请求不留痕；DEBUG 标记可绕过（`:161`） |
| `gateway.trace.capture-slow` | `:178`，`${GATEWAY_TRACE_CAPTURE_SLOW:true}` | `true`（`:76`） | 同上 |
| `gateway.trace.capture-all` | `:176`，`${GATEWAY_TRACE_CAPTURE_ALL:true}`（yml 注释写着 "set to false in production"） | **`false`**（`:86`） | 同上 |
| `gateway.trace.sampling-rate` | `:180`，`${GATEWAY_TRACE_SAMPLING_RATE:50}` | **`10`**（`:89`） | 仅影响 `ALL` 分支；DEBUG 请求不走 |
| `gateway.trace.sampling-rate-for-errors` | `:181`，`${GATEWAY_TRACE_SAMPLING_RATE_ERRORS:100}` | `100`（`:92`） | 仅影响 ERROR 分支；DEBUG 请求不走 |
| `gateway.trace.body-sampling-rate` | `:174`，`${GATEWAY_TRACE_BODY_SAMPLING_RATE:10}` | `10`（`:95`） | **DEBUG 请求跳过此项**（`:170`） |
| `gateway.trace.max-body-size` | `:182`，`${GATEWAY_TRACE_MAX_BODY_SIZE:65536}` | `65536`（`:82`） | body 预留额度 = `max(0, 该值) * 4`（`:169`）；为 0 时 DEBUG 请求也拿不到 body |
| `gateway.trace.max-inflight-body-bytes` | `:175`，`${GATEWAY_TRACE_MAX_INFLIGHT_BODY_BYTES:16777216}` | `16777216`（`:97`） | 在途 body 预算，耗尽则不抓 body（对 DEBUG 无豁免） |
| `gateway.trace.include-filter-chain` | `:183`，`${GATEWAY_TRACE_INCLUDE_FILTER_CHAIN:true}` | `true`（`:101`） | 决定上报里是否附过滤器链明细（`:502`）；**本端点不返回这些字段** |
| `gateway.trace.mask-sensitive-body` | **yml 无此键** | `true`（`:107`） | 上报前对 body / queryString 脱敏 |

`gateway.trace.slow-threshold-ms`（`:179`，默认 `${GATEWAY_TRACE_SLOW_THRESHOLD:3000}`）决定 SLOW 判定，与本端点无直接关系。

---

## 7. 当前不覆盖的部分 / 已知边界

以下是实现里确实没有的东西，不要当成已有能力：

1. **只有一个端点**。`RequestJourneyController` 里没有任何列表、批量、按路由/时间范围查询的 mapping，也没有写操作（无 POST/PUT/DELETE）。
2. **不返回请求体与响应体**。`data.trace` 是 12 字段的精简视图，`requestBody`/`responseBody`/`requestHeaders`/`responseHeaders`/`uri`/`queryString`/`clientIp`/`userAgent`/`targetInstance`/`replay*` 全部不在其中（`RequestJourneyController.java:28-35`）。
3. **不返回过滤器链明细**。上报侧算出了 `filterChainDurationMs`、`filterCount` 等（`TraceCaptureGlobalFilter.java:502` 起，`addFilterChainDetails`），但走的是 `/api/filter-executions/internal` 这条独立通道，本端点不查也不返回。
4. **不读 `distributed_trace`**。跨服务 span、`report_id`/`capture_mode`/`dropped_span_count`/`open_span_count`/`start_time_ms` 这些列与本端点无关。
5. **不分页、不截断 usage**。`findByInstanceIdAndTraceIdOrderByIdAsc` 返回全部匹配行；`eventPage` 算出的 `total`/`page`/`size` 被丢弃（`:37` 只取 `content`）。
6. **查不到不报 404**。追踪缺失或实例不匹配一律 HTTP 200 + `data.trace = null`。
7. **不校验实例是否存在**。`instanceId` 只做长度/空白校验，不查 `gateway_instance` 表；填一个不存在的实例 ID 会得到 200 + 空结果。
8. **排障判定在前端**。`requestDiagnosis` 是 TypeScript 函数，后端不参与；直接调 API 拿不到 `diagnosis` 字段。
9. **轮询上限 8 次 × 1.2 秒**。超过后不再自动刷新，只保留手动「刷新」按钮。
10. **SSE / WebSocket 调试请求拿不到 body**（`TraceCaptureGlobalFilter.java:159-165` 提前返回）。
11. **签名有效期 60 秒**（允许 5 秒时钟偏差，`DebugTraceVerifier.java:30-31`）。调试请求在网关排队超过这个窗口不会导致验签失败（验签发生在 `TraceIdGlobalFilter`，即请求刚进来时），但把签好名的 URL 复制出来重放会失败——签名还绑定了 method 与 path?query。

---

## 8. 相关文档

- [route-debug.md](route-debug.md) — 本特性的主要入口；调试请求的签名/验签细节
- [request-tracing.md](request-tracing.md) — `request_trace` 表的完整字段、列表查询端点与保留期清理
- [request-replay.md](request-replay.md) — 用捕获到的请求做重放
- [filter-chain-analysis.md](filter-chain-analysis.md) — 过滤器级执行统计
- [ai-usage-delivery.md](ai-usage-delivery.md) — `token_usage_history` 的可靠投递与 `eventId` 幂等
- [ai-usage-reconciliation.md](ai-usage-reconciliation.md) — `UNKNOWN`/`ESTIMATED`/`PENDING`/`FAILED` 的人工核对
- [traffic-topology.md](traffic-topology.md) / [historical-comparison.md](../design/historical-comparison.md) — 拓扑与环比视角
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — 过滤器 order 权威表与整体架构
