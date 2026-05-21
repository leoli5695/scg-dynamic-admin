package com.seckill.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * 批量扣减结果DTO单元测试
 * ============================================================================
 *
 * OPTIMIZATION (P0): Pipeline批量执行优化测试
 *
 * 测试范围:
 * 1. fromLuaResult 解析成功结果 (>= 1000)
 * 2. fromLuaResult 解析失败结果 (-1, 0, -2, -99)
 * 3. 错误信息转换正确性
 * 4. 边界值测试
 *
 * @author leoli
 */
class BatchDeductResultTest {

    // ==================== fromLuaResult 成功场景 ====================

    @Test
    @DisplayName("fromLuaResult: 成功结果解析 - 返回1005表示分片索引5")
    void fromLuaResult_success_shardIndex5() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .orderNo("ORDER001")
                .traceId("trace-001")
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 1005L, 50L);

        assertTrue(result.isSuccess());
        assertEquals(5, result.getShardIndex());
        assertEquals(1005L, result.getLuaResult());
        assertEquals(50L, result.getLatencyMs());
        assertNull(result.getErrorMessage());
        assertEquals(request, result.getRequest());
    }

    @Test
    @DisplayName("fromLuaResult: 成功结果解析 - 返回1000表示分片索引0")
    void fromLuaResult_success_shardIndex0() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 1000L, 10L);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getShardIndex());
    }

    @Test
    @DisplayName("fromLuaResult: 成功结果解析 - 最大分片索引7 (返回1007)")
    void fromLuaResult_success_maxShardIndex() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10007L)
                .quantity(1)
                .build();

        // 8分片场景，最大分片索引为7
        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 1007L, 20L);

        assertTrue(result.isSuccess());
        assertEquals(7, result.getShardIndex());
    }

    // ==================== fromLuaResult 失败场景 ====================

    @Test
    @DisplayName("fromLuaResult: 已购买 - Lua返回-1")
    void fromLuaResult_alreadyBought() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, -1L, 30L);

        assertFalse(result.isSuccess());
        assertNull(result.getShardIndex());
        assertEquals("已购买（防重失败）", result.getErrorMessage());
    }

    @Test
    @DisplayName("fromLuaResult: 库存不足 - Lua返回0")
    void fromLuaResult_stockInsufficient() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 0L, 25L);

        assertFalse(result.isSuccess());
        assertNull(result.getShardIndex());
        assertEquals("库存不足", result.getErrorMessage());
    }

    @Test
    @DisplayName("fromLuaResult: 库存未预热 - Lua返回-2")
    void fromLuaResult_stockNotWarmed() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, -2L, 15L);

        assertFalse(result.isSuccess());
        assertNull(result.getShardIndex());
        assertEquals("库存未预热", result.getErrorMessage());
    }

    @Test
    @DisplayName("fromLuaResult: 执行异常 - Lua返回-99")
    void fromLuaResult_executionError() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, -99L, 100L);

        assertFalse(result.isSuccess());
        assertNull(result.getShardIndex());
        assertEquals("执行异常", result.getErrorMessage());
    }

    @Test
    @DisplayName("fromLuaResult: 未知错误码 - Lua返回-50")
    void fromLuaResult_unknownErrorCode() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, -50L, 10L);

        assertFalse(result.isSuccess());
        assertNull(result.getShardIndex());
        assertEquals("未知错误: -50", result.getErrorMessage());
    }

    // ==================== 边界值测试 ====================

    @Test
    @DisplayName("fromLuaResult: 边界值 - 999仍为失败")
    void fromLuaResult_boundary_999_isFailure() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 999L, 10L);

        assertFalse(result.isSuccess());
        assertEquals("未知错误: 999", result.getErrorMessage());
    }

    @Test
    @DisplayName("fromLuaResult: 边界值 - 1000为成功（分片索引0）")
    void fromLuaResult_boundary_1000_isSuccess() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 1000L, 10L);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getShardIndex());
    }

    @Test
    @DisplayName("fromLuaResult: 边界值 - 大数值分片索引")
    void fromLuaResult_largeValue_shardIndex() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(1)
                .build();

        // 假设有1000个分片
        BatchDeductResult result = BatchDeductResult.fromLuaResult(request, 1500L, 10L);

        assertTrue(result.isSuccess());
        assertEquals(500, result.getShardIndex());
    }

    // ==================== Builder测试 ====================

    @Test
    @DisplayName("Builder: 完整字段构建")
    void builder_fullFields() {
        BatchDeductRequest request = BatchDeductRequest.builder()
                .seckillId(1L)
                .userId(10001L)
                .quantity(2)
                .orderNo("ORDER123")
                .traceId("trace-abc")
                .build();

        BatchDeductResult result = BatchDeductResult.builder()
                .request(request)
                .luaResult(1003L)
                .success(true)
                .shardIndex(3)
                .latencyMs(50L)
                .build();

        assertTrue(result.isSuccess());
        assertEquals(3, result.getShardIndex());
        assertEquals(request, result.getRequest());
    }

    @Test
    @DisplayName("Builder: 最小字段构建")
    void builder_minimalFields() {
        BatchDeductRequest request = BatchDeductRequest.builder().build();

        BatchDeductResult result = BatchDeductResult.builder()
                .request(request)
                .luaResult(-1L)
                .success(false)
                .errorMessage("已购买")
                .build();

        assertFalse(result.isSuccess());
        assertNull(result.getShardIndex());
        assertNull(result.getLatencyMs());
    }
}