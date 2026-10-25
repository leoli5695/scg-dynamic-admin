package com.seckill.redis.lua;

import com.seckill.config.SeckillConfig;
import com.seckill.service.AlertService;
import com.seckill.service.LocalFallbackService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * stock_sync.lua 真实 Redis 校验：降级窗口销量回写
 * ============================================================================
 *
 * <p>验收不变量（本类的存在理由）：
 * <ol>
 *   <li><b>可叠加、与顺序无关</b> —— 3 个实例各自在降级期间卖了若干件，按任意顺序回写，
 *       Redis 剩余库存都必须落在"真实剩余"上（= 降级前剩余 - 三实例净销量之和）。</li>
 *   <li><b>不打负</b> —— 任何分片都不得变成负数，即使 Redis 剩余吸收不下降级窗口的销量。</li>
 *   <li><b>买家补登</b> —— 每个本地买家都进入 bought set，恢复后不能二次购买；被本地回补
 *       抵消掉的用户则不得进入。</li>
 *   <li><b>回补可寻址</b> —— 回写时分片归属记进了 user_shard，事后 rollbackStock 能精确
 *       还原到原分片。</li>
 * </ol>
 *
 * <p>这些性质都是 Redis 侧的，Mock 测不出来（Mock 只会把我自己的假设回放给我），所以
 * 必须跑真 Redis。本机没有 Docker 时整个类跳过而不是失败。
 */
class StockSyncLuaIntegrationTest {

    private static final long SECKILL_ID = 987_654L;
    private static final int SHARD_COUNT = 8;
    private static final int TOTAL_STOCK = 1000;      // DB 侧初始库存
    private static final int FALLBACK_STOCK = 100;    // 每实例上架 10%

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static SeckillDeductLua deductLua;

