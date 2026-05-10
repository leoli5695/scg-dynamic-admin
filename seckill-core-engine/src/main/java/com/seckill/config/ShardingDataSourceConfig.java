package com.seckill.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepositoryConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.keygen.KeyGenerateStrategyConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.*;

/**
 * ============================================================================
 * ShardingSphere Java API 分库分表配置
 * ============================================================================
 *
 * 使用 Java API 配置替代 YAML 配置，避免 SnakeYAML 2.x 版本兼容性问题
 *
 * 分片策略:
 * - 分库: user_id % 8 (8个分库)
 * - 分表: user_id % 16 (每个库16张表)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "seckill.sharding.enabled", havingValue = "true", matchIfMissing = true)
public class ShardingDataSourceConfig {

    private static final int DB_COUNT = 8;
    private static final int TABLE_COUNT = 16;
    private static final String DB_HOST = "127.0.0.1";
    private static final int DB_PORT = 3306;
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "123456";

    /**
     * 创建 ShardingSphere 数据源（替代 YAML 配置）
     */
    @Bean
    @Primary
    public DataSource shardingSphereDataSource() throws Exception {
        log.info("Initializing ShardingSphere DataSource via Java API...");

        // 1. 创建实际数据源映射
        Map<String, DataSource> dataSourceMap = createActualDataSources();

        // 2. 配置分片规则
        ShardingRuleConfiguration shardingRuleConfig = createShardingRuleConfiguration();

        // 3. 创建 ShardingSphere 数据源
        Collection<org.apache.shardingsphere.infra.config.rule.RuleConfiguration> ruleConfigs =
                Collections.singletonList(shardingRuleConfig);

        Properties props = new Properties();
        props.setProperty("sql-show", "true");
        props.setProperty("sql-show-format", "true");

        DataSource dataSource = ShardingSphereDataSourceFactory.createDataSource(
                "seckill_schema",
                createModeConfiguration(),  // 单机模式配置
                dataSourceMap,
                ruleConfigs,
                props
        );

        log.info("ShardingSphere DataSource initialized successfully with {} databases", DB_COUNT);
        return dataSource;
    }

    /**
     * 创建单机模式配置
     */
    private ModeConfiguration createModeConfiguration() {
        // 使用 JDBC 存储的单机模式（官方文档推荐）
        StandalonePersistRepositoryConfiguration repositoryConfig = 
                new StandalonePersistRepositoryConfiguration("JDBC", new Properties());
        return new ModeConfiguration("Standalone", repositoryConfig);
    }

    /**
     * 创建 8 个实际数据源
     */
    private Map<String, DataSource> createActualDataSources() {
        Map<String, DataSource> dataSourceMap = new HashMap<>();

        for (int i = 0; i < DB_COUNT; i++) {
            String dsName = "ds_" + i;
            String dbName = "seckill_db_" + i;

            HikariDataSource ds = new HikariDataSource();
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                    DB_HOST, DB_PORT, dbName));
            ds.setUsername(DB_USER);
            ds.setPassword(DB_PASSWORD);
            ds.setMaximumPoolSize(20);
            ds.setMinimumIdle(5);
            ds.setConnectionTimeout(30000);
            ds.setIdleTimeout(600000);
            ds.setMaxLifetime(1800000);

            dataSourceMap.put(dsName, ds);
            log.debug("Created datasource: {} -> {}", dsName, dbName);
        }

        return dataSourceMap;
    }

    /**
     * 配置分片规则
     */
    private ShardingRuleConfiguration createShardingRuleConfiguration() {
        ShardingRuleConfiguration config = new ShardingRuleConfiguration();

        // 配置 seckill_order 表的分片规则（使用构造函数）
        ShardingTableRuleConfiguration orderTableRule = new ShardingTableRuleConfiguration(
                "seckill_order", "ds_${0..7}.order_${0..15}");
        orderTableRule.setDatabaseShardingStrategy(
                new StandardShardingStrategyConfiguration("user_id", "order_db_inline"));
        orderTableRule.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration("user_id", "order_table_inline"));
        orderTableRule.setKeyGenerateStrategy(
                new KeyGenerateStrategyConfiguration("id", "snowflake"));

        config.getTables().add(orderTableRule);

        // 配置 transaction_log 表（单库单表）
        ShardingTableRuleConfiguration transactionLogRule = new ShardingTableRuleConfiguration(
                "transaction_log", "ds_0.transaction_log");
        config.getTables().add(transactionLogRule);

        // 配置 seckill_activity 表（单库单表）
        ShardingTableRuleConfiguration activityRule = new ShardingTableRuleConfiguration(
                "seckill_activity", "ds_0.seckill_activity");
        config.getTables().add(activityRule);

        // 配置 seckill_product 表（单库单表）
        ShardingTableRuleConfiguration productRule = new ShardingTableRuleConfiguration(
                "seckill_product", "ds_0.seckill_product");
        config.getTables().add(productRule);

        // 配置 stock_reconciliation 表（单库单表）
        ShardingTableRuleConfiguration reconciliationRule = new ShardingTableRuleConfiguration(
                "stock_reconciliation", "ds_0.stock_reconciliation");
        config.getTables().add(reconciliationRule);

        // 配置分片算法
        Properties dbInlineProps = new Properties();
        dbInlineProps.setProperty("algorithm-expression", "ds_${user_id % 8}");
        config.getShardingAlgorithms().put("order_db_inline",
                new AlgorithmConfiguration("INLINE", dbInlineProps));

        Properties tableInlineProps = new Properties();
        tableInlineProps.setProperty("algorithm-expression", "order_${user_id % 16}");
        config.getShardingAlgorithms().put("order_table_inline",
                new AlgorithmConfiguration("INLINE", tableInlineProps));

        // 配置主键生成器
        Properties snowflakeProps = new Properties();
        snowflakeProps.setProperty("worker-id", "1");
        config.getKeyGenerators().put("snowflake",
                new AlgorithmConfiguration("SNOWFLAKE", snowflakeProps));

        return config;
    }
}