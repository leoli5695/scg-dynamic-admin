# AI 网关（数据面总览）

把本系统当 AI 网关（LLM 流量网关）用：**网关代理"你的用户"发来的 LLM 请求**，在转发前完成提供商路由、密钥注入、提示词装饰、内容审核、上下文限制、语义缓存、Token 配额预扣，在响应后完成用量解析、结算/退款、计量上报与可观测埋点。北向是 OpenAI 兼容端点，南向是多家大模型提供商。

本文是数据面的**总览与索引**。各个零件的深入细节在它链接出去的 5 份文档里，本文不重复。

---

## 1. 两个方向的 AI：数据面 vs 控制面

本仓库有两套完全独立的 AI 能力，名字都叫 "AI"，但**方向相反、代码不相交、配置不共用**。

| | 数据面（本文） | 控制面 |
| --- | --- | --- |
| 谁的 LLM 请求 | **你的用户**的推理请求，网关做代理 | **管理端自己**发起的分析请求，网关是被分析对象 |
| 运行进程 | `my-gateway`（8080） | `gateway-admin`（9090） |
| 代码位置 | `my-gateway/.../ai/`、`filter/ai/`、`filter/token/`、`parser/` | `gateway-admin/.../service/AiAnalysisService.java`、`AiCopilotService.java`、`tool/` |
| 提供商目录 | `LlmProvider` 枚举，6 个**小写** id：`openai` / `deepseek` / `qwen` / `zhipu` / `moonshot` / `anthropic`（`my-gateway/src/main/java/com/leoli/gateway/ai/LlmProvider.java:21-46`） | `AiAnalysisService.DEFAULT_BASE_URLS` / `PROVIDER_MODELS`，4 个**大写** id：`OPENAI` / `GEMINI` / `CLAUDE` / `BAILIAN`（`gateway-admin/src/main/java/com/leoli/gateway/admin/service/AiAnalysisService.java:107-132`） |
| 密钥存放 | `provider_credentials` 表，AES-GCM 密文，网关运行时经内部 API 取明文（`gateway-admin/src/main/resources/sql/provider-credentials.sql:4-18`） | `AiConfig` 实体的 `api_key` 列，管理端进程内直接用（`AiAnalysisService.java:321-335`） |
| HTTP 客户端 | 反应式 `WebClient`（`GatewayEmbeddingClient.java:33-41`、`AdminProviderKeyResolver.java:33-53`） | 阻塞 `RestTemplate`，读超时 5 分钟（`AiAnalysisService.java:94-104`） |
| 配置入口 | `POST /api/provider-credentials`、AI 服务、AI 路由、7 个 AI/Token 策略 | `POST /api/copilot/config` |
| 文档 | **本文** + [ai-content-security.md](ai-content-security.md) + [ai-quota-ledger.md](ai-quota-ledger.md) + [ai-usage-delivery.md](ai-usage-delivery.md) + [ai-usage-reconciliation.md](ai-usage-reconciliation.md) | [ai-analysis.md](ai-analysis.md) + [ai-copilot.md](ai-copilot.md) |

### ★ 两个方向不共用 provider 配置（实测结论）

**不共用，且没有任何代码级耦合。** 复核依据：

```bash
# gateway-admin 只在一条注释里提到 LlmProvider，无 import、无调用
git grep -ln "ProviderRegistry\|LlmProvider" -- gateway-admin/src/main/java
#   -> 只有 model/ServiceDefinition.java，命中在 :45 的注释里

# my-gateway 完全不引用控制面的 AiConfig / AiConfigRepository
git grep -ln "AiConfigRepository\|admin.model.AiConfig" -- my-gateway/src/main/java
#   -> No matches found
```

实际后果：

- 控制面能用的 `GEMINI` **在数据面没有对应 provider**，无法把 Gemini 配成 AI 网关的上游。
- 数据面能直连的 `deepseek` / `zhipu` / `moonshot` **在控制面没有独立条目**，控制面只能通过 `BAILIAN`（DashScope OpenAI 兼容端点）间接使用这些模型家族。
- 同一个 `qwen`：数据面的默认 base URL 是 `https://dashscope.aliyuncs.com/compatible-mode/v1`（`LlmProvider.java:29`），控制面的 `BAILIAN` 也是同一个地址（`AiAnalysisService.java:111`），但**两边各自存各自的密钥**，改一边不影响另一边。
- 给 AI 网关配好的密钥，AI 运维助手看不到，反之亦然。

---

## 2. 一次 LLM 请求穿过网关的完整路径

下表是**逐行核实**的执行顺序，来源 `my-gateway/src/main/java/com/leoli/gateway/constants/FilterOrderConstants.java`（它是 `interface`，常量省略了 `public static final`，用 `grep "static final int"` 搜不到）。复核命令：

```bash
grep -nE "^    int (AI_|TOKEN_|HYBRID_|MULTI_DIM_)" \
  my-gateway/src/main/java/com/leoli/gateway/constants/FilterOrderConstants.java
```

| order | 常量（行号） | 过滤器 | 请求阶段做什么 |
| --- | --- | --- | --- |
| `HIGHEST_PRECEDENCE` | `TRACE_ID` 常量值 -300 **仅为历史参考**（:75） | `TraceIdGlobalFilter` | `getOrder()` 实际返回 `Ordered.HIGHEST_PRECEDENCE`（`TraceIdGlobalFilter.java:182-186`），最先执行，打 Trace ID |
| -256 | `AUTHENTICATION`（:97） | `AuthenticationGlobalFilter` | JWT / API Key / Basic / HMAC / OAuth2。产出 `jwt_claims`、`api_key_metadata` 交换属性——**租户身份与语义缓存隔离都只认这两个来源** |
| -246 | `AI_SECURITY`（:163） | `AiSecurityGuardFilter` | 入站内容审核；若 `scanResponse=true`，同时安装响应装饰器做出站审核 |
| -245 | `HYBRID_RATE_LIMITER`（:169） | `HybridRateLimiterFilter` | Redis + 本地限流 |
| -244 | `MULTI_DIM_RATE_LIMITER`（:175） | `MultiDimRateLimiterFilter` | 按身份维度限流 |
| -243 | `AI_CONTEXT_LIMIT`（:185） | `AiContextLimitFilter` | 估算输入 token，超窗拒绝；放行时把请求体发布为 `cachedRequestBody` 属性，供配额预扣复用，避免二次 join |
| -242 | `AI_SEMANTIC_CACHE`（:207） | `AiSemanticCacheFilter` | 算 embedding → 查缓存。**命中即短路**：直接写回缓存的响应，不再往下走 |
| -241 | `TOKEN_QUOTA_PRE_CHECK`（:193） | `TokenQuotaPreCheckFilter` | 窗口速率检查 + 周期配额预扣；写 `token_quota_tenant_id`、`token_usage_start_ns`、冻结周期戳 |
| 10004 | `AI_PROMPT_TEMPLATE`（:333） | `AiPromptTemplateFilter` | 注入/覆盖 system prompt、前后插消息、`{{var}}` 替换、可选 model 覆盖；剥离控制字段 |
| 10005 | `AI_PROVIDER_ROUTING`（:345） | `AiProviderRoutingFilter` | 解析 provider → 取密钥 → 重写上游 URL / Host / Authorization → 重写请求体 |
| — | SCG `NettyRoutingFilter` | — | 真正发起上游调用 |
| -44 | `TOKEN_USAGE_COLLECTOR`（:261） | `TokenUsageCollectorFilter` | 响应装饰器捕获正文/ SSE 帧 → 解析 usage → 结算或退款 → 写 outbox 上报 |
| -43 | `AI_STATISTICS`（:272） | `AiStatisticsFilter` | 纯打点装饰器（只记时间戳、不缓冲）→ 输出指标、`ai_log`、span 标签 |

响应侧装饰器的嵌套关系（由 order 决定，越小越早安装、越靠内）：
`Netty → AiStatistics(-43) → TokenUsageCollector(-44) → AiSemanticCache 捕获(-242) → AiSecurityGuard(-246) → 客户端`。
所以用量采集器读到的是**未经出站审核改写**的原始上游正文（`usage` 字段完整），而语义缓存存的也是审核前的正文——命中时缓存内容会**重新经过一次出站审核**，因为命中路径写的是 `AiSecurityGuardFilter` 装好的那个响应对象（`AiSecurityGuardFilter.java:56-58` 把 `guarded` 传下去，`AiSemanticCacheFilter.java:259-267` 往它上面写）。

### 为什么 `AI_PROMPT_TEMPLATE` / `AI_PROVIDER_ROUTING` 是 10004/10005 这种大正值

这不是随手挑的数字，而是被 SCG 内置过滤器的 order 夹出来的：

1. **必须在 `RouteToRequestUrlFilter`(10000) 之后**。该内置过滤器根据路由的 `uri` 写出 `GATEWAY_REQUEST_URL_ATTR`。AI 路由的 `uri` 是占位符 `https://ai-gateway.invalid`（`AiRouteProjector.java:39,63`），真正的上游地址由 `AiProviderRoutingFilter` **覆盖同一个属性**（`AiProviderRoutingFilter.java:181-182`）。写在 10000 之前就会被内置过滤器覆盖回去。
2. **必须在 `NettyRoutingFilter` 之前**，否则上游调用已经发出。
3. **必须在 lb:// 解析过滤器之前**：`NACOS_DISCOVERY_LOAD_BALANCER`(10100) 与 `DISCOVERY_LOAD_BALANCER`(10140) 只处理 `lb://`，而 AI 上游是绝对 `https://` URL，所以它们会跳过（`FilterOrderConstants.java:336-345`）。
4. **模板必须在路由之前**（10004 < 10005），因为模板可以覆盖 `model`，而路由是按 `model` 选 provider 的（`AiPromptTemplateFilter.java:42-55`、`AiProviderRoutingFilter.java:114-126`）。

**对使用者的实际影响**：审核、限流、上下文限制、语义缓存、配额预扣全部发生在**请求体被装饰之前**。也就是说：

- `AI_CONTEXT_LIMIT`(-243) 估算的是**客户端原始**输入 token，不含模板注入的 system prompt。若模板注入很长的合规提示词，实际发给上游的 token 会大于上下文限制器算出来的值。
- `AI_SECURITY`(-246) 审核的也是**客户端原始**请求体，模板注入的内容不经过入站审核（它由管理员配置，视为可信）。
- `TOKEN_QUOTA_PRE_CHECK`(-241) 预扣的估算值同样基于装饰前的请求体。
- 语义缓存的 embedding 也是**装饰前**的 prompt。两个只在模板变量上不同的请求，会算出同一个向量、命中同一条缓存。

### 拒绝点与真实响应体

