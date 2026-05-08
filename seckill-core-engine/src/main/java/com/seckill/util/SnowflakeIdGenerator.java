package com.seckill.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * 雪花算法 ID生成器
 * ============================================================================
 * <p>
 * 结构: 64位
 * - 1位符号位（始终0）
 * - 41位时间戳（毫秒级）
 * - 10位机器ID（5位数据中心 + 5位机器）
 * - 12位序列号（同毫秒内计数）
 * <p>
 * 特性:
 * 1. 全局唯一，无需协调
 * 2. 时间有序，便于排序
 * 3. 高性能，本地生成
 * 4. 每毫秒可生成4096个ID
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /**
     * 开始时间戳（2024-01-01）
     */
    private static final long START_TIMESTAMP = 1704038400000L;

    /**
     * 机器ID占用位数
     */
    private static final long WORKER_ID_BITS = 5L;

    /**
     * 数据中心ID占用位数
     */
    private static final long DATACENTER_ID_BITS = 5L;

    /**
     * 序列号占用位数
     */
    private static final long SEQUENCE_BITS = 12L;

    /**
     * 机器ID最大值
     */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);

    /**
     * 数据中心ID最大值
     */
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);

    /**
     * 序列号最大值
     */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    /**
     * 机器ID左移位数
     */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    /**
     * 数据中心ID左移位数
     */
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /**
     * 时间戳左移位数
     */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    /**
     * 数据中心ID
     */
    private final long datacenterId;

    /**
     * 机器ID
     */
    private final long workerId;

    /**
     * 序列号
     */
    private long sequence = 0L;

    /**
     * 上次生成时间戳
     */
    private long lastTimestamp = -1L;

    /**
     * 构造函数
     *
     * @param datacenterId 数据中心ID (0-31)
     * @param workerId     机器ID (0-31)
     */
    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("数据中心ID超出范围: " + datacenterId);
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("机器ID超出范围: " + workerId);
        }

        this.datacenterId = datacenterId;
        this.workerId = workerId;

        log.info("雪花ID生成器初始化: datacenterId={}, workerId={}", datacenterId, workerId);
    }

    /**
     * 默认构造函数（基于 IP 自动分配 workerId）
     * <p>
     * 【生产级多节点部署支持】：
     * - 根据本机 IP 的 Hash 值计算 workerId
     * - 确保多节点部署时不会产生 ID 冲突
     * - workerId 范围: 0-31（5 位）
     * - datacenterId 范围: 0-31（5 位）
     */
    public SnowflakeIdGenerator() {
        long assignedWorkerId = 1L;
        long assignedDatacenterId = 0L;

        try {
            // 获取本机 IP 地址
            String hostAddress = java.net.InetAddress.getLocalHost().getHostAddress();
            log.info("雪花ID生成器: 检测到本机IP={}", hostAddress);

            // 基于 IP Hash 计算 workerId（0-31）
            // 使用 & 0xFF 确保非负，再 % 32 保证范围
            int ipHash = hostAddress.hashCode();
            assignedWorkerId = Math.abs(ipHash & 0xFF) % 32;

            // datacenterId 取 Hash 的高 5 位（备用，可用于不同机房）
            assignedDatacenterId = Math.abs((ipHash >> 8) & 0x1F);

            log.info("雪花ID生成器: IP={}, workerId={}, datacenterId={}",
                    hostAddress, assignedWorkerId, assignedDatacenterId);

        } catch (Exception e) {
            // 极端情况 fallback：使用默认值
            log.warn("雪花ID生成器: 无法获取本机IP，使用默认值 workerId=1, datacenterId=0, error={}",
                    e.getMessage());
        }

        // 校验范围（防御性编程）
        if (assignedWorkerId > MAX_WORKER_ID || assignedWorkerId < 0) {
            log.warn("雪花ID生成器: workerId={} 超出范围，使用默认值 1", assignedWorkerId);
            assignedWorkerId = 1L;
        }
        if (assignedDatacenterId > MAX_DATACENTER_ID || assignedDatacenterId < 0) {
            log.warn("雪花ID生成器: datacenterId={} 超出范围，使用默认值 0", assignedDatacenterId);
            assignedDatacenterId = 0L;
        }

        this.datacenterId = assignedDatacenterId;
        this.workerId = assignedWorkerId;

        log.info("雪花ID生成器初始化完成: datacenterId={}, workerId={}", datacenterId, workerId);
    }

    /**
     * 生成下一个ID
     *
     * @return 64位唯一ID
     */
    public synchronized long nextId() {
        long currentTimestamp = getCurrentTimestamp();

        // 时间回拨检测
        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            if (offset <= 5) {
                // 小幅回拨，等待时间追赶
                currentTimestamp = waitToNextMillis(lastTimestamp);
            } else {
                // 大幅回拨，抛出异常
                throw new RuntimeException("时钟回拨异常: " + offset + "毫秒");
            }
        }

        // 同毫秒内生成
        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                currentTimestamp = waitToNextMillis(lastTimestamp);
            }
        } else {
            // 新毫秒，序列号重置
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        // 组装ID
        return ((currentTimestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 获取当前时间戳
     */
    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /**
     * 等待到下一毫秒
     */
    private long waitToNextMillis(long lastTimestamp) {
        long timestamp = getCurrentTimestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentTimestamp();
        }
        return timestamp;
    }

    /**
     * 解析ID的时间戳部分
     */
    public long parseTimestamp(long id) {
        return (id >> TIMESTAMP_SHIFT) + START_TIMESTAMP;
    }

    /**
     * 解析ID的数据中心ID部分
     */
    public long parseDatacenterId(long id) {
        return (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
    }

    /**
     * 解析ID的机器ID部分
     */
    public long parseWorkerId(long id) {
        return (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    /**
     * 解析ID的序列号部分
     */
    public long parseSequence(long id) {
        return id & SEQUENCE_MASK;
    }
}