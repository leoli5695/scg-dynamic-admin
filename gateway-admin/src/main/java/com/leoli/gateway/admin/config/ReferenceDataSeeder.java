package com.leoli.gateway.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================================
 * 引用数据启动播种器
 * ============================================================================
 * <p>
 * 本项目的"引用/目录型"数据（策略类型、内置插件、AI 提示词、意图配置）曾放在
 * Flyway 迁移里，但迁移目录被删且从未接过 Flyway，导致全新库（ddl-auto=update 只建
 * 空表）里这些表全空——策略/插件创建页没有可选项、AI 助手退回硬编码兜底提示词。
 * <p>
 * 这里在启动时把 {@code resources/sql/seed/} 下的幂等种子脚本灌进<b>空表</b>：
 * <ul>
 *   <li>只在目标表 COUNT=0 时执行，<b>绝不覆盖</b>用户之后在 UI 里的修改；</li>
 *   <li>脚本本身是 INSERT IGNORE，重复执行也安全；</li>
 *   <li>在 ddl-auto 建表之后（ApplicationRunner 阶段）运行；</li>
 *   <li>可用 {@code gateway.seed.reference-data=false} 关闭。</li>
 * </ul>
 * 这样任何新环境起来都自带完整引用数据，不会再"跑一遍就没了"。
 *
 * @author leoli
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.seed.reference-data", havingValue = "true", matchIfMissing = true)
public class ReferenceDataSeeder implements ApplicationRunner {

    private final DataSource dataSource;
    private final ResourceLoader resourceLoader;
    private final JdbcTemplate jdbcTemplate;

    /** 目标表 -> 种子脚本（classpath）。顺序即执行顺序。 */
    private static final Map<String, String> SEEDS = new LinkedHashMap<>();

    static {
        SEEDS.put("strategy_types", "classpath:sql/seed/strategy_types.sql");
        SEEDS.put("prompts", "classpath:sql/seed/prompts.sql");
        SEEDS.put("intent_configs", "classpath:sql/seed/intent_configs.sql");
        SEEDS.put("route_plugins", "classpath:sql/builtin-plugins.sql");
    }

    /**
     * 表 -> 每次启动都重跑种子脚本（脚本必须全篇 INSERT IGNORE 幂等）。
     * strategy_types 是产品目录：新增策略类型要靠它演进到存量库，
     * 空表才播会让老环境永远拿不到新目录行。
     */
    private static final Set<String> UPSERT_EVERY_START = Set.of("strategy_types");

    public ReferenceDataSeeder(DataSource dataSource, ResourceLoader resourceLoader, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.resourceLoader = resourceLoader;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, String> e : SEEDS.entrySet()) {
            if (UPSERT_EVERY_START.contains(e.getKey())) {
                seedUpsert(e.getKey(), e.getValue());
            } else {
                seedIfEmpty(e.getKey(), e.getValue());
            }
        }
    }

    private void seedUpsert(String table, String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("Reference seed script not found: {}", location);
                return;
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(resource);
            populator.setSqlScriptEncoding("UTF-8");
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            Integer after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            log.info("Reference data upserted: {} <- {} ({} rows)", table, location, after);
        } catch (Exception ex) {
            log.warn("Reference data upsert failed for {} (skipped): {}", table, ex.getMessage());
        }
    }

    private void seedIfEmpty(String table, String location) {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            if (count != null && count > 0) {
                log.debug("Reference seed skip: {} already has {} rows", table, count);
                return;
            }
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("Reference seed script not found: {}", location);
                return;
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(resource);
            populator.setSqlScriptEncoding("UTF-8");
            populator.setContinueOnError(false);
            populator.execute(dataSource);

            Integer after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            log.info("Reference data seeded: {} <- {} ({} rows)", table, location, after);
        } catch (Exception ex) {
            // 播种失败不应阻断应用启动（表可能已由其它途径填充，或脚本与当前 schema 不完全匹配）
            log.warn("Reference data seeding failed for {} (skipped): {}", table, ex.getMessage());
        }
    }
}
