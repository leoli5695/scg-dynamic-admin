package com.seckill.service;

import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * RedisDegradeService 恢复路径测试
 * ============================================================================
 *
 * <p>盯的是两件以前都坏过的事：
 *
 * <ol>
 *   <li><b>自动恢复死锁</b>：{@code isRedisAvailable()} 一看 {@code degraded} 为真就直接返回
 *       false，而 {@code attemptRecover()} 又恰好用它来判断"是否真的恢复了"，
 *       {@code healthCheck()} 也用它决定要不要 {@code recordSuccess()}。三处互相锁死，
 *       结果是：一旦降级就永远出不来，只能人工 {@code manualRecover()}。
 *       现在探活走 {@code probeRedis()}，不看降级开关。</li>
 *   <li><b>手动恢复漏回写</b>：{@code manualRecover()} 以前只翻开关、清标记就返回，
 *       降级窗口卖掉的量永远不回 Redis。那批买家不在 bought set 里（能再买一次），
 *       Redis 也没被扣（对账会把它当剩余库存再放出去 → 超卖）。
 *       自动/手动两条出口现在共用 {@code syncLocalSalesBack()}。</li>
 * </ol>
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisDegradeServiceRecoveryTest {

    private static final int DEGRADE_THRESHOLD = 5;
    private static final int RECOVERY_THRESHOLD = 10;
    private static final String MANUAL_DEGRADE_KEY = "seckill:degrade:manual";

    @Mock private AlertService alertService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private LocalFallbackService localFallbackService;
    @Mock private SeckillDeductLua seckillDeductLua;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private LocalStockShelvingService localStockShelvingService;

    @InjectMocks private RedisDegradeService service;

    /** 让 PING 回 PONG；不 stub 时 mock 默认返回 null，等价于探活失败。 */
    @SuppressWarnings("unchecked")
    private void redisIsUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(MANUAL_DEGRADE_KEY)).thenReturn(null);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
    }

    private void degradeFirst() {
        for (int i = 0; i < DEGRADE_THRESHOLD; i++) {
            service.recordFailure();
        }
        assertTrue(service.isDegraded(), "前置条件没成立：应该已经自动降级了");
    }

    @Test
    @DisplayName("自动恢复：连续探活成功后必须自己退出降级，并回写降级窗口销量")
    void autoRecover_afterEnoughSuccessfulProbes_exitsDegradationAndSyncs() {
        degradeFirst();
        redisIsUp();
        when(localFallbackService.syncAllToRedis(seckillDeductLua)).thenReturn(Map.of(7L, 3));

        for (int i = 0; i < RECOVERY_THRESHOLD; i++) {
            service.healthCheck();
        }

        assertFalse(service.isDegraded(),
                "连续 " + RECOVERY_THRESHOLD + " 次探活成功后必须自动退出降级，否则一次抖动就永久降级");
        verify(localFallbackService).syncAllToRedis(seckillDeductLua);
        verify(alertService).sendAlert(eq("Redis恢复库存同步"), contains("共3件"));
    }

    @Test
    @DisplayName("自动恢复：还没攒够成功次数时不许提前退出降级")
    void autoRecover_beforeThreshold_staysDegraded() {
        degradeFirst();
        redisIsUp();

        for (int i = 0; i < RECOVERY_THRESHOLD - 1; i++) {
            service.healthCheck();
        }

        assertTrue(service.isDegraded(), "差一次成功就恢复，会让抖动的 Redis 反复进出降级");
        verify(localFallbackService, never()).syncAllToRedis(any());
    }

    @Test
    @DisplayName("Redis 还没活过来：一直探活失败就保持降级，且不回写")
    void probeStillFailing_staysDegradedAndDoesNotSync() {
        degradeFirst();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        for (int i = 0; i < RECOVERY_THRESHOLD * 2; i++) {
            service.healthCheck();
        }

        assertTrue(service.isDegraded());
        verify(localFallbackService, never()).syncAllToRedis(any());
    }

    @Test
    @DisplayName("手动恢复：也要回写降级窗口销量，不能只翻开关")
    void manualRecover_syncsLocalSalesBack() {
        degradeFirst();
        when(localFallbackService.syncAllToRedis(seckillDeductLua)).thenReturn(Map.of(7L, 3, 9L, 0));

        service.manualRecover();

        assertFalse(service.isDegraded());
        verify(localFallbackService).syncAllToRedis(seckillDeductLua);
        verify(alertService).sendAlert(eq("Redis恢复库存同步"), contains("共3件"));
    }

    @Test
    @DisplayName("手动恢复：回写抛异常不能把恢复本身一起带崩")
    void manualRecover_syncThrows_stillExitsDegradation() {
        degradeFirst();
        when(localFallbackService.syncAllToRedis(seckillDeductLua))
                .thenThrow(new RuntimeException("redis still warming up"));

        assertDoesNotThrow(() -> service.manualRecover());

        assertFalse(service.isDegraded(), "回写失败时 syncToRedis 会把本地状态还回去等下次重试，恢复动作不该被卡住");
        verify(alertService).sendAlert(eq("Redis恢复库存同步失败"), contains("redis still warming up"));
    }

    @Test
    @DisplayName("降级中 isRedisAvailable() 仍短路，业务侧不会去锤已经倒了的 Redis")
    void isRedisAvailable_stillShortCircuitsWhileDegraded() {
        degradeFirst();
        redisIsUp();

        assertFalse(service.isRedisAvailable(),
                "降级期间业务流量必须走本地兜底；要判断 Redis 是否复活是 probeRedis 的职责");
    }

    // ==================== 降级入口：本地库存必须上架 ====================
    //
    // 没接这根线之前，降级 = 拒绝一切秒杀：deductStockLocal 找不到本地库存返回 -1，
    // SeckillService 把 -1 翻译成"库存不足"。开关翻了、告警发了、业务一件也卖不出去。

    @Test
    @DisplayName("自动降级：翻开关的同时必须上架本地兜底库存")
    void autoDegrade_shelvesLocalStock() {
        degradeFirst();

        verify(localStockShelvingService).shelveForActiveActivities("Redis自动降级");
    }

    @Test
    @DisplayName("手动降级：同样必须上架，否则运维一按降级就等于全站停售")
    void manualDegrade_shelvesLocalStock() {
        service.manualDegrade();

        assertTrue(service.isDegraded());
        verify(localStockShelvingService).shelveForActiveActivities("Redis手动降级");
    }

    @Test
    @DisplayName("上架失败不能把降级动作本身带崩")
    void shelveThrows_degradationStillTakesEffect() {
        when(localStockShelvingService.shelveForActiveActivities(anyString()))
                .thenThrow(new RuntimeException("db is down too"));

        assertDoesNotThrow(() -> service.manualDegrade());

        assertTrue(service.isDegraded(),
                "降级开关必须已经生效：宁可少卖（本地无库存→请求被拒），也不能停在半降级状态继续锤死掉的 Redis");
    }
}