网关侧错误体**没有统一形态**。事实基线 §12.3 说的 `{httpStatus,code,error,message,data}` 是 `GatewayResponseHelper.writeErrorResponse`（`GatewayResponseHelper.java:60-73`）的形态，AI 过滤器里有 4 种不同写法：

| 拒绝点 | HTTP | 响应体 | 代码 |
| --- | --- | --- | --- |
| 入站内容违规（`inboundAction=BLOCK`） | 403 | `{"httpStatus":403,"code":"CONTENT_BLOCKED","message":"<blockMessage>"}` | `AiSecurityGuardFilter.java:71-74,168-177` |
| 外部审核服务不可用（请求侧） | 503 | 同上，`code` = `"MODERATION_UNAVAILABLE"` | `AiSecurityGuardFilter.java:72-74` |
| 请求体超审核上限 | 413 | 同上，`code` = `"BODY_TOO_LARGE"` | `AiSecurityGuardFilter.java:60-61` |
| 请求不是可审核 JSON | 400 | 同上，`code` = `"INVALID_JSON"` | `AiSecurityGuardFilter.java:66-67` |
| 出站内容违规 / 流式被 BLOCK / 压缩响应 / 审核失败 | 502 | 同上，`code` 分别为 `CONTENT_BLOCKED` / `STREAM_BLOCKED` / `ENCODED_RESPONSE` / `RESPONSE_AUDIT_FAILED` | `AiSecurityGuardFilter.java:99-119,129-131` |
| 上下文超窗 | `errorStatusCode`（默认 400） | **OpenAI 风格**：`{"error":{"message":"This model's maximum context length is N tokens. Your request had approximately M tokens.","type":"invalid_request_error","code":"context_length_exceeded"}}` | `AiContextLimitFilter.java:121-143` |
| 上下文/缓存/模板过滤器请求体超限 | 413 | **空响应体**（`response.setComplete()`） | `AiContextLimitFilter.java:107-109`、`AiSemanticCacheFilter.java:183-184`、`AiPromptTemplateFilter.java:114-115` |
| 周期配额超限（账本模式） | 429 | `{"httpStatus":429,"code":52902,"error":"Too Many Requests","message":"Token quota exceeded","data":null}` | `TokenQuotaPreCheckFilter.java:313` → `GatewayResponseHelper.java:60-73` |
| 周期配额超限（旧模式，无账本） | 429 | `{"httpStatus":429,"code":52902,"error":"Token Quota Exceeded","message":"<原因>","data":null,"tenantId":"...","exceededType":"DAILY\|MONTHLY"}`，另带响应头 `X-Token-Quota-Exceeded-Type` | `TokenQuotaPreCheckFilter.java:522-553` |
| Token 窗口速率超限 | `rejectedCode`（默认 429） | `{"httpStatus":...,"code":52903,"error":"Token Window Rate Limited","message":"<rejectedMsg>","data":null,"tenantId":"...","exceededType":"WINDOW"}` | `TokenQuotaPreCheckFilter.java:634-663` |
| 配额账本不可用 | 503 | `{"httpStatus":503,"code":50302,"error":"Service Unavailable","message":"Token quota accounting is temporarily unavailable","data":null}` | `TokenQuotaPreCheckFilter.java:323-324` |
| 配额估算请求体超限 | 413 | `{"httpStatus":413,"code":41301,"error":"Payload Too Large","message":"请求体超过 Token 配额估算上限","data":null}` | `TokenQuotaPreCheckFilter.java:183-202` |
| 模板装饰失败且 `onError=REJECT` | 500 | `{"httpStatus":500,"code":50002,"error":"Internal Server Error","message":"Prompt 模板装饰失败，已按 onError=REJECT 策略拒绝请求","data":null}` | `AiPromptTemplateFilter.java:96-103,253-271` |
| 模型无法解析到 provider | 400 | `{"httpStatus":400,"code":40001,"error":"Bad Request","message":"无法解析模型对应的 Provider: <model>","data":null}` | `AiProviderRoutingFilter.java:133-134,482-500` |
| provider 已知但无凭据 | 500 | `{"httpStatus":500,"code":50001,"error":"Internal Server Error","message":"Provider 未配置凭据: <model>","data":null}` | `AiProviderRoutingFilter.java:131-132` |
| AI 代理请求体超 `maxBodySize` | 413 | `{"httpStatus":413,"code":41301,"error":"Payload Too Large","message":"请求体超过 AI 代理上限","data":null}` | `AiProviderRoutingFilter.java:146-149` |
| 所有 failover 目标的密钥都取不到 | 502 | `{"httpStatus":502,"code":50201,"error":"Bad Gateway","message":"无法获取任何 Provider 密钥（已尝试 N 个目标）","data":null}` | `AiProviderRoutingFilter.java:164-167` |
| 计量持久化不可用（请求未发往上游） | 503 | `{"httpStatus":503,"code":50303,"error":"Service Unavailable","message":"AI usage persistence is temporarily unavailable; request was not sent upstream","data":null}` | `TokenUsageCollectorFilter.java:323-347` |
| 上游声明的 `Content-Length` 小于实际发送量（响应已损坏） | 502 | `{"code":502,"message":"Response too large for token usage collection"}` — **只有 2 个键，与其它网关错误体都不同** | `TokenUsageCollectorFilter.java:216-227` |

★ **响应体超过 `gateway.token-usage.max-body-size` 不再是拒绝理由。** 以前 chunked 响应（无
`Content-Length`）会走 `DataBufferUtils.join`，超限时 join 已释放它消费过的缓冲区、正文不可恢复，
于是只能返回 502 并**丢掉上游已经完整生成的答复**——而 chunked 是 LLM provider 和中间代理的常态。
现在只有"上游声明了长度且在限内"才聚合（这也是唯一能在响应提交前写 `X-Token-Usage-*` 的情形）；
长度未知或已知超限的响应一律**原样流式放行**，同时做一份有界旁路拷贝用于解析用量。代价是这类响应
拿不到 `X-Token-Usage-*` 头；换来的是**计量永远不会毁掉一次成功的推理**。超限被截断时用量解析不出来，
结算记为 `UNKNOWN`（保留预扣待人工核对），不会静默按 0 计费。

⚠️ `AI_SECURITY` 的 `code` 是**字符串**（`"CONTENT_BLOCKED"`），其余是**整数**；且它的响应体只有 3 个键，没有 `error` 和 `data`。客户端不要写统一的错误解析逻辑。

### 放行时的响应头

| 头 | 来源 |
| --- | --- |
| `X-Token-Quota-Limit-Monthly` / `-Remaining-Monthly` / `-Used-Monthly` / `-Limit-Daily` / `-Remaining-Daily` / `-Used-Daily` | `TokenQuotaPreCheckFilter.java:316-321`（账本模式，`set`）、`:500-517`（旧模式，`add`） |
| `X-Token-Usage-Prompt` / `-Completion` / `-Total`、`X-Token-Pre-Deducted`、`X-Token-Stream-Interrupted` | `TokenUsageCollectorFilter.java:730-743`，**仅非流式 JSON 响应**（流式响应已提交，加不进去，见 `:604-608`） |
| `X-AI-Cache: HIT` / `MISS` | `AiSemanticCacheFilter.java:65,176,263` |
| `X-AI-Security-Request` / `X-AI-Security-Response` | 命中类别列表，如 `BANNED_WORD,EMAIL`；不含正文与原词（`AiSecurityGuardFilter.java:39-40,164-166`） |

---

## 3. 提供商路由与密钥池

### 3.1 内置 provider 清单

`LlmProvider` 枚举**共 6 项**（钉在 commit `a2ac1ae`；复核：`grep -cE '^    [A-Z]+\("' my-gateway/src/main/java/com/leoli/gateway/ai/LlmProvider.java`）。全部为 `Protocol.OPENAI_COMPAT` + `AuthScheme.BEARER`，聊天路径全部是 `/chat/completions`（`LlmProvider.java:21-46`）：

| id | 显示名 | 默认 base URL | 拼出的聊天地址 | 模型名前缀（用于自动识别） |
| --- | --- | --- | --- | --- |
| `openai` | OpenAI | `https://api.openai.com/v1` | `.../v1/chat/completions` | `gpt-`, `o1`, `o3`, `o4`, `chatgpt-` |
| `deepseek` | DeepSeek | `https://api.deepseek.com` | `https://api.deepseek.com/chat/completions`（**没有 `/v1`**） | `deepseek-` |
| `qwen` | 通义千问/百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `.../compatible-mode/v1/chat/completions` | `qwen`, `qwq` |
| `zhipu` | 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `.../api/paas/v4/chat/completions` | `glm-`, `chatglm`, `cogview`, `cogvideo` |
| `moonshot` | 月之暗面/Kimi | `https://api.moonshot.cn/v1` | `.../v1/chat/completions` | `kimi-`, `moonshot-` |
| `anthropic` | Anthropic Claude | `https://api.anthropic.com/v1` | `.../v1/chat/completions`（**Anthropic 的 OpenAI 兼容端点，不是原生 `/v1/messages`**） | `claude-` |

URL 拼接见 `LlmProvider.resolveChatUrl` / `resolveEmbeddingsUrl`（`:71-87`）。每项都可被策略里的 `providerBaseUrlOverride`（或 AI 服务的 `baseUrl`）覆盖，用于中继、出口代理、自托管 OpenAI 兼容后端。

`Protocol.ANTHROPIC`、`Protocol.CUSTOM`、`AuthScheme.X_API_KEY`、`AuthScheme.CUSTOM` 这几个枚举值**没有任何 provider 使用**，也没有对应的转换实现（`LlmProvider.java:114-132`）。`AiProviderRoutingFilter.java:399-403` 里 `X_API_KEY` 那条分支在当前枚举下不可达。

### 3.2 provider 是怎么选出来的

`AiProviderRoutingFilter.resolveProvider`（`:208-228`）按 `AiProxyConfig.providerSelection` 分三种模式：

| 模式 | 依据 |
| --- | --- |
| `MODEL`（默认） | 先查 `modelRouting[<请求 model>]` 显式映射；没有再用 `ProviderRegistry.resolveByModel(model)` 按**枚举声明顺序**做前缀匹配（`ProviderRegistry.java:44-58`） |
| `FIXED` | 恒用 `fixedProvider` |
| `HEADER` | 读请求头，头名由 `providerHeader` 决定，默认 `X-Provider` |

请求体里没有 `model` 时用 `defaultModel` 兜底（`:114-118`）。`modelMapping` 再把"客户端 model 名"改写成"上游 model 名"（`:120-123`，`rewriteUpstreamBody` `:433-461`）——只有两者不同才会重新序列化请求体。

### 3.3 目标列表、权重与 failover

`buildTargets`（`:254-316`）产出一个**有序目标列表**：