    @BeforeAll
    static void startRedis() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "本机没有 Docker，跳过真实 Redis 的 Lua 校验（CI 上会跑）");

        redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);
        redis.start();

        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        SeckillConfig config = new SeckillConfig();
        config.setShardCount(SHARD_COUNT);
        config.setMaxBuyCount(3);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LuaScriptService luaScriptService = new LuaScriptService(
                redisTemplate,
                script("seckill_deduct.lua"),
                script("stock_rollback.lua"),
                script("stock_sync.lua"),
                registry);

        deductLua = new SeckillDeductLua(redisTemplate, config,
                Timer.builder("test.lua").register(registry), luaScriptService);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    private static DefaultRedisScript<Long> script(String fileName) throws Exception {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(Files.readString(Paths.get("src/main/resources/lua/" + fileName)));
        s.setResultType(Long.class);
        return s;
    }

    @BeforeEach
    void warmup() {
        deductLua.cleanupActivity(SECKILL_ID);
    }

    // ------------------------------------------------------------------ fixtures

    /** 降级期间的一个实例：本机上架 FALLBACK_STOCK 件，卖掉的量只在内存里 */
    private static LocalFallbackService instance(AlertService alertService) {
        SeckillConfig config = new SeckillConfig();
        config.setShardCount(SHARD_COUNT);
        config.setMaxBuyCount(3);

        LocalFallbackService svc = new LocalFallbackService(alertService,
                new com.fasterxml.jackson.databind.ObjectMapper(), config);
        ReflectionTestUtils.setField(svc, "fallbackStockRatio", 0.1);
        ReflectionTestUtils.setField(svc, "minStockThreshold", 0);
        svc.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        return svc;
    }

    private static void sell(LocalFallbackService svc, long userId, int quantity) {
        assertTrue(svc.deductStockLocal(SECKILL_ID, userId, quantity) >= 0,
                "本地扣减应成功: userId=" + userId);
    }

    // -------------------------------------------------------------------- tests

    @Test
    @DisplayName("3 个实例乱序回写：Redis 剩余落在真实值上，且买家全部补登")
    void threeInstances_syncedInArbitraryOrder_redisLandsOnTrueRemaining() {
        int redisRemainingBeforeOutage = 500;
        deductLua.warmupStock(SECKILL_ID, redisRemainingBeforeOutage);
        assertEquals(redisRemainingBeforeOutage, deductLua.getTotalStock(SECKILL_ID));

        AlertService alertA = mock(AlertService.class);
        AlertService alertB = mock(AlertService.class);
        AlertService alertC = mock(AlertService.class);

        LocalFallbackService a = instance(alertA);
        LocalFallbackService b = instance(alertB);
        LocalFallbackService c = instance(alertC);

        sell(a, 1001L, 1);
        sell(a, 1002L, 1);
        sell(a, 1003L, 1);
        sell(b, 2001L, 2);
        sell(b, 2002L, 1);
        sell(c, 3001L, 1);
        c.rollbackStockLocal(SECKILL_ID, 3001L, 1);   // 这笔被抵消，不应回写
        sell(c, 3002L, 1);

        int netSold = 3 + 3 + 1;

        // 刻意乱序：B -> C -> A
        assertEquals(3, b.syncToRedis(deductLua, SECKILL_ID));
        assertEquals(1, c.syncToRedis(deductLua, SECKILL_ID));
        assertEquals(3, a.syncToRedis(deductLua, SECKILL_ID));

        // ---- 不变量 1：剩余库存 = 降级前剩余 - 三实例净销量之和 ----
        assertEquals(redisRemainingBeforeOutage - netSold, deductLua.getTotalStock(SECKILL_ID),
                "乱序回写后 Redis 剩余必须等于真实剩余");

        // ---- 不变量 2：不打负 ----
        for (int stock : deductLua.getShardStocks(SECKILL_ID)) {
            assertTrue(stock >= 0, "任何分片都不得为负: " + deductLua.getShardStocks(SECKILL_ID));
        }

        // ---- 不变量 3：买家补登，被抵消的不登 ----
        Set<String> bought = redisTemplate.opsForSet().members("seckill:bought:" + SECKILL_ID);
        assertEquals(Set.of("1001", "1002", "1003", "2001", "2002", "3002"), bought,
                "bought set 必须正好是全部本地净买家");
        assertTrue(deductLua.hasBought(SECKILL_ID, 1001L));
        assertFalse(deductLua.hasBought(SECKILL_ID, 3001L), "被本地回补抵消的用户不应被登记");

        // 恢复后本地买家不能二次购买，新用户仍可正常购买
        assertEquals(-1, deductLua.deductStock(SECKILL_ID, 1001L, 1), "已登记的本地买家必须被防重拦住");
        assertTrue(deductLua.deductStock(SECKILL_ID, 555L, 1) >= 1000, "新用户应能正常购买");
        assertEquals(redisRemainingBeforeOutage - netSold - 1, deductLua.getTotalStock(SECKILL_ID));
    }

    @Test
    @DisplayName("回补可寻址：回写后 rollbackStock 能精确还原到原分片")
    void rollbackAfterSync_restoresExactQuantityToOriginalShard() {
        deductLua.warmupStock(SECKILL_ID, 500);

        LocalFallbackService a = instance(mock(AlertService.class));
        sell(a, 4001L, 2);
        assertEquals(2, a.syncToRedis(deductLua, SECKILL_ID));

        int afterSync = deductLua.getTotalStock(SECKILL_ID);
        assertEquals(498, afterSync);

        long rollback = deductLua.rollbackStock(SECKILL_ID, 4001L, 2);
        assertTrue(rollback >= 1000,
                "回写时必须记下分片归属，否则 rollbackStock 拿不到分片号只能返回 -1，这件货就永久丢了");
        assertEquals(afterSync + 2, deductLua.getTotalStock(SECKILL_ID));
        assertFalse(deductLua.hasBought(SECKILL_ID, 4001L), "回补后应解除防重");
    }

    @Test
    @DisplayName("同一实例重复回写：第二次不碰 Redis，不会重复扣减")
    void repeatedSyncFromSameInstance_doesNotDoubleDecrement() {
        deductLua.warmupStock(SECKILL_ID, 500);

        LocalFallbackService a = instance(mock(AlertService.class));
        sell(a, 5001L, 1);

        assertEquals(1, a.syncToRedis(deductLua, SECKILL_ID));
        int afterFirst = deductLua.getTotalStock(SECKILL_ID);

        assertEquals(-1, a.syncToRedis(deductLua, SECKILL_ID), "本地状态已摘走，第二次应报未初始化");
        assertEquals(afterFirst, deductLua.getTotalStock(SECKILL_ID), "重复回写不得再次扣减");
    }

    @Test
    @DisplayName("Redis 剩余吸收不下降级销量：分片夹到 0，缺口按超卖告警")
    void redisCannotAbsorb_shardsClampAtZeroAndCriticalAlertFires() {
        deductLua.warmupStock(SECKILL_ID, 2);   // Redis 只剩 2 件，降级期间本机却卖了 5 件

        AlertService alertService = mock(AlertService.class);
        LocalFallbackService a = instance(alertService);
        sell(a, 6001L, 1);
        sell(a, 6002L, 1);
        sell(a, 6003L, 3);

        assertEquals(5, a.syncToRedis(deductLua, SECKILL_ID));

        assertEquals(0, deductLua.getTotalStock(SECKILL_ID), "Redis 只能吸收到 0");
        for (int stock : deductLua.getShardStocks(SECKILL_ID)) {
            assertTrue(stock >= 0, "夹逼不得把任何分片打成负数: " + deductLua.getShardStocks(SECKILL_ID));
        }
        // 3 个买家确实拿到了货，恢复后必须全部拦住
        assertEquals(3, redisTemplate.opsForSet().size("seckill:bought:" + SECKILL_ID).intValue());
        verify(alertService).sendCriticalAlert(eq("降级窗口超卖"), contains("缺口 3"));
    }

    @Test
    @DisplayName("活动未预热：拒绝回写并还原本地状态，等下次重试")
    void activityNotWarmed_refusesAndRestoresLocalState() {
        deductLua.warmupStock(SECKILL_ID, 500);

        LocalFallbackService a = instance(mock(AlertService.class));
        sell(a, 7001L, 1);
        sell(a, 7002L, 1);

        deductLua.cleanupActivity(SECKILL_ID);   // 分片 Key 全没了

        assertEquals(-2, a.syncToRedis(deductLua, SECKILL_ID));
        assertEquals(FALLBACK_STOCK - 2, a.getLocalStock(SECKILL_ID),
                "回写被拒绝时销量必须还在本地，否则这两件永久丢失");
        Set<String> shardKeys = redisTemplate.keys("seckill:stock:" + SECKILL_ID + ":shard:*");
        assertTrue(shardKeys == null || shardKeys.isEmpty(),
                "未预热的活动不得被凭空造出分片 Key，实际=" + shardKeys);
    }
}
