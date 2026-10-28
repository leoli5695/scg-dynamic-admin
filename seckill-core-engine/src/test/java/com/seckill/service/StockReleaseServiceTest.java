package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.entity.SeckillOrder;
import com.seckill.mapper.OrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StockReleaseServiceTest {
    private SeckillOrder order(){var o=new SeckillOrder();o.setOrderNo("a");o.setStockReleaseId("a");o.setStockReleaseState(1);o.setStatus(2);
        o.setUserId(42L);o.setSeckillId(1L);o.setQuantity(1);o.setShardIndex(3);return o;}
    @Test void databaseConfirmationFailureKeepsSameLedgerIdentityForRetry() {
        var orders=mock(OrderMapper.class);var redis=mock(StringRedisTemplate.class);var config=new SeckillConfig();config.setStockHashTagEnabled(true);
        var service=new StockReleaseService(orders,redis,config,new SimpleMeterRegistry());
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).thenReturn(1003L);
        when(orders.confirmRelease(42L,"a","a")).thenThrow(new IllegalStateException("DB failed")).thenReturn(1);
        assertThrows(IllegalStateException.class,()->service.release(order()));assertTrue(service.release(order()));
        verify(redis,times(2)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of("seckill:stock:{1}:shard:3","seckill:bought:{1}","seckill:user_shard:{1}","seckill:released:{1}")),
                eq("a"),eq("42"),eq("1"),eq("3"));
        service.shutdown();
    }
    @Test void missingRedisStockIsNotRecreatedOrConfirmed() {
        var orders=mock(OrderMapper.class);var redis=mock(StringRedisTemplate.class);
        var service=new StockReleaseService(orders,redis,new SeckillConfig(),new SimpleMeterRegistry());
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).thenReturn(-2L);
        assertFalse(service.release(order()));verifyNoInteractions(orders);service.shutdown();
    }
    @Test void oldTerminalRowsWithoutIntentCannotBeBlindlyReleased() {
        var orders=mock(OrderMapper.class);var redis=mock(StringRedisTemplate.class);
        var service=new StockReleaseService(orders,redis,new SeckillConfig(),new SimpleMeterRegistry());
        var old=order();old.setStockReleaseId(null);assertFalse(service.release(old));verifyNoInteractions(redis,orders);service.shutdown();
    }
}
