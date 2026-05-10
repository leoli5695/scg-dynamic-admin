package com.seckill.service;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * 数据库连接池监控服务
 * ============================================================================
 * <p>
 * 监控指标:
 * 1. 活动连接数 - 当前正在使用的连接
 * 2. 空闲连接数 - 连接池中的空闲连接
 * 3. 总连接数 - 连接池中的总连接数
 * 4. 等待连接数 - 正在等待获取连接的线程
 * 5. 连接获取耗时 - 获取连接的平均耗时
 * <p>
 * 健康检查:
 * - 每30秒检查一次连接池状态
 * - 连接数超过阈值时告警
 * - 连接获取失败时触发告警
 * <p>
 * 生产建议:
 * - 配置HikariCP的metricRegistry
 * - 使用Prometheus采集指标
 * - 配置Grafana监控面板
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceMonitorService {

    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;

    /**
     * 数据源缓存（支持多数据源）
     */
    private final Map<String, HikariDataSource> dataSourceCache = new ConcurrentHashMap<>();

    /**
     * 连接池告警阈值
     */
    private static final double POOL_USAGE_THRESHOLD = 0.8;  // 80%
    private static final int WAIT_THREAD_THRESHOLD = 10;     // 等待线程数

    /**
     * ============================================================================
     * 【P2-20修复】应用启动时自动注册监控指标
     * ============================================================================
     * <p>
     * 尝试从 DataSource 中提取 HikariDataSource 并注册监控指标
     * 支持 ShardingSphere 包装的 DataSource
     */
    @PostConstruct
    public void autoRegisterMetrics() {
        log.info("自动注册数据源监控指标...");

        try {
            // 尝试从 DataSource 中获取 HikariDataSource
            // ShardingSphere 包装了原始 DataSource，需要解包
            HikariDataSource hikariDataSource = unwrapHikariDataSource(dataSource);

            if (hikariDataSource != null) {
                String poolName = hikariDataSource.getPoolName();
                if (poolName == null || poolName.isEmpty()) {
                    poolName = "default";
                }
                registerMetrics(poolName, hikariDataSource);
                log.info("数据源监控指标自动注册成功: pool={}", poolName);
            } else {
                log.warn("无法从 DataSource 中提取 HikariDataSource，监控指标未注册");
            }
        } catch (Exception e) {
            log.warn("自动注册数据源监控指标失败: {}", e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 解包获取 HikariDataSource
     * ============================================================================
     * <p>
     * 支持从 ShardingSphere 包装的 DataSource 中提取原始 HikariDataSource
     */
    private HikariDataSource unwrapHikariDataSource(DataSource dataSource) {
        // 如果本身就是 HikariDataSource
        if (dataSource instanceof HikariDataSource) {
            return (HikariDataSource) dataSource;
        }

        // 尝试通过反射解包 ShardingSphere DataSource
        try {
            // ShardingSphere 的 ShardingSphereDataSource 包含实际的数据源
            if (dataSource.getClass().getName().contains("ShardingSphereDataSource")) {
                // 通过反射获取 context -> dataSourceMap
                java.lang.reflect.Field contextField = dataSource.getClass().getDeclaredField("context");
                contextField.setAccessible(true);
                Object context = contextField.get(dataSource);

                if (context != null) {
                    // 获取 dataSourceMap
                    java.lang.reflect.Field dataSourceMapField = context.getClass().getDeclaredField("dataSourceMap");
                    dataSourceMapField.setAccessible(true);
                    Object dataSourceMap = dataSourceMapField.get(context);

                    if (dataSourceMap instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) dataSourceMap;
                        if (!map.isEmpty()) {
                            // 取第一个数据源作为监控目标
                            Object firstDataSource = map.values().iterator().next();
                            if (firstDataSource instanceof HikariDataSource) {
                                return (HikariDataSource) firstDataSource;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解包 DataSource 失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * ============================================================================
     * 注册监控指标
     * ============================================================================
     */
    public void registerMetrics(String poolName, HikariDataSource hikariDataSource) {
        dataSourceCache.put(poolName, hikariDataSource);

        // 注册活动连接数
        Gauge.builder("seckill.datasource.active", hikariDataSource, ds -> ds.getHikariPoolMXBean().getActiveConnections())
                .description("活动连接数")
                .tag("pool", poolName)
                .register(meterRegistry);

        // 注册空闲连接数
        Gauge.builder("seckill.datasource.idle", hikariDataSource, ds -> ds.getHikariPoolMXBean().getIdleConnections())
                .description("空闲连接数")
                .tag("pool", poolName)
                .register(meterRegistry);

        // 注册总连接数
        Gauge.builder("seckill.datasource.total", hikariDataSource, ds -> ds.getHikariPoolMXBean().getTotalConnections())
                .description("总连接数")
                .tag("pool", poolName)
                .register(meterRegistry);

        // 注册等待连接数
        Gauge.builder("seckill.datasource.waiting", hikariDataSource, ds -> ds.getHikariPoolMXBean().getThreadsAwaitingConnection())
                .description("等待连接数")
                .tag("pool", poolName)
                .register(meterRegistry);

        log.info("数据源监控指标注册完成: pool={}", poolName);
    }

    /**
     * ============================================================================
     * 定时健康检查（每30秒）
     * ============================================================================
     */
    @Scheduled(fixedRate = 30000)
    public void healthCheck() {
        dataSourceCache.forEach((poolName, hikariDataSource) -> {
            try {
                var poolMXBean = hikariDataSource.getHikariPoolMXBean();

                int active = poolMXBean.getActiveConnections();
                int idle = poolMXBean.getIdleConnections();
                int total = poolMXBean.getTotalConnections();
                int waiting = poolMXBean.getThreadsAwaitingConnection();
                int max = hikariDataSource.getMaximumPoolSize();

                double usage = (double) active / max;

                log.debug("连接池状态: pool={}, active={}, idle={}, total={}, waiting={}, usage={}",
                        poolName, active, idle, total, waiting, String.format("%.2f%%", usage * 100));

                // 检查告警
                if (usage > POOL_USAGE_THRESHOLD) {
                    log.warn("连接池使用率过高: pool={}, usage={:.2f}%, threshold={}%",
                            poolName, usage * 100, POOL_USAGE_THRESHOLD * 100);
                }

                if (waiting > WAIT_THREAD_THRESHOLD) {
                    log.warn("等待连接线程过多: pool={}, waiting={}, threshold={}",
                            poolName, waiting, WAIT_THREAD_THRESHOLD);
                }

            } catch (Exception e) {
                log.error("连接池健康检查失败: pool={}, error={}", poolName, e.getMessage());
            }
        });
    }

    /**
     * ============================================================================
     * 测试连接是否可用
     * ============================================================================
     */
    public boolean testConnection() {
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(5);  // 5秒超时
            if (!valid) {
                log.warn("数据库连接测试失败: connection invalid");
            }
            return valid;
        } catch (SQLException e) {
            log.error("数据库连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ============================================================================
     * 获取连接池统计信息
     * ============================================================================
     */
    public PoolStats getPoolStats(String poolName) {
        HikariDataSource hikariDataSource = dataSourceCache.get(poolName);
        if (hikariDataSource == null) {
            return null;
        }

        try {
            var poolMXBean = hikariDataSource.getHikariPoolMXBean();

            return new PoolStats(
                    poolName,
                    poolMXBean.getActiveConnections(),
                    poolMXBean.getIdleConnections(),
                    poolMXBean.getTotalConnections(),
                    poolMXBean.getThreadsAwaitingConnection(),
                    hikariDataSource.getMaximumPoolSize(),
                    hikariDataSource.getMinimumIdle()
            );
        } catch (Exception e) {
            log.error("获取连接池统计失败: pool={}", poolName, e);
            return null;
        }
    }

    /**
     * ============================================================================
     * 连接池统计信息
     * ============================================================================
     */
    public static class PoolStats {
        private final String poolName;
        private final int activeConnections;
        private final int idleConnections;
        private final int totalConnections;
        private final int threadsAwaiting;
        private final int maxPoolSize;
        private final int minIdle;

        public PoolStats(String poolName, int activeConnections, int idleConnections,
                         int totalConnections, int threadsAwaiting, int maxPoolSize, int minIdle) {
            this.poolName = poolName;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.totalConnections = totalConnections;
            this.threadsAwaiting = threadsAwaiting;
            this.maxPoolSize = maxPoolSize;
            this.minIdle = minIdle;
        }

        public double getUsage() {
            return (double) activeConnections / maxPoolSize;
        }

        public String getPoolName() {
            return poolName;
        }

        public int getActiveConnections() {
            return activeConnections;
        }

        public int getIdleConnections() {
            return idleConnections;
        }

        public int getTotalConnections() {
            return totalConnections;
        }

        public int getThreadsAwaiting() {
            return threadsAwaiting;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public int getMinIdle() {
            return minIdle;
        }

        @Override
        public String toString() {
            return String.format("PoolStats{pool=%s, active=%d, idle=%d, total=%d, waiting=%d, max=%d, usage=%.2f%%}",
                    poolName, activeConnections, idleConnections, totalConnections,
                    threadsAwaiting, maxPoolSize, getUsage() * 100);
        }
    }
}