1. **配了 `upstreams`（AI 路由的常规形态）**：过滤出 `weight > 0` 且 `credentialRef` 非空且 `ProviderRegistry.byId(provider)` 能解析的条目，用**平滑加权轮转**选一个（`selectService` `:319-334`，CAS 无锁，状态按"路由 + 候选集"缓存 10000 条），选中的排第一，其余按原顺序排在后面作为同请求内的备选。列表被钉在交换属性 `ai.routing.targets` 上，**重试不会重新做负载均衡**（`:256-259,312-314`）。
2. **没配 `upstreams`（旧形态）**：用 `ProviderKeyPoolSelector.selectRef` 从密钥池选一个 ref。
3. **`fallbacks` 追加在最后**：每个 `FallbackTarget` 自带 `provider` / `model` / `credentialRef` / `baseUrlOverride` / `serviceId`；`credentialRef` 省略时回落到该 provider 的密钥池；provider 解析不出或 ref 为空的条目**静默跳过**。

两种 failover 触发方式（`AiProxyConfig.java:108-123`、`AiProviderRoutingFilter.java:152-206`）：

- **密钥取不到**（被禁用/删除/内部 API 失败）：**本次调用内立即**跳到下一个目标，不需要 RETRY 策略。全部耗尽 → 502 `code=50201`。
- **上游返回瞬时错误**（429/5xx）：靠路由绑定的 **RETRY 策略**重入本过滤器，`ai.target.attempt` 属性递增，从而落到下一个目标（`:139-144,239-247`）。`maxAttempts` 需设为 `1 + fallbacks.size()`。流式响应**只能在第一个字节之前** failover。

### 3.4 密钥从哪来

**存放**：`provider_credentials` 表（`gateway-admin/src/main/resources/sql/provider-credentials.sql`）。`encrypted_key` 是 AES-GCM 密文 `base64(iv || ciphertext)`，`key_hint` 是 UI 用的掩码尾串，`credential_ref` 有唯一索引。策略与服务配置里只出现 `credentialRef`，**从不出现明文**。

**管理端 CRUD**：`/api/provider-credentials`（`ProviderCredentialController.java:26`）。所有响应都套 `ApiResponse{code,message,data,traceId,timestamp}`（`gateway-admin/.../dto/ApiResponse.java:55-75`）。

| 方法 | 路径 | 参数 | 鉴权 | data |
| --- | --- | --- | --- | --- |
| `GET` | `/api/provider-credentials` | — | 已登录（`SecurityConfig.java:127 anyRequest().authenticated()`） | 掩码视图数组 |
| `GET` | `/api/provider-credentials/{ref}` | path `ref` | 已登录 | 单条掩码视图；不存在返回 404 |
| `POST` | `/api/provider-credentials` | body `ProviderCredentialRequest` | **ADMIN**（`SecurityConfig.java:122`） | 掩码视图 |
| `PUT` | `/api/provider-credentials/{ref}` | path `ref` + body | **ADMIN** | 掩码视图 |
| `DELETE` | `/api/provider-credentials/{ref}` | path `ref` | **ADMIN** | `null` |
| `GET` | `/api/provider-credentials/internal/{ref}/key` | path `ref` | **`X-Internal-Token`**，不走 JWT（`InternalApiEndpoints.java:45` 在 `tokenProtected()` 名单里） | `{"credentialRef":"...","apiKey":"<明文>"}` |

掩码视图字段（`ProviderCredentialService.java:131-142`）：`credentialRef`、`name`、`provider`、`keyHint`、`tenantId`、`enabled`、`createdAt`、`updatedAt`——**没有 `encryptedKey`，没有明文**。

`ProviderCredentialRequest` 字段（`gateway-admin/.../dto/ProviderCredentialRequest.java:13-29`）：`credentialRef`（可选，创建时省略则生成 UUID）、`name`（必填）、`provider`（必填）、`apiKey`（创建时必填；更新时留空表示"不改密钥"）、`tenantId`、`enabled`。

```bash
curl -X POST http://localhost:9090/api/provider-credentials \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{"credentialRef":"ds-prod-1","name":"DeepSeek 生产","provider":"deepseek","apiKey":"sk-..."}'
```

**创建 AI 服务时会自动建密钥**：`POST /api/services` 的 body 里带 `category:"AI"` + `providerType` + 明文 `apiKey`，`ServiceService.applyAiCredential`（`:629-660`）会把它加密写入 `provider_credentials`，ref 固定为 `svc-<serviceId>`，然后**把定义上的明文置 null**，只在服务元数据里保留 `credentialRef`。已有 ref 时同样传 `apiKey` 表示**轮转**。

**网关取密钥**：`AdminProviderKeyResolver`（`my-gateway/.../ai/AdminProviderKeyResolver.java`）

- 用共享 `WebClient` 调 `GET {gateway.admin.url}/api/provider-credentials/internal/{ref}/key`，带 `X-Internal-Token`（`:78-81`）。
- `gateway.admin.internal-token` 未配置时**不报错、只 WARN，返回空** → 路由过滤器判为"密钥取不到"并 failover（`:73-76`）。
- Caffeine 本地缓存：最多 1000 条、写后过期 `gateway.ai.key-cache-ttl-seconds`（默认 **300 秒**）。缓存在 `@PostConstruct init()` 里构建，因为 `@Value` 字段在字段初始化期还是 0（`:44-62`）。有 `invalidate(ref)` 方法但**没有任何调用点**——轮转密钥后最长 300 秒内网关仍可能用旧密钥。
- ref 会被 URL 编码后拼进路径，防止 `/` 或 `?` 让它打到别的内部端点（`:92-100`）。
- 被禁用（`enabled=false`）的凭据在管理端就抛异常（`ProviderCredentialService.java:118-124`），网关侧表现为解析失败。

### 3.5 密钥池选择策略

`ProviderKeyPoolSelector`（`my-gateway/.../ai/ProviderKeyPoolSelector.java`）——Higress `ai-load-balancer` 的凭据级对应物。配置形态：`AiProxyConfig.providerKeyPools = { "<providerId>": { "credentialRefs": [...], "strategy": "ROUND_ROBIN" } }`（`AiProxyConfig.java:125-139`）。

| 策略 | 行为 | 代码 |
| --- | --- | --- |
| `ROUND_ROBIN`（默认） | 本机 `AtomicLong` 自增取模轮转 | `:57-58` |
| `LEAST_INFLIGHT` | 选**本网关实例**上在途请求数最少的 ref，同数时取列表靠前者；`acquire`/`release` 在路由前后计数 | `:52-56,62-84` |

**没有池时的回落顺序**：`providerKeyPools[provider].credentialRefs` → 只有 1 个则直接用 → 池不存在则用旧字段 `providerApiKeyRefs[provider]` → 都没有返回 null（`:42-59`）。

⚠️ `LEAST_INFLIGHT` 是**单实例本地计数**，不是跨副本的全局最少请求（Higress 的 `global_least_request` 是 Redis 支撑的）。这是刻意的取舍：路由热路径上不做阻塞往返（`:19-25` 的 javadoc 明确说明）。前缀缓存与端点指标策略**未移植**。

⚠️ 通过控制台"AI 路由"界面创建的策略只写 `upstreams`（每个 upstream 一个 `credentialRef`），**不会写 `providerKeyPools`**。密钥池目前只能通过策略 API 直接配 `AI_PROXY` 的 config 来启用。

### 3.6 密钥会不会回传给客户端 / 写进日志

三条独立证据，结论是**不会**：

1. **不回传客户端**：明文只写进转发给上游的请求头（`Authorization: Bearer <key>` 或 `x-api-key: <key>`，`AiProviderRoutingFilter.java:399-403`）。默认 `stripClientAuth=true`（`AiProxyConfig.java:100-101`）会先摘掉客户端自己的 `Authorization` 与 `x-api-key`。响应路径上没有任何写密钥的代码。
2. **不进访问日志**：访问日志在 -400 采集，早于 10005 的注入；且注入用的是 `exchange.mutate().request(...)` 产生的**子交换**，父交换看不到（`:191-196,382-412`）。即便看到，`SensitiveDataSanitizer` 的敏感头名单包含 `authorization` 与 `x-api-key`，一律替换为 `***MASKED***`（`SensitiveDataSanitizer.java:37-46,59-73`），访问日志的 `requestHeaders` / `responseHeaders` 都经过它（`AccessLogGlobalFilter.java:549-564`）。
3. **不进应用日志**：路由日志只打 `route / model / provider / service / upstream / attempt`（`AiProviderRoutingFilter.java:193-194`）；取密钥失败只打 ref 和异常 message（`AdminProviderKeyResolver.java:74,87`）；管理端的密钥解析异常也刻意不向外泄露 crypto 细节（`ProviderCredentialController.java:106-110`）。审计日志只记 `provider=<类型>` 和 `rotated=true|false`（`ProviderCredentialService.java:74-75,100-101`）。

`provider_credentials.provider` 列只是元数据：`resolveKey` 不校验它与路由解析出的 provider 是否一致，路由也不读它。写成 `custom` 之类不在 `LlmProvider` 里的值不影响取密钥，但**路由侧的 provider id 必须是 6 个之一**，否则 `ProviderRegistry.byId` 返回空、该 upstream 被过滤掉。

### 3.7 北向模型发现端点

`GET /v1/models`（`my-gateway/.../controller/AiModelsEndpoint.java:32,43`）是网关进程上的原生 WebFlux 控制器，返回 OpenAI 形状：

```json
{"object":"list","data":[{"id":"<model>","object":"model","created":1730000000,"owned_by":"deepseek"}]}
```

模型清单来自所有**已启用**的 `AI_PROXY` 策略：`upstreams[].modelMapping` 的键、`modelRouting` 的键、`modelMapping` 的键、`defaultModel`；`owned_by` 取 provider id，解析不出则为 `"custom"`（`:48-107`）。**不暴露任何凭据**。

⚠️ 该端点**无鉴权**：`my-gateway` 只依赖 `spring-security-crypto`（`my-gateway/pom.xml:69-73`），没有 Web 安全自动配置；进程里只有两个 `WebFilter`——`ActuatorEndpointFilter` 和只守 `/internal/**` 的 `InternalApiAuthWebFilter`。`AuthenticationGlobalFilter` 是 `GlobalFilter`，只作用于**被路由的请求**，管不到原生控制器。所以暴露在公网的网关，任何人都能列出你的模型清单。

---

## 4. Token 配额与计量：端到端链路全貌

链路细节已由 3 份文档覆盖，本节只画全貌并指路。

