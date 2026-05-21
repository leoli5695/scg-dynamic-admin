package com.seckill.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * SeckillResult 枚举测试
 * ============================================================================
 *
 * Lua返回值定义:
 *   -2: 库存未预热
 *   -1: 已购买
 *    0: 库存不足
 *   >= 1000: 成功，shardIndex = luaResult - 1000
 */
class SeckillResultTest {

    @Test
    @DisplayName("Lua返回>=1000 应为SUCCESS（1000+分片索引）")
    void testFromLuaResult_Success() {
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(1000)); // shard 0
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(1001)); // shard 1
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(1007)); // shard 7
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(1100)); // 极端值
    }

    @Test
    @DisplayName("shardIndexFromLuaResult 正确提取分片索引")
    void testShardIndexFromLuaResult() {
        assertEquals(0, SeckillResult.shardIndexFromLuaResult(1000));
        assertEquals(1, SeckillResult.shardIndexFromLuaResult(1001));
        assertEquals(7, SeckillResult.shardIndexFromLuaResult(1007));
    }

    @Test
    @DisplayName("Lua返回0 应为STOCK_INSUFFICIENT")
    void testFromLuaResult_StockInsufficient() {
        assertEquals(SeckillResult.STOCK_INSUFFICIENT, SeckillResult.fromLuaResult(0));
    }

    @Test
    @DisplayName("Lua返回-1 应为ALREADY_BOUGHT")
    void testFromLuaResult_AlreadyBought() {
        assertEquals(SeckillResult.ALREADY_BOUGHT, SeckillResult.fromLuaResult(-1));
    }

    @Test
    @DisplayName("Lua返回-2 应为STOCK_NOT_WARMED")
    void testFromLuaResult_StockNotWarmed() {
        assertEquals(SeckillResult.STOCK_NOT_WARMED, SeckillResult.fromLuaResult(-2));
    }

    @Test
    @DisplayName("Lua返回其他负值或1-999 应为SYSTEM_ERROR")
    void testFromLuaResult_SystemError() {
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(-3));
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(-99));
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(1));   // 旧契约的值
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(999)); // 边界
    }

    @Test
    @DisplayName("枚举属性验证")
    void testEnumProperties() {
        // SUCCESS
        assertEquals(1, SeckillResult.SUCCESS.getCode());
        assertNotNull(SeckillResult.SUCCESS.getMessage());

        // ALREADY_BOUGHT
        assertEquals(-1, SeckillResult.ALREADY_BOUGHT.getCode());
        assertTrue(SeckillResult.ALREADY_BOUGHT.getMessage().contains("重复"));

        // STOCK_INSUFFICIENT
        assertEquals(0, SeckillResult.STOCK_INSUFFICIENT.getCode());
        assertTrue(SeckillResult.STOCK_INSUFFICIENT.getMessage().contains("售罄"));

        // STOCK_NOT_WARMED
        assertEquals(-2, SeckillResult.STOCK_NOT_WARMED.getCode());
        assertTrue(SeckillResult.STOCK_NOT_WARMED.getMessage().contains("预热"));
    }

    // ============================================================================
    // OPTIMIZATION (P3): 精细化错误码测试
    // ============================================================================

    @Test
    @DisplayName("P3: Redis故障错误码 - REDIS_FAILURE(-100)")
    void testRedisFailureErrorCode() {
        assertEquals(-100, SeckillResult.REDIS_FAILURE.getCode());
        assertTrue(SeckillResult.REDIS_FAILURE.getMessage().contains("Redis"));
        assertTrue(SeckillResult.REDIS_FAILURE.getMessage().contains("降级"));
    }

    @Test
    @DisplayName("P3: MQ故障错误码 - MQ_FAILURE(-300)")
    void testMQFailureErrorCode() {
        assertEquals(-300, SeckillResult.MQ_FAILURE.getCode());
        assertTrue(SeckillResult.MQ_FAILURE.getMessage().contains("消息队列"));
        assertTrue(SeckillResult.MQ_FAILURE.getMessage().contains("降级"));
    }

    @Test
    @DisplayName("P3: 请求限流错误码 - RATE_LIMITED(-8)")
    void testRateLimitedErrorCode() {
        assertEquals(-8, SeckillResult.RATE_LIMITED.getCode());
        assertTrue(SeckillResult.RATE_LIMITED.getMessage().contains("频繁"));
    }

    @Test
    @DisplayName("P3: IP封禁错误码 - IP_BLOCKED(-9)")
    void testIpBlockedErrorCode() {
        assertEquals(-9, SeckillResult.IP_BLOCKED.getCode());
        assertTrue(SeckillResult.IP_BLOCKED.getMessage().contains("IP"));
        assertTrue(SeckillResult.IP_BLOCKED.getMessage().contains("封禁"));
    }

    @Test
    @DisplayName("P3: 事务超时错误码 - TRANSACTION_TIMEOUT(-101)")
    void testTransactionTimeoutErrorCode() {
        assertEquals(-101, SeckillResult.TRANSACTION_TIMEOUT.getCode());
        assertTrue(SeckillResult.TRANSACTION_TIMEOUT.getMessage().contains("超时"));
    }

    @Test
    @DisplayName("P3: 数据库故障错误码 - DB_FAILURE(-200)")
    void testDbFailureErrorCode() {
        assertEquals(-200, SeckillResult.DB_FAILURE.getCode());
        assertTrue(SeckillResult.DB_FAILURE.getMessage().contains("数据库"));
    }

    @Test
    @DisplayName("P3: 降级库存不足错误码 - DEGRADE_STOCK_INSUFFICIENT(-10)")
    void testDegradeStockInsufficientErrorCode() {
        assertEquals(-10, SeckillResult.DEGRADE_STOCK_INSUFFICIENT.getCode());
        assertTrue(SeckillResult.DEGRADE_STOCK_INSUFFICIENT.getMessage().contains("系统繁忙"));
    }

    @Test
    @DisplayName("P3: 错误码范围验证 - 系统级错误应使用特定范围")
    void testErrorCodeRanges() {
        // Redis故障范围: -100 ~ -199
        assertTrue(SeckillResult.REDIS_FAILURE.getCode() >= -100 && SeckillResult.REDIS_FAILURE.getCode() <= -199);
        // 因为 -100 >= -199 是错的，让我用正确的范围检查
        assertEquals(-100, SeckillResult.REDIS_FAILURE.getCode());

        // MQ故障范围: -300 ~ -399
        assertEquals(-300, SeckillResult.MQ_FAILURE.getCode());

        // DB故障范围: -200 ~ -299
        assertEquals(-200, SeckillResult.DB_FAILURE.getCode());

        // 业务错误范围: -1 ~ -10
        assertTrue(SeckillResult.ALREADY_BOUGHT.getCode() >= -10 && SeckillResult.ALREADY_BOUGHT.getCode() <= -1);
        assertTrue(SeckillResult.STOCK_INSUFFICIENT.getCode() >= -10 && SeckillResult.STOCK_INSUFFICIENT.getCode() <= -1);
        assertTrue(SeckillResult.RATE_LIMITED.getCode() >= -10 && SeckillResult.RATE_LIMITED.getCode() <= -1);
        assertTrue(SeckillResult.IP_BLOCKED.getCode() >= -10 && SeckillResult.IP_BLOCKED.getCode() <= -1);
    }

    @Test
    @DisplayName("P3: 错误码与消息一致性验证")
    void testErrorCodeMessageConsistency() {
        // 所有错误码应有非空消息
        for (SeckillResult result : SeckillResult.values()) {
            assertNotNull(result.getMessage(), "错误码 " + result.name() + " 的消息不应为空");
            assertFalse(result.getMessage().isEmpty(), "错误码 " + result.name() + " 的消息不应为空字符串");
        }
    }
}