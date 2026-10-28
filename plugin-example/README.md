# 自定义路由插件开发指南

## 快速开始

这是一个示例自定义路由插件项目，展示了如何开发符合网关插件规范的插件。

### 1. 项目结构

```
plugin-example/
├── pom.xml                                    # Maven项目配置
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/gateway/plugin/
│       │       └── CustomRateLimitGatewayFilterFactory.java  # 插件主类
│       └── resources/
│           └── META-INF/
│               └── plugin-schema.json         # 配置Schema（可选）
```

### 2. 核心要求

插件必须满足以下条件：

1. **继承 `AbstractGatewayFilterFactory<Config>`**
   - Config 是内部类，用于定义配置参数

2. **命名规范**
   - 类名必须以 `GatewayFilterFactory` 结尾
   - 例如：`CustomRateLimitGatewayFilterFactory`
   - 插件名称提取：去掉 `GatewayFilterFactory` 后缀 = `CustomRateLimit`

3. **配置类（Config）**
   - 定义为 `public static class Config`
   - 每个字段对应一个配置参数
   - 建议使用 Lombok @Data 注解

4. **不要使用 @Component**
   - 插件是动态加载的，不需要Spring自动扫描

### 3. 配置Schema（可选）

在 `META-INF/plugin-schema.json` 中定义前端配置表单：

```json
{
  "fields": [
    {
      "name": "maxRequests",
      "type": "number",
      "label": "最大请求数",
      "default": 100,
      "min": 1,
      "max": 10000
    },
    {
      "name": "keyResolver",
      "type": "select",
      "label": "Key解析策略",
      "options": [
        {"label": "IP地址", "value": "IP"},
        {"label": "用户ID", "value": "USER"}
      ]
    }
  ]
}
```

前端实际支持的字段类型（见 `gateway-ui/src/features/plugin-bindings/components/ConfigFieldInput.tsx` 的 `switch` 分支）：
- `number` -> 数字输入框
- `text` -> 文本输入框（未识别的 `type` 也会落到这一分支的默认文本框）
- `select` -> 下拉选择
- `switch` -> 开关
- `textarea` -> 多行文本（JSON配置）

> **没有 `array` 类型**：写 `"type": "array"` 不会渲染成数组编辑器，只会退化为普通文本输入框。
> 需要配置数组/对象等复杂结构时，请用 `textarea` 并在其中填写 JSON——`textarea` 会尝试
> `JSON.parse` 输入内容，解析成功则按对象回传，解析失败则按原始字符串回传。

### 4. 编译打包

> **本目录不在 Maven 反应堆内**：根 `pom.xml` 的 `<modules>` 只包含 `license-client`、
> `gateway-trace-starter`、`license-server`、`gateway-moderation-spi`、`my-gateway`、
> `gateway-admin`、`demo-service`、`seckill-core-engine`，**没有 `plugin-example`**；根 `pom.xml`
> 的注释也写明 plugin-example 是独立的 `com.example` 模板、不继承父 pom。
> 因此在仓库根执行 `mvn clean package` **不会**构建本示例，CI（`.github/workflows/ci.yml`）
> 同样完全不涉及它。必须像下面这样进入本目录单独构建。

```bash
cd plugin-example
mvn clean package
```

生成的JAR文件位于 `target/custom-rate-limit-plugin-1.0.0.jar`

### 5. 上传插件

1. 登录网关管理平台
2. 进入 "插件管理" 页面
3. 点击 "上传插件" 按钮
4. 选择打包好的JAR文件
5. 等待解析和注册完成

### 6. 绑定到路由

1. 进入 "插件绑定" 页面
2. 选择目标路由
3. 点击 "绑定插件"
4. 选择已注册的插件
5. 配置参数（基于Schema自动生成表单）
6. 保存后插件即可生效

## 注意事项

1. **依赖管理**
   - Spring Cloud Gateway 依赖设置为 `provided`（本示例 `pom.xml` 中 `spring-cloud-starter-gateway`
     的 `<scope>` 确实是 `provided`），运行时由网关提供，插件 JAR 不打包 SCG 类
   - 避免引入与网关冲突的依赖版本
   - ⚠️ **本示例目前并不满足上一条**：`plugin-example/pom.xml:17` 钉的是
     `<spring-cloud-gateway.version>4.1.3</spring-cloud-gateway.version>`，而网关实际运行的是
     Spring Cloud `2025.0.3`（`my-gateway/pom.xml:23`）对应的 Spring Cloud Gateway **4.3.5**，
     两者相差 2 个 minor 版本。因为依赖是 `provided`，编译期用的是 4.1.3 的 API、运行期加载的却是
     4.3.5 的类，一旦网关侧 API 有变更就会在插件加载/执行时抛 `NoSuchMethodError` /
     `NoClassDefFoundError` 之类的错误。
   - **建议**：编写自己的插件时，把 `spring-cloud-gateway.version` 对齐到目标网关实际使用的版本
     （当前为 `4.3.5`，可从 `my-gateway` 的构建产物或 `mvn -pl my-gateway dependency:tree` 确认），
     并保持 `provided` 作用域。

2. **ClassLoader隔离**
   - 每个插件使用独立的ClassLoader
   - 插件间不会相互干扰

3. **Bean命名**
   - 系统自动生成Bean名称：`{pluginName}GatewayFilterFactory`
   - 例如：`customRateLimitGatewayFilterFactory`

4. **执行顺序**
   - 通过绑定的 `order` 参数控制执行顺序
   - 数值越小越先执行

5. **热更新**
   - 插件绑定后立即生效，无需重启网关
   - 配置修改实时更新

## 更多示例

参考网关内置的GlobalFilter实现：
- `HybridRateLimiterFilter` - Redis+本地双层限流
- `CircuitBreakerGlobalFilter` - Resilience4j熔断
- `TimeoutGlobalFilter` - 超时控制

---

有问题请参考网关文档或联系开发团队。