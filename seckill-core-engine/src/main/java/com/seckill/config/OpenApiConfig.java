package com.seckill.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * ============================================================================
 * OpenAPI/Swagger Configuration
 * ============================================================================
 *
 * API Documentation Configuration:
 * - Swagger UI: http://localhost:8080/swagger-ui.html
 * - OpenAPI JSON: http://localhost:8080/v3/api-docs
 *
 * Features:
 * - API 分组（秒杀、库存、订单、监控）
 * - 请求/响应示例
 * - 错误码文档
 *
 * @author leoli
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI seckillOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(servers())
                .tags(tags());
    }

    private Info apiInfo() {
        return new Info()
                .title("Seckill Core Engine API")
                .description("""
                    ## 生产级高并发秒杀核心引擎
                    
                    ### 核心能力
                    - **分库分表方案**: 8库 × 16表，支持千万级订单
                    - **Redis热点分片**: 8个分片避免单Key热点
                    - **攒批落库优化**: TPS从2000提升到10000+
                    - **多层降级方案**: Redis降级、MQ降级、本地缓存降级
                    
                    ### API 分组
                    - **秒杀接口**: 秒杀请求、库存预热、库存对账
                    - **订单接口**: 订单查询、订单状态更新
                    - **监控接口**: Prometheus指标、健康检查
                    
                    ### 错误码规范
                    | 类别 | 范围 | 示例 |
                    |------|------|------|
                    | 业务错误 | -1 ~ -10 | STOCK_INSUFFICIENT(0), ALREADY_BOUGHT(-1) |
                    | 系统错误 | -99 | SYSTEM_ERROR |
                    | Redis故障 | -100 ~ -199 | REDIS_CONNECTION_FAILURE(-100) |
                    | DB故障 | -200 ~ -299 | DB_CONNECTION_FAILURE(-201) |
                    | MQ故障 | -300 ~ -399 | MQ_BROKER_FAILURE(-302) |
                    
                    ### 限流策略
                    - IP限流: 10次/秒 (滑动窗口)
                    - 用户限流: 5次/秒 (滑动窗口)
                    - Consumer限流: 1000次/秒 (分布式滑动窗口)
                    """)
                .version("1.0.0")
                .contact(new Contact()
                        .name("Seckill Team")
                        .email("seckill@example.com"))
                .license(new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0"));
    }

    private List<Server> servers() {
        return Arrays.asList(
                new Server()
                        .url("http://localhost:8080")
                        .description("本地开发环境"),
                new Server()
                        .url("http://production-server:8080")
                        .description("生产环境")
        );
    }

    private List<Tag> tags() {
        return Arrays.asList(
                new Tag().name("seckill").description("秒杀核心接口"),
                new Tag().name("stock").description("库存管理接口"),
                new Tag().name("order").description("订单管理接口"),
                new Tag().name("monitoring").description("监控与健康检查"),
                new Tag().name("admin").description("管理接口（需权限）")
        );
    }
}