```
┌─ 请求阶段 ────────────────────────────────────────────────────────────┐
│ AI_CONTEXT_LIMIT(-243)  估算输入 token，发布 cachedRequestBody          │
│          ↓                                                            │
│ AI_SEMANTIC_CACHE(-242) 命中 → 短路返回，不预扣、不上报、不出指标         │
│          ↓ 未命中                                                      │
│ TOKEN_QUOTA_PRE_CHECK(-241)                                           │
│   ① 窗口速率检查（global / tenant，按分钟·小时桶）  超限 → 429 code 52903│
│   ② 租户身份：api_key_metadata → jwt_claims →（显式配置才用）header      │
│   ③ 预扣策略 NONE / ESTIMATE / HYBRID                                  │
│      ESTIMATE|HYBRID：预扣 = 估算 × estimateMultiplier                  │
│   ④ 账本模式：写 PREPARING → Redis Lua 检查+预扣 → 写 ADMITTED          │
│      额度不足 → 429 code 52902；账本不可用 → 503 code 50302             │
│   ⑤ 冻结周期戳（跨日/月退款仍落回原桶）、记录 start_ns                    │
└───────────────────────────────────────────────────────────────────────┘
                              ↓ 上游调用
┌─ 响应阶段 ────────────────────────────────────────────────────────────┐
│ TOKEN_USAGE_COLLECTOR(-44)                                            │
│   ① 按上游 Content-Type 判定 JSON / SSE（在写响应时判，不看 Accept）      │
│   ② JSON：仅当上游声明了 Content-Length 且不超限时才整包 join          │
│      （这是唯一能在提交前写 X-Token-Usage-* 的情形）；长度未知或超限    │
│      则原样流式放行 + 有界旁路拷贝解析 usage，超限截断记 UNKNOWN，      │
│      绝不因为计量失败而 502 丢掉上游已生成的答复                        │
│      SSE：跨 DataBuffer 重组帧 → SseUsageAccumulator 取最大快照          │
│   ③ 判定中断：CANCEL / ON_ERROR / SSE 缺 [DONE]（收到 [DONE] 后客户端断开 │
│      算正常完成，不触发保底计费）                                        │
│   ④ 产出 AiUsageResult{status, input, output, total, charged,           │
│      reserved, interrupted}，status ∈ CONFIRMED|ESTIMATED|UNKNOWN|      │
│      NOT_BILLED，发布到交换属性 ai.usage.result                          │
│   ⑤ 结算：账本 complete() / settleTokenUsage() / consumeTokens()；       │
│      UNKNOWN 时**保留预扣不退款**，等人工核对                             │
│   ⑥ 把已结算用量计入窗口桶                                              │
│   ⑦ 写本地 outbox → 批量上报管理端                                       │
│   ⑧ 无配额策略/无租户身份的 AI 请求走"仅计量"：不扣配额，但照常上报         │
└───────────────────────────────────────────────────────────────────────┘
                              ↓
┌─ 管理端 ──────────────────────────────────────────────────────────────┐
│ POST /api/token-quota/internal/record-usage/batch（X-Internal-Token）   │
│   event_id 唯一索引去重 → 落 token_usage_history                        │
│   UNKNOWN/ESTIMATED 用量、PENDING/FAILED 结算 → 人工核对与补偿           │
└───────────────────────────────────────────────────────────────────────┘
```

| 环节 | 文档 |
| --- | --- |
| 预扣 / 结算 / 退款 / 本地降级 / 账本恢复 | [ai-quota-ledger.md](ai-quota-ledger.md) |
| outbox 落盘、批量投递、`eventId` 去重、退避 | [ai-usage-delivery.md](ai-usage-delivery.md) |
| `UNKNOWN`/`ESTIMATED` 用量与 `PENDING`/`FAILED` 结算的人工核对与补偿 | [ai-usage-reconciliation.md](ai-usage-reconciliation.md) |

**用量解析器对不同 provider / 流式的处理差异**（`my-gateway/.../parser/TokenUsageParser.java`）——这是链路里最容易踩坑的一环：

| 输入 | 解析行为 | 代码 |
| --- | --- | --- |
| `format=OPENAI` | 读 `usage.prompt_tokens` / `completion_tokens`；若存在 `usage.input_tokens` / `output_tokens`（Responses API 命名）则改读它们；`total_tokens` 缺失时用两者相加 | `:101-134` |
| `format=ANTHROPIC` | 输入 = `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`，输出 = `output_tokens` | `:139-165` |
| `format=CUSTOM` | 按 `customFieldMapping` 里的 `promptTokens` / `completionTokens` 字段名读；映射为空直接返回空用量 | `:170-195` |
| `format` 是其它值 | 自动探测：有 `cache_read_input_tokens` / `cache_creation_input_tokens` 或 `type=="message"` 走 Anthropic，否则先试 OpenAI 再试 Anthropic | `:79-91` |
| SSE，OpenAI 兼容 | `usage` 为 null 的分片跳过；`type` 以 `message_` 开头走 Anthropic 分支 | `:215-262` |
| SSE，Anthropic 原生 | `message_start` 取 `message.usage`，`message_delta` 取 `usage`；**`output_tokens` 是累计值不是增量**，四个字段逐个覆盖到累积节点上再解析 | `:300-328` |
| SSE 聚合 | 保留 `totalTokens` 最大的那份快照，`format` 标为 `SSE_AGGREGATED`；`[DONE]` / `message_stop` / `response.completed` 置 `complete` | `:321-331` |

⚠️ **AI 代理路由上，自动探测分支永远走不到**：`AiProviderRoutingFilter.java:188-189` 总会写入 `ai.response_format`，缺省值 `"OPENAI"`；`TokenUsageCollectorFilter.processJsonUsage`（`:845-856`）让该覆盖值优先于策略里的静态 `responseFormat`。所以 Anthropic 的 `cache_creation_input_tokens` / `cache_read_input_tokens` **只有把策略的 `responseFormat` 显式设成 `ANTHROPIC` 才会计入**，否则缓存读写 token 不进账单。

⚠️ 非数字 / 负数 / 超出 int 范围的 token 值会抛 `IllegalArgumentException`（`:197-204`），被上层 catch 后整个响应判为空用量 → `UNKNOWN`。

⚠️ **只有 `Protocol.OPENAI_COMPAT` 且请求体含字面子串 `"stream"` 且 `stream` 为布尔 true 时**，路由器才会替客户端补 `stream_options.include_usage=true`（`AiProviderRoutingFilter.java:433-480`）。上游不支持该选项时，流式响应拿不到 usage → 预扣全额保留为 `UNKNOWN`。

---

## 5. 语义缓存

策略类型 `AI_SEMANTIC_CACHE`，过滤器 `AiSemanticCacheFilter`（order -242），对标 Higress `ai-cache`。

### 5.1 embedding 怎么算

`GatewayEmbeddingClient`（`my-gateway/.../ai/cache/GatewayEmbeddingClient.java`）——**不用任何本地模型**，而是调一个 OpenAI 兼容的 `/embeddings` HTTP 端点：

- URL = `ProviderRegistry.byId(embeddingProvider).resolveEmbeddingsUrl(embeddingBaseUrlOverride)`，即 `<base>/embeddings`（`:56`，`LlmProvider.java:82-87`）。
- 请求体 `{"model":"<embeddingModel>","input":["<prompt 文本>"],"encoding_format":"float"}`，头 `Authorization: Bearer <key>`（`:57-64`）。
- 密钥走**同一套** `ProviderKeyResolver` / `provider_credentials`，ref 由 `embeddingCredentialRef` 指定（`:37-41,54`）。
- 硬超时 **800 毫秒**（`:31,75`）。刻意远低于 `WebClientConfig` 的 30 秒客户端默认值——这个调用在每个查缓存的 AI 请求路径上，挂住的 embedding 端点会变成每请求 30 秒停顿。
- 任何失败（缺 provider / 缺 ref / 密钥取不到 / 超时 / 解析不出向量）一律返回空 → **fail open，退化为缓存未命中**（`:48-53,78-81`）。

被向量化的文本 = 请求体 `messages[]` 中所有**字符串型** `content`，按 `role:content\n` 拼接后 trim（`AiSemanticCacheFilter.java:189-202`）。数组型/多模态 content 块被跳过；结果为空则 fail open。

### 5.2 存储、容量与淘汰

`SemanticCacheStore`（`my-gateway/.../ai/cache/SemanticCacheStore.java`）：

- **进程内内存态、单实例、不持久化**（`:12-21` 的 javadoc 明确写了"共享存储（Redis-Stack / 外部向量库）是后续工作"）。
- 16 个分片，按 `scope.hashCode()` 取模；检索只持有所在分片的锁（`:32-38,42-63`）。
- 暴力余弦相似度，向量先做 L2 归一化，所以点积即余弦（`:42-63,100-120`）。
- 淘汰：分片内 LRU（命中项移到队尾 `:57-60`；写入时容量不足则从队首逐出 `:74-89`）+ 条目 TTL（读时惰性清理过期项 `:52`）。
- 双预算：条目数 `gateway.ai.cache.max-entries`（默认 **1000**）与字节数 `gateway.ai.cache.max-bytes`（默认 **33554432** = 32 MiB）。单条估算字节 = `128 + 2×scope长 + 2×响应长 + 4×向量长`；单条就超总预算则**直接不存**（`:65-70`）。
- 写入是异步的，跑在有界线程池 `BodyWorkPool("gateway-cache-store", 2, 16)` 上，另有独立的 `ByteBudget`；预算耗尽时**静默丢弃**这次写入（`AiSemanticCacheFilter.java:75-78,244-253`）。
- 只有 **2xx 且非 `text/event-stream`** 的响应会被存；错误响应与 SSE 永不入缓存（`:216-224`）。响应体超过 `maxBodySize` 时清空已捕获内容并放弃存储（`:225-242`）。

### 5.3 命中/未命中怎么体现

| | 命中 | 未命中 |
| --- | --- | --- |
| 响应头 | `X-AI-Cache: HIT` | `X-AI-Cache: MISS` |
| 状态码 / 正文 | 200 + `application/json`，正文是**当初存进去的上游 JSON 原文**（`:259-267`） | 正常走上游 |
| 是否调上游 | **否** | 是 |
| 是否预扣配额 | **否**（在 -241 之前短路） | 是 |
| 是否有 `token_usage_history` 记录 | **否** | 是 |
| 是否有 `ai.*` 指标 / `ai_log` | **是**（`usage_status=NOT_BILLED`、token 数 0、`cache_hit=true`） | 是 |
| 网关日志 | `INFO AI cache HIT on route {} scope={}` | `DEBUG AI cache MISS on route {} scope={}` |

命中路径**不调用 `chain.filter`**，所以 -241 之后的一切（配额预扣、提示词模板、provider 路由、用量采集）全部不执行。这是设计意图（`FilterOrderConstants.java:195-207`：命中若发生在预扣之后，预扣的估算量就没人结算/退款，租户会被为没花的 token 计费）。

