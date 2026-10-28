package com.seckill.redis.lua;

import com.seckill.config.SeckillConfig;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * applyLocalSales 与 stock_sync.lua 之间的参数契约
 * ============================================================================
 *
 * <p>stock_sync.lua 按固定布局解析 ARGV：{@code ARGV[1]} 是 shardCount，之后每 3 个一组
 * {@code (userId, quantity, preferredShard)}。这个布局只有 Java 侧这一处在写，一旦错位，
 * 脚本会把 userId 当成件数去扣库存 —— 所以这里把展开结果逐位钉死。
 *
 * <p>脚本自身的分片摊扣 / 不打负 / 补登 bought set 行为由
 * {@code StockSyncLuaIntegrationTest}（真实 Redis）覆盖。
 */
@ExtendWith(MockitoExtension.class)
class SeckillDeductLuaSyncArgsTest {

    private static final Long SECKILL_ID = 42L;
    private static final int SHARD_COUNT = 8;

    private static final List<String> EXPECTED_KEYS = StockKeys.script(SECKILL_ID, SHARD_COUNT, false, false);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private LuaScriptService luaScriptService;

    @Captor
    private ArgumentCaptor<String[]> argsCaptor;

    private SeckillDeductLua deductLua;

    @BeforeEach
    void setUp() {
        SeckillConfig config = new SeckillConfig();
        config.setShardCount(SHARD_COUNT);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        deductLua = new SeckillDeductLua(redisTemplate, config,
                Timer.builder("test.lua").register(registry), luaScriptService);
    }

    @Test
    @DisplayName("ARGV 布局: shardCount + 每个买家一组 (userId, quantity, preferredShard)")
    void flattensSalesIntoShardCountThenTriples() {
        Map<Long, Integer> sales = new LinkedHashMap<>();
        sales.put(101L, 2);
        sales.put(7L, 1);

        when(luaScriptService.executeStockSyncScript(anyList(), any(String[].class))).thenReturn(3L);

        assertEquals(3L, deductLua.applyLocalSales(SECKILL_ID, sales));

        verify(luaScriptService).executeStockSyncScript(eq(EXPECTED_KEYS), argsCaptor.capture());
        // preferredShard = userId % shardCount，与 deductStock 的选片规则一致
        assertArrayEquals(
                new String[]{"8", "101", "2", "5", "7", "1", "7"},
                argsCaptor.getValue(),
                "ARGV 错位会让脚本把 userId 当成件数去扣库存");
    }

    @Test
    @DisplayName("被本地回补抵消的买家（件数 <= 0）不进 ARGV")
    void skipsNonPositiveQuantities() {
        Map<Long, Integer> sales = new LinkedHashMap<>();
        sales.put(101L, 0);
        sales.put(102L, -1);
        sales.put(103L, 1);

        when(luaScriptService.executeStockSyncScript(anyList(), any(String[].class))).thenReturn(1L);

        deductLua.applyLocalSales(SECKILL_ID, sales);

        verify(luaScriptService).executeStockSyncScript(eq(EXPECTED_KEYS), argsCaptor.capture());
        assertArrayEquals(new String[]{"8", "103", "1", "7"}, argsCaptor.getValue());
    }

    @Test
    @DisplayName("没有净销量时不碰 Redis")
    void noNetSales_neverTouchesRedis() {
        Map<Long, Integer> allCancelled = new LinkedHashMap<>();
        allCancelled.put(101L, 0);

        assertEquals(0L, deductLua.applyLocalSales(SECKILL_ID, allCancelled));
        assertEquals(0L, deductLua.applyLocalSales(SECKILL_ID, Map.of()));
        assertEquals(0L, deductLua.applyLocalSales(SECKILL_ID, null));

        verifyNoInteractions(luaScriptService);
    }

    @Test
    @DisplayName("脚本返回 null 必须映射成 -99，不能被当成'扣了 0 件'")
    void nullScriptResult_mapsToLuaError() {
        when(luaScriptService.executeStockSyncScript(anyList(), any(String[].class))).thenReturn(null);

        assertEquals(SeckillDeductLua.LUA_ERROR, deductLua.applyLocalSales(SECKILL_ID, Map.of(101L, 1)),
                "调用方靠负值判断回写失败并还原本地状态，null 不能悄悄变成 0");
    }

    @Test
    @DisplayName("脚本返回值原样透传（含 -2 未预热与不足额吸收）")
    void scriptResultPassedThroughUnchanged() {
        when(luaScriptService.executeStockSyncScript(anyList(), any(String[].class))).thenReturn(-2L, 1L);

        assertEquals(-2L, deductLua.applyLocalSales(SECKILL_ID, Map.of(101L, 1)));
        assertEquals(1L, deductLua.applyLocalSales(SECKILL_ID, Map.of(101L, 2)),
                "applied < requested 表示 Redis 吸收不下，调用方要据此告警");
    }
}
