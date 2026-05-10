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
 *
 * Configuration:
 * - 测试阶段: yaml默认值 (root/123456)
 * - 生产环境: 必须设置环境变量 SECKILL_DB_USER/SECKILL_DB_PASSWORD
 * - SQL logging disabled for production (performance + security)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "seckill.sharding.enabled", havingValue = "true", matchIfMissing = true)
public class ShardingDataSourceConfig {

    private static final int DB_COUNT = 8;
    private static final int TABLE_COUNT = 16;

    /**
     * Database configuration.
     * 测试阶段使用yaml默认值，生产环境使用环境变量。
     */
    private final String dbHost;
    private final int dbPort;
    private final String dbUser;
    private final String dbPassword;
    private final boolean sqlShowEnabled;
    private final boolean isProduction;

    /**
     * Constructor with configuration injection.
     * 测试阶段允许默认值，生产环境必须设置环境变量。
     */
    public ShardingDataSourceConfig(
            @org.springframework.beans.factory.annotation.Value("${seckill.db.host:127.0.0.1}") String dbHost,
            @org.springframework.beans.factory.annotation.Value("${seckill.db.port:3306}") int dbPort,
            @org.springframework.beans.factory.annotation.Value("${seckill.db.user:root}") String dbUser,
            @org.springframework.beans.factory.annotation.Value("${seckill.db.password:123456}") String dbPassword,
            @org.springframework.beans.factory.annotation.Value("${seckill.sharding.sql-show:false}") boolean sqlShowEnabled,
            @org.springframework.beans.factory.annotation.Value("${seckill.production-mode:false}") boolean isProduction) {

        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.sqlShowEnabled = sqlShowEnabled;
        this.isProduction = isProduction;

        // 生产环境验证凭据
        if (isProduction) {
            validateProductionCredentials();
        } else {
            log.info("测试模式: 使用yaml默认数据库配置 user={}", dbUser);
        }
    }

    /**
     * Validate database credentials for production.
     * 生产环境必须设置环境变量，拒绝硬编码默认值。
     */
    private void validateProductionCredentials() {
        // 生产环境拒绝测试默认值
        if ("root".equals(dbUser) && "123456".equals(dbPassword)) {
            throw new IllegalArgumentException(
                "生产环境禁止使用测试默认凭据！请设置环境变量: " +
                "SECKILL_DB_USER 和 SECKILL_DB_PASSWORD");
        }

        if (dbUser == null || dbUser.isEmpty()) {
            throw new IllegalArgumentException(
                "生产环境必须设置 SECKILL_DB_USER 环境变量");
        }

        if (dbPassword == null || dbPassword.isEmpty()) {
            throw new IllegalArgumentException(
                "生产环境必须设置 SECKILL_DB_PASSWORD 环境变量");
        }

        log.info("生产环境数据库配置验证通过: host={}, port={}, user={}", dbHost, dbPort, dbUser);
    }

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
        // PERFORMANCE FIX (P0): SQL logging disabled by default for production
        // Enable only for debugging with 'seckill.sharding.sql-show=true'
        props.setProperty("sql-show", String.valueOf(sqlShowEnabled));
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
                    dbHost, dbPort, dbName));
            ds.setUsername(dbUser);
            ds.setPassword(dbPassword);
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