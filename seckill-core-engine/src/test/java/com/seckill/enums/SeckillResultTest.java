package com.seckill.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * SeckillResult 枚举测试
 * ============================================================================
 *
 * 测试 Lua 脚本返回值转换
 *
 * Lua返回值定义:
 *   -2: 库存未预热
 *   -1: 已购买
 *    0: 库存不足
 *   >0: 成功，返回分片索引
 */
class SeckillResultTest {

    @Test
    @DisplayName("Lua返回>0 应为SUCCESS（分片索引）")
    void testFromLuaResult_Success() {
        // 测试各种分片索引值（>0 表示成功）
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(1));
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(2));
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(7));
        assertEquals(SeckillResult.SUCCESS, SeckillResult.fromLuaResult(100));
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
    @DisplayName("Lua返回其他负值 应为SYSTEM_ERROR")
    void testFromLuaResult_SystemError() {
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(-3));
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(-99));
        assertEquals(SeckillResult.SYSTEM_ERROR, SeckillResult.fromLuaResult(-100));
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