**但 AI 统计不再跟着一起丢失。** `AiStatisticsFilter`(-43) 确实不会执行，所以 `AiSemanticCacheFilter` 在写完缓存响应后直接调用 `AiStatisticsRecorder.recordShortCircuited(exchange)`，产出 `ai.usage.requests{status="NOT_BILLED"}`、`ai.llm.service.duration`、以及一行 `cache_hit=true`、token 数为 0 的 `ai_log`。因为 `NOT_BILLED` 只在 `cacheHit` 为真时出现，**命中率 = `ai.usage.requests{status="NOT_BILLED"}` ÷ `ai.usage.requests` 全状态之和**，可按 route/model/provider 维度下钻。

两点实现约束值得知道：

- 记录逻辑被抽成了 `AiStatisticsRecorder`（普通 `@Component`）而不是留在过滤器里，因为 `GlobalFilterTrackingPostProcessor` 会把**每一个** `GlobalFilter` bean 替换成 `TrackedGlobalFilter` 装饰器，而该装饰器不暴露 delegate ⇒ **按具体类型注入另一个 GlobalFilter 会在启动期抛 `BeanNotOfRequiredTypeException`**。普通组件不被包装，可以正常注入。
- 命中时 `AiProviderRoutingFilter`(10005) 同样不会执行，所以模型归属由缓存过滤器用请求体里的 `model` 补写（`putIfAbsent(AI_MODEL_ATTR, model)`），否则每条命中的 `ai_log` 都是 `model=none`，命中率无法按模型拆分。
- 命中**仍然没有** `token_usage_history` 记录：没有消耗 provider token，也就没有可结算的用量。管理端的 Token 计量页看不到命中流量，要看命中率请用 `ai.*` 指标或 `ai_log`。

### 5.4 ★ 隔离边界：跨路由 / 跨租户共享吗

scope 字符串 = `<model 或 *>|<tenant 或 *>`（`AiSemanticCacheFilter.java:204-208`）：

```java
String m = config.isScopeByModel()  ? (model  != null ? model  : "*") : "*";
String t = config.isScopeByTenant() ? (tenant != null ? tenant : "*") : "*";
return m + "|" + t;
```

据实回答：

| 维度 | 是否隔离 | 依据 |
| --- | --- | --- |
| **跨路由** | **不隔离，会共享。** scope 里**没有 routeId**。两条不同路由只要 `model` 与租户解析结果相同，就共用同一批缓存条目 | `:204-208`（scope 组成）；`SemanticCacheStore.java:38,53`（分片与匹配都只按 scope 字符串） |
| **跨租户** | 默认隔离（`scopeByTenant=true`），但**仅当租户能被服务端验证** | `AiSemanticCacheConfig.java`（`scopeByTenant` 默认 true）+ 下表 |
| **跨模型** | 默认隔离（`scopeByModel=true`） | 同上 |
| **跨网关实例** | **不共享**（进程内内存，重启即空） | `SemanticCacheStore.java:12-21` |

租户身份**只取服务端验证过的来源**，优先级与配额过滤器一致：`api_key_metadata.tenantId` → `jwt_claims.tenant_id`（`:278-293`）。**绝不读原始 `X-Tenant-Id` 头**——scope 是隔离边界，客户端自选的值会让任何调用方读到并污染别人的缓存。具体行为：

- 客户端发了 `X-Tenant-Id` 但没有任何已验证租户 → **整个请求绕过缓存**（既不查也不存），`:132-140`。
- 客户端没发 `X-Tenant-Id` 且没有已验证租户 → 租户位取 `*`，**所有匿名请求共用 `model|*` 这一个 scope**。若路由不要求认证，这等于把匿名流量池化在一个共享缓存里。要在公网匿名场景使用，必须关掉 `scopeByTenant` 的错觉、改为在路由上强制认证，或接受匿名共享。
- `scopeByTenant=false` → 租户位恒为 `*`，跨租户共享。

---

## 6. 内容审核

策略类型 `AI_SECURITY`，过滤器 `AiSecurityGuardFilter`（order -246），配置类 `AiSecurityConfig`。请求侧在 pre-phase 审核，响应侧靠响应装饰器审核；能力覆盖敏感词 / 违禁词 / 正则 / PII 脱敏 / 可选外部审核服务（经 `gateway-moderation-spi` 的 `ModerationAdapter` SPI 扩展）。

关键默认值（`my-gateway/.../model/AiSecurityConfig.java`）：`scanRequest`/`scanResponse` 均 true、`inboundAction=BLOCK`、`outboundAction=REPLACE`、`piiAction=REPLACE`、`enablePiiMasking=true`、`piiTypes=[EMAIL,PHONE,ID_CARD]`、`streamingPolicy=BUFFER`、`maxBodySize=1048576`、`responseTimeoutMs=60000`、`externalAdapter=HTTP_JSON`、`externalTimeoutMs=3000`、`externalFailureAction=BLOCK`。

管理端保存时由 `AiSecurityStrategyValidator.validate` 校验枚举取值、数值范围（`maxBodySize` 1024..10485760、`externalTimeoutMs` 100..30000、`responseTimeoutMs` 1000..300000）、词表条数（≤2048）与单项长度（≤512）、正则可编译性、`externalUrl` 的 scheme/host/userInfo，以及 `externalHeaders` 的头名合法性与 CR/LF 注入（`gateway-admin/.../service/AiSecurityStrategyValidator.java:13-55`）。错误信息**不含任何密钥值**。

**完整细节（含 SPI 契约、SSE BUFFER 语义、外部审核失败处置、插件打包与部署）见 [ai-content-security.md](ai-content-security.md)，本文不重复。**

---

## 7. 上下文限制与提示词模板

### 7.1 `AI_CONTEXT_LIMIT`（order -243）

**解决的问题**：在请求花掉租户配额和上游容量**之前**，把超出模型上下文窗口的请求挡回去。

- 只处理 POST；`enabled=false` 或 `maxContextTokens <= 0` 时直接放行（`AiContextLimitFilter.java:73-78`）。
- 估算走 `AiTokenEstimator.estimateChat`：有 `messages[]` 按消息估、有 `prompt` 按 prompt 估、**非聊天/非 JSON 返回空**（`my-gateway/.../util/AiTokenEstimator.java:57-74`）。估不出就放行，与 Higress 语义一致。
- 判定式：`ceil(估算 × bufferRatio) > maxContextTokens` → 拒绝（`:91-96`）。
- 放行时把请求体发布为 `cachedRequestBody` 交换属性并用装饰器重放，**配额预扣直接复用这个属性，不再二次 join 请求体**（`:98-100`；`TokenQuotaPreCheckFilter.java:169-181`）。
- 请求体超 `maxBodySize` → **413 空响应体**（`:102-110`）。
- 支持 ROUTE 与 GLOBAL 两种 scope（`:145-167`）。

配置字段（`my-gateway/.../model/AiContextLimitConfig.java`）：

| 字段 | Java 默认值 | 控制台 schema 默认值 |
| --- | --- | --- |
| `enabled` | `false` | `false` |
| `maxContextTokens` | **`0`（= 关闭检查）** | **`128000`** |
| `bufferRatio` | `1.10` | `1.1`（范围 1..2） |
| `errorStatusCode` | `400` | `400`（范围 400..599） |
| `maxBodySize` | `1048576` | `1048576`（范围 1024..10485760） |

⚠️ `maxContextTokens` 两处默认值不一致。Java 默认 0 会让过滤器在 `:73` 直接返回；控制台表单默认填 128000（`gateway-admin/src/main/resources/sql/seed/strategy_types.sql:71`）。**用 API 直接建策略而不写这个字段，上下文限制就是关着的。**

### 7.2 `AI_PROMPT_TEMPLATE`（order 10004）

**解决的问题**：在不改客户端代码的前提下统一注入/覆盖 system prompt、前后插消息、做变量替换、按请求选命名模板、覆盖 model。对标 Higress `ai-prompt-template` + `ai-prompt-decorator`。

- 只处理 POST；`enabled=false` 放行（`AiPromptTemplateFilter.java:76-82`）。
- 消息组装顺序：`[system?] + prependMessages + 原始 messages（OVERRIDE 模式下丢掉原 system）+ appendMessages`（`:154-186`）。
- 变量替换：`{{key}}` 正则 `\{\{\s*([A-Za-z0-9_.\-]+)\s*}}`，值取自请求体的 `variablesBodyField`（默认 `variables`）对象；**未知占位符原样保留**（`:60,217-246`）。
- 模板选择：先看 `templateHeader`（默认 `X-Prompt-Template`），再看 `templateBodyField`；找不到同名模板则回落到顶层扁平字段构成的默认装饰（`:188-215`）。
- `stripControlFields=true`（默认）时在转发前删掉 `variables` 与模板选择字段（`:134-135,147-150`）。
- 无任何改动时**不重新序列化**，原字节直接转发（`:137-139`）。
- 装饰抛异常时按 `onError` 处置：`FORWARD`（默认）记 WARN 后转发原始请求体；`REJECT` 返回 500 `code=50002`。取值大小写不敏感，**无法识别的值一律按 FORWARD**（`:93-108`；`AiPromptTemplateConfig.java` 的 `onError` javadoc）。
- 请求体超 `maxBodySize` → 413 空响应体（`:112-116`）。

⚠️ 边界：这个过滤器在 10004，**晚于内容审核(-246)、上下文限制(-243)与配额预扣(-241)**。注入的提示词不经过入站审核，注入造成的 token 增长也不计入上下文限制判定与预扣估算（见 §2）。

⚠️ 控制台的 `AI_PROMPT_TEMPLATE` schema 只暴露 `enabled`/`systemPrompt`/`systemPromptMode`/`model`/`templateHeader`/`variablesBodyField`/`stripControlFields`/`maxBodySize`（`strategy_types.sql:62`）。`templates`（命名模板）、`prependMessages`、`appendMessages`、`templateBodyField`、`onError` **只能走策略 API 配**。

---

## 8. 配置形态：哪些靠静态配置，哪些靠策略

### 8.1 静态配置（只有这些）

`my-gateway/src/main/resources/application.yml`：

