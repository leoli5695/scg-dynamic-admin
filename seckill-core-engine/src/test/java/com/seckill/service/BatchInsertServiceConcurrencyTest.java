package com.seckill.service;

import com.seckill.entity.SeckillOrder;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 批量插入服务并发压测
 * ============================================================================
 * <p>
 * 覆盖 BatchInsertService 的攒批落库路径：addToBatch 入队与阈值触发、flushBatch 的
 * 按 userId 分组批量写、队列溢出时的强制刷新重试。
 * <p>
 * 这三个用例原先一次都没有调用过 BatchInsertService：线程体里只有
 * counter.incrementAndGet()，断言是 assertEquals(threadCount, success + fail) 这类恒真式，
 * 还有一个 assertNotNull(batchInsertService)。orderMapper.insertBatch 的 stub 因此从未被
 * 用到，Mockito 以 UnnecessaryStubbing 报红了第一个 —— 那是本类唯一真实执行过的检查。
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class BatchInsertServiceConcurrencyTest {

    private OrderMapper orderMapper;
    private AlertService alertService;
    private BatchInsertService batchInsertService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        alertService = mock(AlertService.class);
        batchInsertService = newService(orderMapper, alertService);
    }

    @AfterEach
    void tearDown() {
        batchInsertService.shutdown();
    }

    @Test
    @DisplayName("并发压测：1000并发插入不丢失数据")
    void concurrentBatchInsert_noDataLoss() throws InterruptedException {
        AtomicInteger inserted = new AtomicInteger();
        when(orderMapper.insertBatch(anyList())).thenAnswer(invocation -> {
            List<SeckillOrder> orders = invocation.getArgument(0);
            inserted.addAndGet(orders.size());
            return orders.size();
        });

        int threadCount = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // userId 取模分散，覆盖 flushBatch 里 groupedByUser 的多分组路径
                    if (batchInsertService.addToBatch(order(idx, idx % 17))) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试应在60秒内完成");
        pool.shutdownNow();

        drainQueue(batchInsertService);

        assertEquals(threadCount, accepted.get() + rejected.get(), "每个请求必须有明确结果");
        assertEquals(accepted.get(), inserted.get(),
                "被接收的订单必须全部落库，一条都不能烂在队列里");
        assertEquals(0, batchInsertService.getQueueSize(), "落库后队列必须清空");
    }

    @Test
    @DisplayName("攒批：阈值以内的请求合并成一次批量插入")
    void batchInsert_requestsMergedInWindow() {
        AtomicInteger batchCalls = new AtomicInteger();
        when(orderMapper.insertBatch(anyList())).thenAnswer(invocation -> {
            List<SeckillOrder> orders = invocation.getArgument(0);
            batchCalls.incrementAndGet();
            assertEquals(50, orders.size(), "达到阈值时应把攒下的订单一次写出");
            return orders.size();
        });

        int threshold = 50;
        // 同一个 userId，flushBatch 的 groupedByUser 才会合成一组、只调一次 insertBatch
        for (int i = 0; i < threshold - 1; i++) {
            batchInsertService.addToBatch(order(i, 7L));
        }
        verify(orderMapper, never()).insertBatch(anyList());
        assertEquals(threshold - 1, batchInsertService.getQueueSize(), "阈值以内只攒不写");

        batchInsertService.addToBatch(order(threshold - 1, 7L));

        assertEquals(1, batchCalls.get(), "跨阈值应触发且只触发一次批量写");
        assertEquals(0, batchInsertService.getQueueSize());
    }

    @Test
    @DisplayName("队列溢出：强制刷新后重试，不阻塞也不丢单")
    void batchInsert_queueOverflow_forcesFlushWithoutLoss() {
        // 默认 queueCapacity=200 / threshold=50，队列根本填不满，addToBatch 的溢出分支
        // （offer 失败 -> 告警 -> 强制 flush -> 重试）永远走不到。把容量压到阈值以下逼它出来。
        BatchInsertService tight = newService(orderMapper, alertService);
        ReflectionTestUtils.setField(tight, "queueCapacity", 3);
        tight.init();

        AtomicInteger inserted = new AtomicInteger();
        when(orderMapper.insertBatch(anyList())).thenAnswer(invocation -> {
            List<SeckillOrder> orders = invocation.getArgument(0);
            inserted.addAndGet(orders.size());
            return orders.size();
        });

        try {
            int total = 20;
            for (int i = 0; i < total; i++) {
                assertTrue(tight.addToBatch(order(i, i)),
                        "第 " + i + " 单应被接收：队列满时会强制刷新后重试");
            }
            drainQueue(tight);

            assertEquals(total, inserted.get(), "溢出一单都不能丢");
            assertEquals(0, tight.getQueueSize());
            verify(alertService, atLeastOnce()).sendAlert(eq("攒批队列满"), anyString());
        } finally {
            tight.shutdown();
        }
    }

    private static BatchInsertService newService(OrderMapper orderMapper, AlertService alertService) {
        BatchInsertService service = new BatchInsertService(
                orderMapper,
                alertService,
                mock(TransactionLogMapper.class),
                new SimpleMeterRegistry());
        // init() 是 @PostConstruct，Mockito 不会调用；不手动调 orderQueue 就是 null，
        // addToBatch 第一行 offer 直接 NPE。
        service.init();
        return service;
    }

    /** flushBatch 单次最多取 threshold*2 条，循环到队列真正空为止。 */
    private static void drainQueue(BatchInsertService service) {
        do {
            service.flushBatch();
        } while (service.getQueueSize() > 0);
    }

    private static SeckillOrder order(int idx, long userId) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo("order-" + idx);
        order.setTransactionId("tx-" + idx);
        order.setUserId(userId);
        order.setSeckillId(1L);
        order.setProductId(1L);
        order.setQuantity(1);
        return order;
    }
}
