package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 数据对账：只许看，不许改
 * ============================================================================
 *
 * <p>不变量：<b>对账逻辑永远不得写 Redis 库存</b>，和 {@link StockReconciliationServiceTest}
 * 是同一条规矩的另一个口径。
 *
 * <p>这里的理论库存是 {@code initialStock - 成功事务条数 - 处理中事务条数}，按"条"算，
 * 而一条 TransactionLog 可以对应多件（它有 quantity 字段）。所以已售件数 &gt;= 事务条数，
 * 理论库存只是剩余库存的上界。以前的 autoFixStock 会在 |diff| &lt;= 10 时把库存绝对 SET 成
 * 这个上界，等于凭空放货。
 *
 * <p>而且 diff &lt; 0 在正常运行时本来就会出现：Redis 是同步扣的，事务日志要等 RocketMQ 回调
 * executeLocalTransaction 才落库。这中间 diff 就是负的，恰好落在原来的"自动修正"区间里，
 * 每 5 分钟把刚扣掉的库存又加回去 —— 这是条不需要任何故障就会触发的超卖路径。
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    private static final long SECKILL_ID = 1L;
    private static final int INITIAL_STOCK = 100;
    private static final int ALERT_THRESHOLD = 50;

    @Mock private AlertService alertService;
    @Mock private ActivityMapper activityMapper;
    @Mock private SeckillDeductLua seckillDeductLua;
    @Mock private TransactionLogMapper transactionLogMapper;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(alertService, activityMapper, seckillDeductLua,
                transactionLogMapper);
        ReflectionTestUtils.setField(service, "alertThreshold", ALERT_THRESHOLD);
    }

    /** 摆好一次对账的输入：Redis 剩余 remaining，事务日志里 success 条成功、processing 条处理中。 */
    private SeckillActivity given(int remaining, int success, int processing) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(SECKILL_ID);
        activity.setTotalStock(INITIAL_STOCK);

        when(seckillDeductLua.getTotalStock(SECKILL_ID)).thenReturn(remaining);
        when(transactionLogMapper.countSuccessTransactions(SECKILL_ID)).thenReturn(success);
        when(transactionLogMapper.countProcessingTransactions(SECKILL_ID)).thenReturn(processing);
        lenient().when(transactionLogMapper.countFailedTransactions(SECKILL_ID)).thenReturn(0);
        return activity;
    }

    @Test
    @DisplayName("事务日志还在 MQ 回调路上（diff 为负）：绝不能把库存加回去")
    void inFlightTransaction_negativeDiff_neverInflatesStock() {
        // Redis 已经扣了 3 件，事务日志一条都还没落库 → 理论 100，diff = -3
        // 以前的 |diff| <= 10 自动修正会把库存 SET 回 100，凭空放 3 件出去卖
        ReconciliationService.ReconciliationResult result =
                service.reconcileActivity(given(97, 0, 0));

        assertEquals(-3, result.getDiff());
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(seckillDeductLua, never()).warmupStock(anyLong(), anyInt());
        verify(alertService).sendAlert(eq("库存差异待人工核对"), contains("diff=-3"));
    }

    @Test
    @DisplayName("一条日志对应多件（diff 为负）：同样只告警")
    void multiUnitTransaction_neverInflatesStock() {
        // 一个用户一次买 3 件：Redis 少 3 件，日志只多 1 条 → 理论 99，diff = -2
        ReconciliationService.ReconciliationResult result =
                service.reconcileActivity(given(97, 1, 0));

        assertEquals(-2, result.getDiff());
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(alertService).sendAlert(eq("库存差异待人工核对"), contains("diff=-2"));
    }

    @Test
    @DisplayName("大偏差升级为严重告警，但仍然不改库存")
    void largeDiff_escalatesToCriticalAlert_andStillNeverRewrites() {
        ReconciliationService.ReconciliationResult result =
                service.reconcileActivity(given(10, 20, 0));

        assertEquals(-70, result.getDiff());
        assertTrue(result.isNeedAlert());
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        verify(alertService).sendCriticalAlert(eq("库存差异告警"), contains("diff=-70"));
    }

    @Test
    @DisplayName("库存一致时不告警")
    void noDiff_noAlert() {
        ReconciliationService.ReconciliationResult result =
                service.reconcileActivity(given(98, 2, 0));

        assertEquals(0, result.getDiff());
        assertFalse(result.isNeedAlert());
        verifyNoInteractions(alertService);
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
    }
}