| key | yml 行 | 默认值 / 环境变量 |
| --- | --- | --- |
| `gateway.ai.cache.max-bytes` | :170 | `${GATEWAY_AI_CACHE_MAX_BYTES:33554432}` |
| `gateway.token-quota-ledger.directory` | :189 | `${GATEWAY_QUOTA_LEDGER_DIRECTORY:${user.dir}/data/token-quota/${gateway.instance-id}}` |
| `gateway.token-quota-ledger.max-pending` | :190 | `${GATEWAY_QUOTA_LEDGER_MAX_PENDING:4096}` |
| `gateway.token-quota-ledger.replay-hours` | :191 | `${GATEWAY_QUOTA_LEDGER_REPLAY_HOURS:168}` |
| `gateway.token-quota-ledger.retry-ms` | :192 | `${GATEWAY_QUOTA_LEDGER_RETRY_MS:5000}` |
| `gateway.token-usage.outbox-directory` | :194 | `${GATEWAY_USAGE_OUTBOX_DIRECTORY:${user.dir}/data/token-usage/${gateway.instance-id}}` |
| `gateway.token-usage.outbox-max-bytes` | :195 | `${GATEWAY_USAGE_OUTBOX_MAX_BYTES:67108864}` |
| `gateway.token-usage.outbox-max-records` | :196 | `${GATEWAY_USAGE_OUTBOX_MAX_RECORDS:4096}` |

**只在 `@Value` 里、yml 没有条目、也没有环境变量占位**的三个 key（yml 与 `@Value` 两处逐个核对的结果；`@Value` 的缺省值即生效值）：

| key | 默认值 | 位置 |
| --- | --- | --- |
| `gateway.ai.cache.max-entries` | `1000` | `SemanticCacheStore.java:27` |
| `gateway.ai.key-cache-ttl-seconds` | `300` | `AdminProviderKeyResolver.java:41` |
| `gateway.token-usage.max-body-size` | `65536` | `TokenUsageCollectorFilter.java:92` |

复核命令：

```bash
git grep -n '@Value("\${gateway\.\(ai\|token\)' -- my-gateway/src/main/java
grep -n "ai:\|token-quota-ledger:\|token-usage:" my-gateway/src/main/resources/application.yml
```

⚠️ `gateway.token-usage.max-body-size` 默认 **64 KiB**，是 JSON 响应做用量解析时**旁路拷贝**的上限。行为按上游是否声明长度分三种（`TokenUsageCollectorFilter.java:185-247`）：

- `Content-Length` 已知且 ≤ 64 KiB → 整包聚合后解析 usage，并在响应提交前写入 `X-Token-Usage-*` 头。**这是唯一能拿到这组响应头的情形。**
- `Content-Length` 已知且 > 64 KiB，**或** `Content-Length` 未知（chunked）→ **原样流式放行**，同时做一份截断到 64 KiB 的旁路拷贝。正文完整送达客户端；若实际超出上限，拷贝被截断、usage 解析不出来，用量落 `UNKNOWN`、预扣保留待人工核对。这类响应拿不到 `X-Token-Usage-*` 头（响应已提交）。
- 唯一还会 502 的情形：上游**声明的 `Content-Length` 小于实际发送量**（响应本身已损坏），此时 `join` 已经释放了它消费过的缓冲区、正文不可恢复。

**chunked 超限曾经是 502**：旧实现把长度未知的响应也交给 `DataBufferUtils.join(flux, maxBodySize)`，超限时 join 已释放消费过的缓冲区，正文无法恢复，只能返回 502 并丢掉上游已经完整生成的答复。而 chunked 是 LLM provider 与中间代理的常态，所以那是常见路径而非边角情况。现已改为流式放行——**计量永远不会毁掉一次成功的推理**。

长补全场景仍建议调大这个值：不是因为会失败，而是因为超过上限的部分不进旁路拷贝，用量会落 `UNKNOWN` 而需要人工核对。注意它和 `gateway.trace.max-body-size`（`application.yml:182`，也是 65536）是**两个不同的 key**。

### 8.2 策略驱动（其余全部）

所有 AI 行为都由**策略**驱动，通过管理端配成 strategy 再绑定到路由。7 个相关策略类型：

| `strategyType` | 常量位置 | 网关读取处 | 配置类 | 支持 GLOBAL scope |
| --- | --- | --- | --- | --- |
| `AI_PROXY` | `StrategyDefinition.java:48` | `AiProviderRoutingFilter.java:95-96` | `AiProxyConfig` | 是（见下） |
| `AI_PROMPT_TEMPLATE` | `:51` | `AiPromptTemplateFilter.java:74-75` | `AiPromptTemplateConfig` | 是 |
| `AI_SECURITY` | `:54` | `AiSecurityGuardFilter.java:53-54` | `AiSecurityConfig` | 是 |
| `AI_SEMANTIC_CACHE` | `:57` | `AiSemanticCacheFilter.java:94-95` | `AiSemanticCacheConfig` | 是 |
| `AI_CONTEXT_LIMIT` | `:60` | `AiContextLimitFilter.java:147-148` | `AiContextLimitConfig` | 是（另有显式回落 `:152-165`） |
| `AI_STATISTICS` | `:63` | `AiStatisticsFilter.java:225-226` | `AiStatisticsConfig` | 是（另有显式回落 `:230-243`） |
| `TOKEN_RATE_LIMITER` | **没有 `TYPE_*` 常量** | 两个 token 过滤器都写死字符串字面量：`TokenQuotaPreCheckFilter.java:373,381`、`TokenUsageCollectorFilter.java:874,882` | `TokenQuotaConfig` | 是（显式回落 `:395-413` / `:896-914`） |

读取路径统一是 `StrategyManager.getConfig(routeId, type, Class)`（`my-gateway/.../manager/StrategyManager.java:398-404`）→ `resolveStrategyWithFallback`（`:379-388`，配置中心不健康时回落本地快照）→ `getStrategyForRoute`（`:252-281`）。**`getStrategyForRoute` 自己就会先找 ROUTE 绑定、再找 GLOBAL**，所以 7 个类型都能配成实例级全局策略；`AI_CONTEXT_LIMIT` 与 `AI_STATISTICS` 里那段额外的 `getGlobalStrategiesByType` 回落是冗余的。转换结果按"策略版本"缓存，不是每请求 `convertValue`（`:406-420`）。

`TOKEN_RATE_LIMITER` 与其它 6 个的差别还在于：它**不在** `StrategyDefinition` 的常量表里，只作为一行数据存在于 `gateway-admin/src/main/resources/sql/seed/strategy_types.sql:59`（分类 `ai`，`hasSubSchemas:true`）。该 seed 文件里分类为 `ai` 的策略类型共 7 行（钉在 `a2ac1ae`；复核 `grep -c ", 'ai'," gateway-admin/src/main/resources/sql/seed/strategy_types.sql`）。

---

## 9. 如何配置一条 AI 路由

下面是从代码逐步核实出来的最短路径。**所有 `/api/**` 请求都需要登录 JWT；POST/PUT/PATCH/DELETE 一律需要 `ADMIN` 权限**（`gateway-admin/.../config/SecurityConfig.java:122-127`）。所有响应套 `ApiResponse{code,message,data,traceId,timestamp}`。

### 步骤 1：建 provider 密钥

两种方式，选一种。

**方式 A（推荐，随 AI 服务自动建）**：直接跳到步骤 2，在 body 里传明文 `apiKey`，管理端会加密存入 `provider_credentials`，ref 为 `svc-<serviceId>`（`ServiceService.java:642-653`）。

**方式 B（显式建，便于一个 provider 配多把 key 做密钥池）**：

```bash
curl -X POST http://localhost:9090/api/provider-credentials \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{"credentialRef":"ds-pool-1","name":"DeepSeek 池 1","provider":"deepseek","apiKey":"sk-..."}'
```

`credentialRef` 省略时自动生成 UUID。响应 `data` 是掩码视图（无明文、无密文）：

```json
{"code":200,"message":"Credential created successfully",
 "data":{"credentialRef":"ds-pool-1","name":"DeepSeek 池 1","provider":"deepseek",
         "keyHint":"****abcd","tenantId":null,"enabled":true,
         "createdAt":null,"updatedAt":null}}
```

### 步骤 2：建 AI 服务（= 一个 LLM 上游）

`POST /api/services?instanceId=<实例ID>`（`ServiceController.java:156-158`，body 是完整 `ServiceDefinition`）。AI 服务必填 `category:"AI"`、`name`、`providerType`，以及 `apiKey`（明文，仅本次）或 `credentialRef`（已存在）；`baseUrl` 可选（中继/自托管）。

```bash
curl -X POST "http://localhost:9090/api/services?instanceId=tj9j963zq2x1" \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{
        "name": "deepseek-prod",
        "category": "AI",
        "providerType": "deepseek",
        "apiKey": "sk-...",
        "description": "DeepSeek 生产"
      }'
```

`providerType` 必须是 §3.1 的 6 个 id 之一。**管理端不校验它是否在枚举里**（`ServiceService.java:638-640` 只检查非空），写错要到运行时才表现为"无法解析模型对应的 Provider"（400 `code=40001`）。明文 `apiKey` 是 `transient` 字段，加密后立即置 null，**不会进服务元数据、不会进 Nacos**（`:658-659`）。

### 步骤 3：建 AI 路由

`POST /api/routes?instanceId=<实例ID>`（`RouteController.java:75-79`，body 是完整 `RouteDefinition`）。`RouteService` 依次调 `normalizeAiRoute` → `validateAiRoute` → 落库 → `syncProxyStrategy`（`RouteService.java:297-298,360`）。

```bash
curl -X POST "http://localhost:9090/api/routes?instanceId=tj9j963zq2x1" \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{
        "routeName": "ai-chat",
        "order": 0,
        "category": "AI",
        "predicates": [{"name": "Path", "args": {"_genkey_0": "/v1/chat/completions"}}],
        "aiUpstreams": [
          {"provider": "deepseek-prod", "weight": 80, "modelMapping": {"gpt-4o": "deepseek-chat"}},
          {"provider": "qwen-prod",     "weight": 20, "modelMapping": {}}
        ]
      }'
```

字段依据：

- `category` 必须是 `"AI"`；`RouteDefinition.isAiRoute()` 就是拿它比（`RouteDefinition.java:138-142`）。
- `predicates` 里**必须有 Path 断言**。AI 路由的北向路径由它决定（如 `/v1/chat/completions`），后端不会替你生成。
- `uri` 可以不传：`normalizeAiRoute` 会填占位符 `https://ai-gateway.invalid`（`AiRouteProjector.java:39,58-65`），运行时被 `AiProviderRoutingFilter` 覆盖。
- `aiUpstreams[].provider` 是**步骤 2 里的 AI 服务名**，不是 provider 类型。至少 1 个，否则报"AI 路由必须至少绑定一个 AI 服务"（`:75-78`）。
- `weight` 取值 **1..100**，越界报"AI 上游权重必须在 1 到 100 之间"（`:196-200`）；缺省按 100（`:152`）。
- `modelMapping` 是"客户端 model 名 → 上游 model 名"，按 upstream 独立配置。
- `aiFallback` 可选：`{"enabled":true,"upstreams":[{"provider":"<AI 服务名>"}]}`。启用时 `upstreams` 不能为空（`:86-97`）。
- `modelPredicates` 可选：`[{"matchType":"PREFIX","value":"gpt-"}]`。**见 §11 第 7 条：它会被持久化但当前不参与路由匹配。**

