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
}