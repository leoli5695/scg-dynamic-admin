# AI 内容安全

`AI_SECURITY` 在网关转发过程中审核文本，不保存完整请求、响应或对话。策略页面提供词表、正则、个人信息处理和可选外部审核配置。普通使用者不需要编写 JSON 映射或转换脚本。

## 内置检测

- `bannedWords` / `prohibitedWords`：业务维护的敏感词 / 违禁词，按不区分大小写的子串匹配，替换为 `***`。两类命中分别标记 `BANNED_WORD` / `PROHIBITED_WORD`。
- `bannedPatterns`：Java 正则。管理端保存时检查语法，非法配置不发布。词表、正则每类最多 2048 项，每项最多 512 字符；正则由可信管理员维护，避免复杂回溯表达式。
- `enablePiiMasking` / `piiTypes`：邮箱、大陆手机号、18 位身份证号的格式检测，不能证明号码真实有效。
- `inboundAction` / `outboundAction`：`BLOCK` 拦截、`REPLACE` 替换、`DETECT` 只检测。请求默认 BLOCK，响应默认 REPLACE。
- `piiAction` 独立控制个人信息处理，默认 REPLACE。因此普通请求携带邮箱会脱敏，而不会因为请求违规处置为 BLOCK 就直接被拦截。老策略未填写该字段时也采用这个新默认值。

不附带具有法律判定含义的默认违禁词库；词表为空时只执行已启用的其他规则。内置规则不理解语义，也不能可靠识别变体、谐音、隐喻或提示词注入。

请求扫描 POST JSON 中的 `messages[].content`、文本内容块的 `text`、`input`、`prompt`、`system`、`instructions` 及工具参数字符串等文本字段。响应覆盖 JSON 中的文本字段和 `text/*`。模型名称、角色、Token usage 等协议元数据保留。不进行图片 OCR、音视频或文件内容审核，也不声称覆盖所有厂商的内容协议。工具参数字符串经过替换后可能不再满足业务工具的输入约束，此类场景建议采用 BLOCK。

请求非 JSON 返回 400；请求超过上限返回 413。响应违规 BLOCK 返回 502，原内容不下发。`DETECT` 仍会放行内容，不是防护模式。检测结果通过 `X-AI-Security-Request` / `X-AI-Security-Response` 返回类别，不包含正文或命中的原词。

未识别到受支持文本字段时标记 `NO_TEXT_FIELDS`，不是“已审核通过”；不受支持的响应媒体类型透传并标记 `UNSUPPORTED_MEDIA_TYPE`。有此类报文的路线需要扩展文本提取逻辑，不能依赖该策略审核图片或任意自定义数据格式。

## SSE 与容量

`streamingPolicy` 必须明确选择：

| 值 | 行为 |
| --- | --- |
| BUFFER（默认） | 等上游响应结束，合并文本片段进行审核，通过后一次性返回 SSE 报文；失去逐字输出效果 |
| BLOCK | 拒绝 SSE 响应，返回 502 |
| SKIP | 显式跳过 SSE 响应审核，保留流式输出，响应头标记 STREAM_SKIPPED |

BUFFER 可以检测分散在不同 SSE 分片中的词语和个人信息。替换后的完整文本写入第一个文本片段，其余文本片段清空；事件类型、usage 和 `[DONE]` 等保留。应用不得依赖原有片段边界或逐片时间。多个 choice、工具调用和多种输出混合的应用，优先选 BLOCK，或确认其可接受合并替换后再使用 BUFFER。

`maxBodySize` 默认 1 MiB，上限 10 MiB，分别限制请求和整个响应；`responseTimeoutMs` 默认 60 秒，上限 300 秒。缓冲超限、超时、无法解析的 SSE/JSON 或压缩响应返回 502，不下发未审查的原文。审核路线应禁用上游响应压缩。并发响应会同时占用内存，应按实例容量设置上限。外部审核不自动重试。

## 外部服务与 Java 适配插件

`externalUrl` 留空时只使用内置检测；填写后先执行本地规则，再调用外部审核。外部服务收到的是已启用 PII 掩码处理的文本，而不是整份网关请求。请求与响应分别使用 `REQUEST` / `RESPONSE` 方向。外部审核获得的是每次调用的文本，不是长期对话会话。

每家服务的请求、签名、业务状态和审核结果可能不同，因此通过独立的 `gateway-moderation-spi` Java 接口扩展：

```java
public interface ModerationAdapter {
    String id();
    Request prepare(Context context);
    Decision parse(Response response) throws Exception;
}
```

