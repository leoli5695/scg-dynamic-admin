package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * 批量插入服务动态阈值测试
 * ============================================================================
 *
 * OPTIMIZATION (P2): 动态阈值调整测试
 *
 * 测试范围:
 * 1. 固定阈值模式（dynamicAdjustEnabled=false）
 * 2. 动态阈值模式 - 队列高填充率时降低阈值
 * 3. 动态阈值模式 - 队列低填充率时提高阈值
 * 4. 动态阈值模式 - 正常填充率使用默认阈值
 * 5. 边界值测试
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class BatchInsertServiceDynamicThresholdTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private AlertService alertService;

    @Mock
    private TransactionLogMapper transactionLogMapper;

    private SimpleMeterRegistry meterRegistry;
    private BatchInsertService batchInsertService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        
        // 创建服务实例
        batchInsertService = new BatchInsertService(
                orderMapper,
                alertService,
                transactionLogMapper,
                meterRegistry
        );

        // 设置配置值
        ReflectionTestUtils.setField(batchInsertService, "queueCapacity", 200);
        ReflectionTestUtils.setField(batchInsertService, "batchThreshold", 50);
        ReflectionTestUtils.setField(batchInsertService, "maxBatchWaitMs", 100);
    }

    // ==================== 固定阈值模式 ====================

    @Test
    @DisplayName("固定阈值: dynamicAdjustEnabled=false时返回固定阈值")
    void fixedThreshold_disabled_returnsFixedValue() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", false);
        
        // 初始化队列
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", 
                new java.util.concurrent.ArrayBlockingQueue<>(200));

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        assertEquals(50, threshold, "固定阈值模式应返回配置的阈值50");
    }

    // ==================== 动态阈值模式 ====================

    @Test
    @DisplayName("动态阈值: 队列空时返回提高的阈值")
    void dynamicThreshold_emptyQueue_returnsIncreasedThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 空队列
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 队列深度为0，填充率 < 30%，应提高阈值
        assertEquals(70, threshold, "队列空时应提高阈值到70");
    }

    @Test
    @DisplayName("动态阈值: 队列低填充率(<30%)时返回提高的阈值")
    void dynamicThreshold_lowFillRatio_returnsIncreasedThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 队列容量200，填充30个元素，填充率15%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 30; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 填充率15% < 30%，应提高阈值到70
        assertEquals(70, threshold, "低填充率时应提高阈值到70");
    }

    @Test
    @DisplayName("动态阈值: 队列高填充率(>70%)时返回降低的阈值")
    void dynamicThreshold_highFillRatio_returnsDecreasedThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 队列容量200，填充150个元素，填充率75%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 150; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 填充率75% > 70%，应降低阈值到30
        assertEquals(30, threshold, "高填充率时应降低阈值到30");
    }

    @Test
    @DisplayName("动态阈值: 队列正常填充率(30%~70%)时返回默认阈值")
    void dynamicThreshold_normalFillRatio_returnsDefaultThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 队列容量200，填充100个元素，填充率50%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 100; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 填充率50%在30%~70%范围，应使用默认阈值50
        assertEquals(50, threshold, "正常填充率时应使用默认阈值50");
    }

    // ==================== 边界值测试 ====================

    @Test
    @DisplayName("边界值: 填充率正好30%时返回默认阈值")
    void dynamicThreshold_exact30Percent_returnsDefaultThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 队列容量200，填充60个元素，填充率30%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 60; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 填充率正好30%，在正常范围边界，使用默认阈值
        assertEquals(50, threshold, "填充率30%时应使用默认阈值");
    }

    @Test
    @DisplayName("边界值: 填充率正好70%时返回默认阈值")
    void dynamicThreshold_exact70Percent_returnsDefaultThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 阀列容量200，填充140个元素，填充率70%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 140; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 填充率正好70%，在正常范围边界，使用默认阈值
        assertEquals(50, threshold, "填充率70%时应使用默认阈值");
    }

    @Test
    @DisplayName("边界值: 队列满时返回最小阈值30")
    void dynamicThreshold_queueFull_returnsMinThreshold() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        
        // 队列满
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 200; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 填充率100% > 70%，应降低阈值到30
        assertEquals(30, threshold, "队列满时应返回最小阈值30");
    }

    // ==================== 阈值边界约束测试 ====================

    @Test
    @DisplayName("阈值约束: 降低阈值不应低于30")
    void dynamicThreshold_decrease_notBelowMin() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        ReflectionTestUtils.setField(batchInsertService, "batchThreshold", 20); // 设置较小的默认阈值
        
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        for (int i = 0; i < 180; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 即使默认阈值只有20，降低后也不应低于30
        assertEquals(30, threshold, "降低阈值不应低于最小值30");
    }

    @Test
    @DisplayName("阈值约束: 提高阈值不应高于70")
    void dynamicThreshold_increase_notAboveMax() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        ReflectionTestUtils.setField(batchInsertService, "batchThreshold", 80); // 设置较大的默认阈值
        
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(200);
        // 空队列
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        // 即使默认阈值是80，提高后也不应高于70
        assertEquals(70, threshold, "提高阈值不应高于最大值70");
    }

    // ==================== 不同队列容量测试 ====================

    @Test
    @DisplayName("不同容量: 小队列(容量100)的动态阈值")
    void dynamicThreshold_smallQueue_correctBehavior() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        ReflectionTestUtils.setField(batchInsertService, "queueCapacity", 100);
        
        // 容量100，填充80个，填充率80%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(100);
        for (int i = 0; i < 80; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        assertEquals(30, threshold, "小队列高填充率时应降低阈值");
    }

    @Test
    @DisplayName("不同容量: 大队列(容量500)的动态阈值")
    void dynamicThreshold_largeQueue_correctBehavior() {
        ReflectionTestUtils.setField(batchInsertService, "dynamicAdjustEnabled", true);
        ReflectionTestUtils.setField(batchInsertService, "queueCapacity", 500);
        
        // 容量500，填充100个，填充率20%
        java.util.concurrent.ArrayBlockingQueue<Object> queue = 
                new java.util.concurrent.ArrayBlockingQueue<>(500);
        for (int i = 0; i < 100; i++) {
            queue.offer(new Object());
        }
        ReflectionTestUtils.setField(batchInsertService, "orderQueue", queue);

        int threshold = ReflectionTestUtils.invokeMethod(batchInsertService, "getDynamicThreshold");

        assertEquals(70, threshold, "大队列低填充率时应提高阈值");
    }
}