每个绑定的 AI 服务都必须已存在、`category=AI`、有 `providerType`、有 `credentialRef`，否则分别报"AI 路由绑定的服务不存在"/"AI 路由只能绑定 AI 服务"/"AI 服务未指定 provider 类型"/"AI 服务未配置 API Key"（`AiRouteProjector.java:233-254`）。

保存成功后，`syncProxyStrategy` 自动创建或更新一条 **ROUTE 作用域的 `AI_PROXY` 策略**，名为 `ai-proxy-<routeId>`，描述"由 AI 路由绑定自动生成"（`:104-135`）。它的 config 形如：

```json
{"enabled": true, "providerSelection": "MODEL",
 "upstreams": [{"serviceId":"deepseek-prod","provider":"deepseek",
                "credentialRef":"svc-...","weight":80,
                "baseUrlOverride":null,"modelMapping":{"gpt-4o":"deepseek-chat"}}],
 "providerApiKeyRefs": {"deepseek": "svc-..."},
 "providerBaseUrlOverride": {"deepseek": "..."},
 "modelMapping": {"gpt-4o": "deepseek-chat"},
 "fallbacks": [...]}
```

路由改回 `NORMAL` 时，这条伴生策略会被**停用而不是删除**（`:212-227`）。

⚠️ 自动投影**不写** `providerKeyPools`、`defaultModel`、`responseFormat`、`stripClientAuth`、`maxBodySize`。要用这些，得直接改伴生策略（下一步），并记住下次编辑路由时 `syncProxyStrategy` 会用投影结果**整体覆盖** config。

### 步骤 4：绑定其余 AI 能力

策略端点（`gateway-admin/.../controller/StrategyController.java`）：

| 方法 | 路径 | 参数 | 鉴权 |
| --- | --- | --- | --- |
| `POST` | `/api/strategies` | body = 完整 `StrategyDefinition`；`instanceId` 可选 | **ADMIN** |
| `PUT` | `/api/strategies/{strategyId}` | path `strategyId` + body | **ADMIN** |
| `GET` | `/api/strategies/type/{type}` | path `type`（如 `AI_SEMANTIC_CACHE`） | 已登录 |
| `GET` | `/api/strategies/{strategyId}` | path | 已登录 |
| `GET` | `/api/strategies/route/{routeId}` | path | 已登录 |
| `DELETE` | `/api/strategies/{strategyId}` | path | **ADMIN** |
| `POST` | `/api/strategies/{strategyId}/enable` \| `/disable` | path | **ADMIN** |

⚠️ **没有** `PUT /api/strategies/<类型名>` 或 `POST /api/strategies/<类型名>` 这种端点。路径变量是 `strategyId`，类型走 body 或 `/type/{type}` 查询。

`StrategyDefinition` 字段：`strategyId`、`strategyName`、`strategyType`、`scope`（`GLOBAL` 默认 / `ROUTE`）、`routeId`、`priority`（默认 100）、`enabled`（默认 true）、`config`（`my-gateway/.../model/StrategyDefinition.java:18-26`）。

例：给 AI 路由加语义缓存 + 上下文限制 + 可观测。

```bash
# 语义缓存（embedding 用自己的凭据；ref 来自步骤 1）
curl -X POST http://localhost:9090/api/strategies \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{
        "strategyName": "ai-cache-chat",
        "strategyType": "AI_SEMANTIC_CACHE",
        "scope": "ROUTE",
        "routeId": "<步骤 3 返回的 routeId>",
        "enabled": true,
        "config": {
          "enabled": true,
          "embeddingProvider": "qwen",
          "embeddingModel": "text-embedding-v3",
          "embeddingCredentialRef": "qwen-embed-1",
          "similarityThreshold": 0.95,
          "ttlSeconds": 3600,
          "scopeByModel": true,
          "scopeByTenant": true
        }
      }'

# 上下文限制（maxContextTokens 必须显式写，Java 默认 0 = 关闭）
curl -X POST http://localhost:9090/api/strategies \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{
        "strategyName": "ai-ctx-chat", "strategyType": "AI_CONTEXT_LIMIT",
        "scope": "ROUTE", "routeId": "<routeId>", "enabled": true,
        "config": {"enabled": true, "maxContextTokens": 128000,
                   "bufferRatio": 1.1, "errorStatusCode": 400}
      }'

# 可观测
curl -X POST http://localhost:9090/api/strategies \
  -H "Authorization: Bearer <ADMIN_JWT>" -H "Content-Type: application/json" \
  -d '{
        "strategyName": "ai-stats", "strategyType": "AI_STATISTICS",
        "scope": "ROUTE", "routeId": "<routeId>", "enabled": true,
        "config": {"enabled": true, "enableMetrics": true,
                   "enableLog": true, "enableSpanTags": true}
      }'
```

`config` 里各字段的默认值以 §7 的表和以下代码为准：`AiSemanticCacheConfig.java`、`AiContextLimitConfig.java`、`AiStatisticsConfig.java`、`AiPromptTemplateConfig.java`、`AiProxyConfig.java`、`AiSecurityConfig.java`。控制台表单暴露的字段子集见 `strategy_types.sql:56-74`。

### 步骤 5：验证

```bash
# 北向模型发现（网关 8080，无鉴权）
curl http://localhost:8080/v1/models

# 真实推理调用（路由上若绑了 AUTH 策略，这里要带对应的客户端凭据）
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}]}' -i
```

`-i` 看响应头：第一次应是 `X-AI-Cache: MISS` + `X-Token-Usage-*`；配了语义缓存且重复问法时第二次应是 `X-AI-Cache: HIT`（此时不会有 `X-Token-Usage-*`）。网关日志里应有 `AI proxy routing: route=..., model=... -> provider=..., service=..., upstream=... (attempt 1/N)` 与 `Token usage settled: tenant=..., preDeducted=..., actual=..., interrupted=...`。

---

## 10. 可观测性

### 10.1 网关侧：`AI_STATISTICS`（order -43）

`AiStatisticsFilter`（`my-gateway/.../filter/ai/AiStatisticsFilter.java`）对标 Higress `ai-statistics`。它的响应装饰器**只打时间戳、不缓冲**，所以不影响 SSE 流式交付（`:91-105`）。

Micrometer 指标（`enableMetrics=true` 且 `MeterRegistry` 存在时，`:158-178`）：

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `ai.tokens.input` | Counter（按输入 token 数递增） | `route`, `model`, `provider`, `tenant` |
| `ai.tokens.output` | Counter（按输出 token 数递增） | `route`, `model`, `provider`, `tenant` |
| `ai.usage.requests` | Counter（每请求 +1） | `route`, `provider`, `status` |
| `ai.llm.service.duration` | Timer（含上游往返的总时延） | `route`, `model`, `provider` |
| `ai.llm.first.byte.duration` | Timer（TTFT） | `route`, `model`, `provider` |

`ai.llm.first.byte.duration` **只在响应 `Content-Type` 含 `text/event-stream` 且确实观测到首个非空 buffer 时才记录**（`:156,173-177`）。非流式响应没有 TTFT 指标。

标签取值缺失时统一填字符串 `"none"`（`:219-221`）；`tenant` 取自 `token_quota_tenant_id` 交换属性（`:135`），未绑定配额策略时是 `TokenUsageCollectorFilter.UNASSIGNED_TENANT`（`__unassigned__`）。

`status` 标签（即 `usage_status`）取值：缓存命中时 `NOT_BILLED`；否则取 `AiUsageResult.status()` 的 `CONFIRMED` / `ESTIMATED` / `UNKNOWN` / `NOT_BILLED`；再否则旧属性存在则 `CONFIRMED`，都没有则 `UNKNOWN`（`:139-140`；`AiUsageResult.java:4-6`）。

结构化日志（`enableLog=true`）：每请求一行 `INFO ai_log: {json}`，字段依次为 `model`、`input_token`、`output_token`、`llm_first_byte_duration`、`usage_status`、`llm_service_duration`、`response_type`（`stream`/`json`）、`cache_hit`、`route`、`provider`、`service`、`tenant`，以及配了 `sessionIdHeader` 且请求带了该头时的 `session_id`（`:180-198,211-213`）。

Span 标签（`enableSpanTags=true` 且 micrometer-tracing 的 `Tracer` 存在）：把上面那份 map 以 `ai.<key>` 为标签名打到**进入本过滤器时**捕获的当前 span 上（`:88,200-209`）。

`AI_STATISTICS` 支持 GLOBAL scope，所以可以配一条实例级策略覆盖所有路由（`:223-245`）。

### 10.2 管理端：Token 计量查询

`/api/token-usage`（`gateway-admin/.../controller/TokenUsageController.java:28`），数据源是网关结算后上报的 `token_usage_history` 表，聚合逻辑在 `TokenUsageAnalyticsService`。**全部是 GET、参数全在 query、都需要登录**：

| 方法 | 路径 | query 参数（默认值） | data |
| --- | --- | --- | --- |
| `GET` | `/api/token-usage/overview` | `from`, `to`（ISO DATE_TIME，可空）, `instanceId`, `routeId`, `tenantId`, `model` | `{requests, promptTokens, completionTokens, totalTokens, avgLatencyMs, activeTenants, activeRoutes, activeModels, unknownUsageRequests, failedSettlementRequests}`（`TokenUsageAnalyticsService.java:33-52`） |
| `GET` | `/api/token-usage/timeseries` | 同上 + `granularity`（`hour`，可填 `day`；非 `day` 一律按小时） | 桶标签 + 请求数 + 三类 token（`:55-82`） |
| `GET` | `/api/token-usage/breakdown` | 同上 + `dimension`（`tenant`，可填 `route`/`model`/`instance`）, `limit`（10） | 按 `totalTokens` 降序的分组榜（`:84-107`） |
| `GET` | `/api/token-usage/events` | 同上 + `page`（0）, `size`（20）, `unresolvedOnly`（false） | 时间倒序明细分页，含 `traceId` 可回链追踪页（`:109-116`） |

`unresolvedOnly=true` 就是控制台的"仅显示待核对明细"：筛 `usageStatus ∈ {UNKNOWN, ESTIMATED}` 或 `settlementStatus ∈ {PENDING, FAILED}`，详见 [ai-usage-reconciliation.md](ai-usage-reconciliation.md)。