- Context 提供待审文本、方向、服务地址、请求头和适配器参数。
- Request 指定 HTTP 方法、最终地址、请求头和原始字节。可在 `prepare` 中组装 JSON、表单、时间戳并对最终字节签名。
- Response 提供 HTTP 状态、响应头和原始字节，`parse` 自行解码并检查厂商业务状态。
- Decision 只能表示已明确解析出的通过或违规；HTTP/业务失败、未知状态、缺少字段和解析异常必须抛异常，不能返回通过。
- HTTP 调用、最多 64 KiB 的审核响应读取、超时、取消和失败处置由网关执行。插件不应自行发网络请求。

插件实现必须线程安全，并且是经审查的可信代码。这里没有在线 Java/Groovy 执行器或脚本沙箱；反应式超时不提供对恶意代码、死循环或阻塞线程的强制隔离。不能将第三方不可信 JAR 作为配置上传并执行。

页面只需填写：

| 配置 | 说明 |
| --- | --- |
| externalAdapter | 开发人员提供的已部署标识，默认 HTTP_JSON；未知标识属于审核失败 |
| externalUrl | 服务入口地址，留空禁用外部审核 |
| externalHeaders | 请求头的键值配置；凭证可填写 `${MODERATION_AUTH}` |
| externalOptions | 厂商适配器约定的参数，如 appId、业务场景；顶层字符串同样支持 `${ENV_NAME}` |
| externalTimeoutMs | 整次外部审核超时，默认 3000 ms，范围 100–30000 ms |
| externalFailureAction | BLOCK（默认）或 LOCAL（退回内置规则） |

环境变量在网关端解析。优先引用环境变量，避免将真实密钥存入策略；这不是管理端密钥保险库。插件参数名及类型由插件文档约定；页面键值编辑器适合字符串参数，复杂参数可通过策略 API 管理。

BLOCK 模式下，外部审核失败时请求返回 503、响应返回 502。LOCAL 模式保留本地处理结果并添加 EXTERNAL_ERROR 类别，代表审核能力下降，不能等同于外部审核通过。未知适配器不会自动套用 HTTP_JSON 协议。远端判违规但没有可信命中位置时，REPLACE 替换全部受审文本为 blockMessage，避免仅替换本地命中词后误放行。

### HTTP_JSON 参考协议

这是一个约定协议，不表示已兼容蚂蚁天鉴或其他商业服务。它发送 `POST application/json`：

```json
{"text":"待审文本","direction":"REQUEST"}
```

HTTP 必须为 2xx，响应必须含布尔类型的 flagged：

```json
{"flagged":false}
```

`true` 表示违规；空响应、缺少 flagged、字符串 `"false"` 等均为审核失败。

### 开发和部署插件

可直接复制仓库中的 `examples/moderation-adapter`。示例使用虚构协议，展示 appId、请求头、嵌套响应和业务错误检查，不是某家厂商的现成实现。

1. 编译并安装独立 SDK：`mvn -pl gateway-moderation-spi -am install -DskipTests`。
2. 插件对 `com.leoli.gateway:gateway-moderation-spi:1.0.0` 使用 provided 依赖，避免重复打包接口。可复用网关的 Jackson，但不要把不兼容版本的依赖混入主类路径。
3. 在 JAR 中提供 `META-INF/services/com.leoli.gateway.ai.security.ModerationAdapter`，每行一个实现类全名；实现类应有无参构造函数。不要同时把同一个实现注册为 Spring Bean 和 SPI 服务。
4. 示例构建：`mvn -f examples/moderation-adapter/pom.xml test package`。
5. 将插件 JAR 和必要的独立依赖随网关版本部署到启动类路径，并重启。可采用 Spring Boot PropertiesLauncher，例如：

```text
java -Dloader.path=./moderation-adapters -cp my-gateway.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

仅把 JAR 放在可执行 JAR 旁边，再执行 `java -jar` 不会自动加载。也可以把适配器作为网关构建依赖打包入 BOOT-INF/lib。现有过滤器插件上传 / 热加载入口不加载此 SPI。

启动时发现重复或非法适配器标识会失败，避免不同实例选择不同协议。先部署适配器，再填写 externalAdapter / externalUrl 并发布策略。接入蚂蚁天鉴等服务，需要对应产品的接口文档、签名规范和测试环境，完成专用适配器并联调后才可标记为已支持。

## 升级

管理端启动时现有 ReferenceDataSeeder 会更新 AI_SECURITY 的类型目录说明和配置 schema，不覆盖用户策略。关闭 `gateway.seed.reference-data` 的环境需自行执行 `sql/seed/strategy_types.sql` 中 AI_SECURITY 对应的 UPDATE。新前端使用专用表单，不需要创建新的调用记录页面。

网关现在实际执行 scanResponse；老策略若已保存该值为 true，升级后响应也会受到审核。默认 SSE BUFFER 会改变流式交付方式，发布前应明确选择 BUFFER、BLOCK 或 SKIP。
