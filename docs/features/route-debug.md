# 路由调试

在实例的「路由管理」页面点击路由卡片或详情面板中的「调试」，进入该路由的调试页。

页面根据路由的 Path、Method、Host、Header 断言预填请求。路径是请求进入网关时的路径，不能提前应用 StripPrefix、RewritePath 等转发规则。通配路径需要填写具体业务地址。AI 路由预填聊天请求及流式参数。

## 请求链路

浏览器提交方法、路径、请求头、请求体和鉴权选择 → admin 校验路由所属实例 → admin 读取实例访问地址、计算鉴权凭据 → 调用网关实例 → 网关按正常路由及过滤链路处理 → admin 将响应实时返回页面。

调试不会绕过网关鉴权、限流和路由匹配，也不会把控制台登录 Token 自动转发给网关。修改请求路径后可能匹配其他路由，可利用返回的 Trace ID 查询实际处理记录。

## 鉴权

下拉列表区分「路由已绑定」和「实例其他鉴权」，默认选择已绑定的策略。选中其他策略只改变调试请求携带的凭据，不会修改路由绑定。

- JWT：admin 根据配置签发测试 Token。仅配置公钥的策略无法签发，需要输入签发方提供的 Token。
- API Key：admin 自动读取单密钥，或按用户选择的密钥标识查找实际密钥，再按配置携带 Header 或查询参数及前缀。
- Basic：admin 按配置组装 Authorization。存储密码哈希或多用户密码时，需要填写原始 `用户名:密码`，不能用哈希代替密码。
- HMAC：admin 以实际发送的方法、编码后的路径、排序后的原始查询参数和非空请求体的 SHA-256 摘要计算签名；每次生成新时间戳和 nonce，并支持配置要求的 Content-MD5。
- OAuth2：输入已获取的 access token，由 admin 组装 Bearer 请求头。

前端只获取鉴权元数据和不透明的密钥选择标识，不获取 JWT/HMAC 签名密钥或自动使用的 API Key。自填凭据仅保存在当前页面内存，离开页面即清除。

## 响应

显示实际 HTTP 状态码、首包耗时、总耗时、响应头、原始响应内容和 Trace ID。AI SSE 响应增量显示，结束但没有 `[DONE]` 时提示可能中途断开。点击停止或离开页面会取消调试连接。

网关返回的 401、429、5xx 作为调试结果展示，不会当作控制台退出登录的信号。

## 接口与边界

- `GET /api/routes/{routeId}/debug/auth?instanceId=...`：返回可选鉴权的元数据。
- `POST /api/routes/{routeId}/debug?instanceId=...`：以 SSE 返回 response、chunk、complete 或 failure 事件。
- 两个端点都是控制台 API，需带登录 JWT；`POST /api/**` 还要求 ADMIN 权限（`SecurityConfig.java:122`）。前端用 `Authorization: Bearer <控制台 Token>` 直接 fetch（`gateway-ui/src/api/routeDebug.ts`）。
- 请求包含 method、path、headers、body、timeoutSeconds，以及可选的 authPolicyId、credential、credentialId。
- 目标地址仅从路由所属实例配置读取，不能由调试请求指定外部地址；不跟随重定向。
- 超时 1–60 秒；请求体最多 64 KB；最多 64 个请求头；输出最多 256K 字符，超限终止接收并提示截断。
- admin 使用独立的 4 个工作线程、8 个排队名额，避免调试占满业务线程；流式心跳用于检测浏览器断开并取消上游调用。

部署时需要同时更新 gateway-admin、gateway-ui 和 my-gateway。my-gateway 没有为调试新增 HTTP 接口，但自 `3087aa2` 起增加了调试请求识别：admin 用 `gateway.admin.internal-token` 对调试请求做 HMAC-SHA256 签名，附加 `X-Gateway-Debug-Trace` 头（签名绑定 instanceId、方法、路径与查询、时间戳和 nonce，60 秒内有效，允许 5 秒时钟偏差）；网关侧 `DebugTraceVerifier` 验签通过后，`TraceIdGlobalFilter` 打上可信标记并移除该头（不转发给上游），`TraceCaptureGlobalFilter` 据此强制留痕（replayType `DEBUG`）并跳过响应体采样率，仍受响应体字节预算限制。gateway-admin 与 my-gateway 必须配置同一个 internal-token，且签名里的 instanceId 要与网关的 `gateway.instance-id` 一致。未升级 my-gateway 或 token 不匹配时调试请求照常发送，但链路记录退回常规采样，页面上的 Trace ID 可能查不到对应记录。
