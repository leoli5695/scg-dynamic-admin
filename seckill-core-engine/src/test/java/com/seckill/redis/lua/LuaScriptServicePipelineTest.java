package com.seckill.redis.lua;

import com.seckill.dto.BatchDeductRequest;
import com.seckill.dto.BatchDeductResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * Lua脚本服务Pipeline批量执行测试
 * ============================================================================
 *
 * OPTIMIZATION (P0): Pipeline批量执行优化测试
 *
 * 测试范围:
 * 1. 空请求列表处理
 * 2. Pipeline失败时的降级处理
 * 3. 服务初始化和监控指标注册
 * 4. 分片计算验证
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class LuaScriptServicePipelineTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private DefaultRedisScript<Long> seckillDeductScript;

    @Mock
    private DefaultRedisScript<Long> stockRollbackScript;

    @Mock
    private DefaultRedisScript<Long> stockSyncScript;

    private MeterRegistry meterRegistry;
    private LuaScriptService luaScriptService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        
        lenient().when(seckillDeductScript.getSha1()).thenReturn("mock-sha1-deduct");
        lenient().when(stockRollbackScript.getSha1()).thenReturn("mock-sha1-rollback");
        lenient().when(stockSyncScript.getSha1()).thenReturn("mock-sha1-sync");

        luaScriptService = new LuaScriptService(
                redisTemplate,
                seckillDeductScript,
                stockRollbackScript,
                stockSyncScript,
                meterRegistry
        );
    }

    // ==================== 空请求处理 ====================

    @Test
    @DisplayName("Pipeline: 空请求列表返回空结果")
    void executeBatchDeduct_emptyRequest_returnsEmptyList() {
        List<BatchDeductRequest> requests = new ArrayList<>();
        
        List<BatchDeductResult> results = luaScriptService.executeBatchDeduct(requests, 8);
        
        assertTrue(results.isEmpty());
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("Pipeline: null请求列表返回空结果")
    void executeBatchDeduct_nullRequest_returnsEmptyList() {
        List<BatchDeductResult> results = luaScriptService.executeBatchDeduct(null, 8);
        
        assertTrue(results.isEmpty());
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    // ==================== 降级处理测试 ====================

    @Test
    @DisplayName("Pipeline: Pipeline异常时降级为逐个执行")
    void executeBatchDeduct_pipelineException_fallbackToSequential() {
        List<BatchDeductRequest> requests = new ArrayList<>();
        requests.add(BatchDeductRequest.builder().seckillId(1L).userId(10001L).quantity(1).build());
        requests.add(BatchDeductRequest.builder().seckillId(1L).userId(10002L).quantity(1).build());

        // Mock Pipeline抛出异常（使用SessionCallback类型匹配）
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenThrow(new RuntimeException("Pipeline failed"));
        
        // Mock 单个执行成功
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1001L)
                .thenReturn(1002L);

        List<BatchDeductResult> results = luaScriptService.executeBatchDeduct(requests, 8);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertEquals(1, results.get(0).getShardIndex());
        assertEquals(2, results.get(1).getShardIndex());
        
        // 验证降级到单个执行
        verify(redisTemplate, times(2)).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("Pipeline: 降级时单个执行失败也正确处理")
    void executeBatchDeduct_fallbackWithFailure_correctHandling() {
        List<BatchDeductRequest> requests = new ArrayList<>();
        requests.add(BatchDeductRequest.builder().seckillId(1L).userId(10001L).quantity(1).build());
        requests.add(BatchDeductRequest.builder().seckillId(1L).userId(10002L).quantity(1).build());

        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenThrow(new RuntimeException("Pipeline failed"));
        
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1001L)
                .thenThrow(new RuntimeException("Redis connection lost"));

        List<BatchDeductResult> results = luaScriptService.executeBatchDeduct(requests, 8);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertTrue(results.get(1).getErrorMessage().contains("执行异常"));
    }

    @Test
    @DisplayName("Pipeline: 降级时null结果处理为-99")
    void executeBatchDeduct_fallbackNullResult_treatedAsError() {
        List<BatchDeductRequest> requests = new ArrayList<>();
        requests.add(BatchDeductRequest.builder().seckillId(1L).userId(10001L).quantity(1).build());

        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenThrow(new RuntimeException("Pipeline failed"));
        
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

        List<BatchDeductResult> results = luaScriptService.executeBatchDeduct(requests, 8);

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals(-99L, results.get(0).getLuaResult());
        // 必须验证 execute 真被调到：mock 的默认返回就是 null，-99 也正是 null 的映射结果，
        // 少了这行，stub 匹配不上时本测试照样绿。
        verify(redisTemplate, times(1)).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("Pipeline: 降级时库存不足正确处理")
    void executeBatchDeduct_fallbackStockInsufficient_correctHandling() {
        List<BatchDeductRequest> requests = new ArrayList<>();
        requests.add(BatchDeductRequest.builder().seckillId(1L).userId(10001L).quantity(1).build());

        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenThrow(new RuntimeException("Pipeline failed"));
        
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L); // 库存不足

        List<BatchDeductResult> results = luaScriptService.executeBatchDeduct(requests, 8);

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals(0L, results.get(0).getLuaResult());
        assertEquals("库存不足", results.get(0).getErrorMessage());
    }

    // ==================== 监控指标验证 ====================

    @Test
    @DisplayName("Pipeline: 服务初始化时注册正确的监控指标")
    void serviceInit_registersCorrectMetrics() {
        assertNotNull(meterRegistry.find("seckill.lua.exec").counter());
        assertNotNull(meterRegistry.find("seckill.lua.latency").timer());
        assertNotNull(meterRegistry.find("seckill.lua.pipeline.exec").counter());
        assertNotNull(meterRegistry.find("seckill.lua.pipeline.latency").timer());
    }

    // ==================== 分片计算验证 ====================

    @Test
    @DisplayName("分片计算: userId % shardCount确定分片索引")
    void shardCalculation_correctIndex() {
        int shardCount = 8;
        assertEquals(1, 10001L % shardCount);
        assertEquals(0, 10008L % shardCount);
        assertEquals(7, 10015L % shardCount);
    }

    @Test
    @DisplayName("分片计算: 不同用户均匀分配到各分片")
    void shardCalculation_distributionCheck() {
        int shardCount = 8;
        int[] shardCounts = new int[shardCount];
        
        for (long userId = 0; userId < 1000; userId++) {
            int shardIndex = (int) (userId % shardCount);
            shardCounts[shardIndex]++;
        }
        
        for (int count : shardCounts) {
            assertEquals(125, count, "每个分片应分配125用户");
        }
    }
}