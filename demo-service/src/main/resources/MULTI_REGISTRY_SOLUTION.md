# 多注册中心配置方案

## 方案选择

由于 Spring Cloud 的限制，不能同时启用两个自动服务注册中心。我们提供以下三种方案：

### 方案一：主从注册中心（推荐）

**配置方式**：
- 主要注册中心：Nacos（自动注册）
- 次要注册中心：Consul（只发现，不注册）

**优点**：
- 简单直接
- 避免 Bean 冲突
- 可以灵活切换

**配置文件**：`application.yml`

```yaml
spring:
  cloud:
    # Nacos - 主注册中心
    nacos:
      discovery:
        enabled: true
        register-enabled: true  # 自动注册
    
    # Consul - 从注册中心
    consul:
      enabled: true
      discovery:
        enabled: true
        register: false  # 不自动注册，避免冲突
```

### 方案二：使用 Profile 切换

**配置方式**：
- 使用 `--spring.profiles.active=nacos` 只启用 Nacos
- 使用 `--spring.profiles.active=consul` 只启用 Consul

**优点**：
- 完全隔离
- 可以根据环境选择

**配置文件**：
- `application-nacos.yml` - 只配置 Nacos
- `application-consul.yml` - 只配置 Consul

### 方案三：手动注册（高级）

**配置方式**：
- 两个注册中心都启用
- 通过代码手动控制注册逻辑

**优点**：
- 最灵活
- 可以同时注册到两个中心

**缺点**：
- 需要编写额外代码
- 复杂度较高

## 推荐方案

**使用方案一**：Nacos 作为主注册中心，Consul 作为从注册中心。

### 配置说明

#### 1. 默认配置（Nacos 主注册）

```yaml
spring:
  cloud:
    nacos:
      discovery:
        enabled: true
        register-enabled: true  # Nacos 自动注册
    
    consul:
      enabled: true
      discovery:
        enabled: true
        register: false  # Consul 不自动注册
```

#### 2. 切换到 Consul 主注册

```yaml
spring:
  cloud:
    nacos:
      discovery:
        enabled: true
        register-enabled: false  # Nacos 不自动注册
    
    consul:
      enabled: true
      discovery:
        enabled: true
        register: true  # Consul 自动注册
```

#### 3. 只使用 Nacos

```bash
java -jar demo-service.jar --spring.profiles.active=nacos-only
```

#### 4. 只使用 Consul

```bash
java -jar demo-service.jar --spring.profiles.active=consul-only
```

## Docker 环境变量

### 使用 Nacos 主注册（默认）

```bash
docker run -d -p 9000:9000 \
  -e SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=host.docker.internal:8848 \
  -e SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=true \
  -e SPRING_CLOUD_CONSUL_HOST=host.docker.internal \
  -e SPRING_CLOUD_CONSUL_DISCOVERY_REGISTER=false \
  --name demo-service demo-service:1.0.0
```

### 使用 Consul 主注册

```bash
docker run -d -p 9000:9000 \
  -e SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=host.docker.internal:8848 \
  -e SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false \
  -e SPRING_CLOUD_CONSUL_HOST=host.docker.internal \
  -e SPRING_CLOUD_CONSUL_DISCOVERY_REGISTER=true \
  --name demo-service demo-service:1.0.0
```

## 验证注册

### Nacos 注册验证

访问：http://localhost:8848/nacos/#/serviceManagement
查看服务列表中的 `demo-service`

### Consul 注册验证

访问：http://localhost:8500/ui/dc1/services
查看服务列表中的 `demo-service`

## 注意事项

1. **Bean 冲突**：Spring Cloud 不允许同时启用两个 AutoServiceRegistration
2. **解决方案**：设置一个为主注册中心（register: true），另一个为从注册中心（register: false）
3. **灵活切换**：可以通过环境变量或 Profile 灵活切换主从关系
