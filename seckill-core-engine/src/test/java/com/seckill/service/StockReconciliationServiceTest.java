package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 库存一致性核对：只许看，不许改
 * ============================================================================
 *
 * <p>不变量：<b>对账逻辑永远不得写 Redis 库存</b>。
 *
 * <p>核对公式用的是 {@code initialStock - boughtSet.size()}，而 bought set 按 userId 去重，
 * 一个人只占一个位置却可能买走多件（{@code SeckillRequest.quantity} 只有 {@code @Min(1)}
 * 没有 {@code @Max}；降级时本地兜底还允许同一用户买满 maxBuyCount 次）。所以已售件数
 * &gt;= boughtCount，那个公式给出的只是剩余库存的<b>上界</b>。以前的 attemptAutoFix 会把
 * 库存绝对 SET 成这个上界 —— 两个方向都不安全，往上调是凭空放货（超卖），往下调也可能
 * 高于真实剩余。
 *
 * <p>最容易踩的是它和降级回写的相互作用：本地一人买 2 件，回写后 Redis 少 2 件、bought set
 * 只多 1 个，这里就会算出 -1 的差异，再把那 1 件放回去卖第二遍，把步骤1 的成果直接抹掉。
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class StockReconciliationServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private SeckillDeductLua seckillDeductLua;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;
    @Mock private AlertService alertService;
    @Mock private RedisDegradeService redisDegradeService;

    private StockReconciliationService service;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new StockReconciliationService(activityMapper, seckillDeductLua, redisTemplate,
                meterRegistry, alertService, redisDegradeService);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    /** 摆好一个活动：初始库存 totalStock、Redis 剩余 remaining、防重 Set 里 buyerCount 个人。 */
    private void givenActivity(long seckillId, int totalStock, int remaining, long buyerCount) {
        when(seckillDeductLua.boughtKey(seckillId)).thenReturn("seckill:bought:" + seckillId);
        SeckillActivity activity = new SeckillActivity();
        activity.setId(seckillId);
        activity.setTotalStock(totalStock);
        when(activityMapper.selectActiveActivities()).thenReturn(List.of(activity));
        when(seckillDeductLua.getTotalStock(seckillId)).thenReturn(remaining);
        lenient().when(setOps.size("seckill:bought:" + seckillId)).thenReturn(buyerCount);
    }

    @Test
    @DisplayName("一人买多件造成的差异：只告警，绝不把库存 SET 回上界")
    void multiUnitBuyer_discrepancy_alertsButNeverRewritesStock() {
        // 一个用户一次买了 3 件：Redis 少 3 件，防重 Set 只多 1 个人
        // 上界 = 100 - 1 = 99，真值 = 97。按上界修就是凭空放 2 件出去卖
        givenActivity(1L, 100, 97, 1L);

        service.reconcileStock();

        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(seckillDeductLua, never()).warmupStock(anyLong(), anyInt());
        verify(alertService).sendCriticalAlert(eq("库存不一致"), contains("差异=-2"));
    }

    @Test
    @DisplayName("降级窗口回写之后：对账不许把刚回写的销量再放回去")
    void afterDegradedWindowSync_doesNotUndoRelativeWriteBack() {
        // 步骤1 的验收场景：3 个实例各自回写，合计 7 件，其中一个用户本地买了 2 件，
        // 所以 bought set 只多了 6 个人。Redis 真实剩余 493，上界却是 500 - 6 = 494
        givenActivity(2L, 500, 493, 6L);

        service.reconcileStock();

        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(seckillDeductLua, never()).warmupStock(anyLong(), anyInt());
        verify(alertService).sendCriticalAlert(eq("库存不一致"), contains("差异=-1"));
    }

    @Test
    @DisplayName("Redis 库存高于上界：往下修同样不安全，也只告警")
    void stockAboveUpperBound_stillNeverRewrites() {
        // 上界本身可能偏高（买多件时），所以往下 SET 到上界也可能高于真实剩余
        givenActivity(3L, 100, 100, 5L);

        service.reconcileStock();

        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(alertService).sendCriticalAlert(eq("库存不一致"), contains("差异=5"));
    }

    @Test
    @DisplayName("库存一致时既不告警也不动库存")
    void consistent_noAlertNoRewrite() {
        givenActivity(4L, 100, 97, 3L);

        service.reconcileStock();

        verifyNoInteractions(alertService);
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
    }

    @Test
    @DisplayName("降级中直接跳过：窗口销量还没回写，这时候量出来的差异是假的")
    void degraded_skipsReconciliationEntirely() {
        when(redisDegradeService.isDegraded()).thenReturn(true);

        service.reconcileStock();

        verifyNoInteractions(activityMapper, seckillDeductLua, alertService);
    }

    @Test
    @DisplayName("核对单个活动时也一样：不改库存")
    void manualReconcile_neverRewritesStock() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(5L);
        activity.setTotalStock(100);
        when(activityMapper.selectById(5L)).thenReturn(activity);
        when(seckillDeductLua.getTotalStock(5L)).thenReturn(97);
        when(setOps.size("seckill:bought:5")).thenReturn(1L);

        when(seckillDeductLua.boughtKey(5L)).thenReturn("seckill:bought:5");
        service.manualReconcile(5L);

        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(alertService).sendCriticalAlert(eq("库存不一致"), contains("seckillId=5"));
    }
}
