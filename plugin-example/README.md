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

支持的字段类型：
- `number` -> 数字输入框
- `text` -> 文本输入框
- `select` -> 下拉选择
- `switch` -> 开关
- `textarea` -> 多行文本（JSON配置）
- `array` -> 数组列表

### 4. 编译打包

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
   - Spring Cloud Gateway 依赖设置为 `provided`
   - 避免引入与网关冲突的依赖版本

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