配额侧另有 `/api/token-quota`（`TokenQuotaController.java:29`）：租户配额 CRUD、`GET /{tenantId}/status`、`POST /{tenantId}/adjust`、`POST /{tenantId}/reset?periodType=BOTH`、`GET /{tenantId}/usage`、`GET /{tenantId}/statistics`、`GET /{tenantId}/daily-summary?days=30`、`GET /{tenantId}/model-breakdown`、`GET /approaching-limit?thresholdPercent=80`、`POST /{tenantId}/enable|disable`、`GET /types`、`GET /formats`。写操作需 ADMIN。

---

## 11. 当前不覆盖的部分 / 已知边界

照实列。每条都有代码依据。第 6、7、11 条已在本次修复中解决，保留原文并划掉，以免读者以为它们仍然成立、也免得同一个坑被重新挖出来。

1. **Anthropic 原生 `/v1/messages` 未实现。** 6 个内置 provider 全部通过各自的 OpenAI 兼容 `/chat/completions` 端点访问，`Protocol` 恒为 `OPENAI_COMPAT`（`LlmProvider.java:41-46`）。`Protocol.ANTHROPIC` / `Protocol.CUSTOM` 与 `AuthScheme.X_API_KEY` / `AuthScheme.CUSTOM` 是预留枚举值，**没有请求/响应转换实现**，`AiProviderRoutingFilter.java:399-401` 的 `x-api-key` 分支在当前枚举下不可达。
2. **只代理 chat-completions 的 POST。** 非 POST 请求直接放行、不做任何 AI 处理（`AiProviderRoutingFilter.java:102-105`；其余 5 个 AI 过滤器同样只处理 POST）。`resolveChatUrl` 把路径写死为 `/chat/completions`（`LlmProvider.java:71-76`），所以 `/v1/embeddings`、`/v1/responses`、`/v1/audio/*`、`/v1/images/*`、`/v1/rerank` **都不能经 AI 代理转发**（`/embeddings` 只被语义缓存自己用作出站调用）。
3. **请求体只读顶层 `model` 一个字段。** `extractModel` 只做 `readTree(body).path("model")` 且要求是文本（`:414-425`）。OpenAI Responses API 的 `instructions` / `input` 形态、批量请求、非 JSON 请求体都识别不出模型。
4. **语义缓存：单实例内存态、不持久化、无共享存储。** 进程重启即清空；多副本之间不共享，命中率随副本数下降（`SemanticCacheStore.java:12-21`）。共享存储（Redis-Stack / 外部向量库）是 javadoc 里写明的后续工作，当前没有实现。
5. **语义缓存的作用域不含 routeId。** 见 §5.4：跨路由会共享条目。
6. ~~**缓存命中对计量与指标完全不可见。**~~ **已修复。** 命中仍在 -242 短路、`TokenUsageCollectorFilter`(-44) 与 `AiStatisticsFilter`(-43) 仍不执行，但 `AiSemanticCacheFilter` 现在写完缓存响应后直接调用 `AiStatisticsRecorder.recordShortCircuited(exchange)`，产出 `ai.usage.requests{status="NOT_BILLED"}`、`ai.llm.service.duration` 和一行 `cache_hit=true`、token 数为 0 的 `ai_log`。命中率 = `NOT_BILLED` ÷ 全状态之和，可按 route/model/provider 下钻。**残留边界**：命中仍然没有 `token_usage_history` 记录（没消耗 provider token，无可结算用量），所以管理端的 Token 计量页看不到命中流量；"节省了多少 token" 也仍无处可查——那需要知道被缓存的那次回答原本会消耗多少，而网关没有这个信息。
7. ~~**`AiStatisticsFilter` 里的缓存命中分支不可达。**~~ **已修复**（同上一条）。`AiStatisticsRecorder` 读 `AI_CACHE_HIT_ATTR` 并把 `usage_status` 置为 `NOT_BILLED`、token 数置 0 的分支现在真的会执行；`ai_log.cache_hit` 不再恒为 `false`。记录逻辑被抽成 `AiStatisticsRecorder`（普通 `@Component`）而非留在过滤器里，原因见 §5.3：`GlobalFilterTrackingPostProcessor` 会把每个 `GlobalFilter` bean 换成不暴露 delegate 的 `TrackedGlobalFilter`，按具体类型注入另一个过滤器会在启动期抛 `BeanNotOfRequiredTypeException`。
8. **AI 路由的 `modelPredicates` 不参与路由匹配。** 它被 `RouteDefinition.java:131` 持久化、被 `RouteResponse.java:105` 回传、被前端表单编辑，但 `git grep -n "modelPredicates" -- '*.java'` 在 gateway-admin 与 my-gateway 里只命中这两处字段声明加 `RouteEntity.java:41` 的一句注释，**没有任何代码把它转成 SCG 路由断言**，`AiModelPredicate` 类本身也没有消费者。实际路由选择完全由路由的普通断言（Path 等）决定；按模型名分流到不同 AI 路由**当前做不到**。
9. **流式响应的能力衰减：**
   - 语义缓存**永不存储** SSE（`AiSemanticCacheFilter.java:222-224`）。`cacheStreaming` 字段是"为向前兼容保留"的，即使设成 `true`，流式请求也只会白做一次 embedding 调用与查表（还可能命中一条非流式缓存、把 JSON 返回给期待 SSE 的客户端），**不会写入**。
   - 出站内容审核的 `streamingPolicy=BUFFER`（默认）会**等整个流结束再一次性下发**，失去逐字输出效果；`SKIP` 保留流式但不审核；`BLOCK` 直接 502。详见 [ai-content-security.md](ai-content-security.md)。
   - `X-Token-Usage-*` 响应头加不上去（响应已提交），只在日志里（`TokenUsageCollectorFilter.java:604-608`）。
   - `ai.llm.first.byte.duration` 只在流式响应上产出（`AiStatisticsFilter.java:173-177`）。
   - failover 只能在第一个字节之前发生（`AiProxyConfig.java:118-121`）。
   - 上游不支持 `stream_options.include_usage` 时，流式用量拿不到 → `UNKNOWN` → 预扣保留待人工核对。
10. **Anthropic 的 prompt-cache token 默认不计费。** 见 §4：AI 代理路由的 `responseFormat` 恒被写成非空值（缺省 `OPENAI`），自动探测分支不可达，`cache_creation_input_tokens` / `cache_read_input_tokens` 只有显式配 `responseFormat:"ANTHROPIC"` 才会被加进输入 token（`TokenUsageParser.java:147-151`）。
11. ~~**`gateway.token-usage.max-body-size` 默认只有 64 KiB，chunked 超限直接 502 丢掉答复。**~~ **已修复。** 见 §8.1：现在只有"上游声明了 `Content-Length` 且在限内"才聚合，长度未知或已知超限的响应一律原样流式放行 + 有界旁路拷贝，**计量不再能毁掉一次成功的推理**。**残留边界**：超过 64 KiB 的部分不进旁路拷贝，用量会落 `UNKNOWN` 需人工核对，且这类响应拿不到 `X-Token-Usage-*` 头；唯一还会 502 的情形是上游声明的长度小于实际发送量（响应本身已损坏，缓冲区已被 join 释放、不可恢复）。
12. **`AI_CONTEXT_LIMIT` 的 Java 默认值 `maxContextTokens=0` 等于关闭。** 见 §7.1。
13. **密钥轮转后最长 300 秒不生效。** `AdminProviderKeyResolver` 的 Caffeine 缓存写后过期 300 秒，`invalidate(ref)` 方法存在但**无任何调用点**（`:102-105`）。管理端轮转密钥不会通知网关。
14. **provider 自动识别是纯前缀匹配，按枚举声明顺序取第一个命中。** `openai` 排在最前，所以 `chatgpt-` 前缀归 openai；`ProviderRegistry.resolveByModel` 没有权重或最长前缀概念（`ProviderRegistry.java:44-58`，`LlmProvider.matchesModel` `:95-106`）。自托管后端的自定义模型名必须靠 `modelRouting` 显式映射，否则解析不出 → 400 `code=40001`。
15. **没有 provider 级熔断、健康检查或自动摘除。** `LEAST_INFLIGHT` 只看本实例在途请求数；一个持续 5xx 的 upstream 不会被动下线，只能靠绑定 RETRY + `fallbacks` 做请求级切换，或靠通用的 `CIRCUIT_BREAKER` 策略（作用于路由，不区分 upstream）。
16. **没有 Anthropic / Gemini 之外的 provider 扩展点。** 加一家新 provider 必须改 `LlmProvider` 枚举并重新构建网关；没有插件式或配置式的 provider 注册（`ProviderRegistry.java:14-17` 的 javadoc 明确说"自定义/自托管 provider 通过 base-url 覆盖表达，而不是新增枚举常量"）。
17. **`/v1/models` 无鉴权**（见 §3.7）。
18. **AI 代理不做请求/响应协议转换。** `responseFormat` 只影响**用量解析**，不改写给客户端的响应形状；`Protocol` 字段不参与任何转换逻辑。北向恒为 OpenAI 兼容形状，客户端拿到的就是上游原样返回的正文（除了 `AiSecurityGuardFilter` 的出站改写与 `ResponseTransform` 策略）。

---

## 12. 相关文档

**数据面（同一方向，中文）**

- [ai-content-security.md](ai-content-security.md) — `AI_SECURITY` 内容审核与 `gateway-moderation-spi` 适配器契约
- [ai-quota-ledger.md](ai-quota-ledger.md) — Token 周期配额账本：预扣 / 结算 / 退款 / 本地降级
- [ai-usage-delivery.md](ai-usage-delivery.md) — 用量 outbox 与 `eventId` 去重投递
- [ai-usage-reconciliation.md](ai-usage-reconciliation.md) — `UNKNOWN`/`ESTIMATED` 用量与 `PENDING`/`FAILED` 结算的人工核对

**控制面（另一个方向，英文）**

- [ai-analysis.md](ai-analysis.md) — 管理端调 LLM 分析自己的网关指标
- [ai-copilot.md](ai-copilot.md) — AI 运维助手与工具注册表

**相关能力**

- [authentication.md](authentication.md) — 北向客户端认证，也是租户身份（`api_key_metadata` / `jwt_claims`）的唯一可信来源
- [rate-limiting.md](rate-limiting.md) — 与 Token 配额并行的请求级限流
- [retry.md](retry.md) — AI 上游瞬时错误 failover 依赖的 RETRY 策略
- [response-caching.md](response-caching.md) — 通用响应缓存（`CACHE`，-46），与语义缓存是两套独立机制
- [request-tracing.md](request-tracing.md) — Trace ID 与请求追踪，`token_usage_history.traceId` 可回链
- [request-journey.md](request-journey.md) — 按 Trace ID 关联路由、追踪记录与用量明细
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — §15 AI Integration Architecture、§21 特性索引
- [../FEATURES.md](../FEATURES.md) — 特性文档总表
