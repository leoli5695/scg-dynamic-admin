package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.*;
import com.seckill.dto.OrderMessage;
import com.seckill.entity.*;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.*;
import com.seckill.mq.consumer.OrderCreateConsumer;
import com.seckill.mq.listener.SeckillTransactionListener;
import com.seckill.redis.lua.StockKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderDeliveryRegressionTest {
    private SeckillOrder order(String no, long user) {
        var order = new SeckillOrder(); order.setOrderNo(no); order.setUserId(user); order.setTransactionId("tx-"+no); return order;
    }
    @Test void differentUsersInOnePhysicalShardAreActuallyBatched() {
        var orders=mock(OrderMapper.class); var tx=mock(TransactionLogMapper.class);
        var service=new BatchInsertService(orders,mock(AlertService.class),tx,new SimpleMeterRegistry()); service.init();
        service.addToBatch(order("a",1)); service.addToBatch(order("b",17)); service.flushBatch();
        verify(orders).insertBatch(argThat(list -> list.size()==2));
        verify(tx).markOrdersCreated(argThat(list -> list.size()==2)); service.shutdown();
    }
    @Test void inFlightWriteStillUsesCapacityAndCannotBeAcknowledgedEarly() throws Exception {
        var orders=mock(OrderMapper.class); var config=new SeckillConfig();
        config.getBatch().setQueueCapacity(1); config.getBatch().setThreshold(1);
        var service=new BatchInsertService(orders,mock(AlertService.class),mock(TransactionLogMapper.class),new SimpleMeterRegistry());
        service.setSeckillConfig(config); service.init();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(orders.insertBatch(anyList())).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)); return 1; });
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(() -> service.persistAndAwait(order("a",1)));
            assertTrue(entered.await(2,TimeUnit.SECONDS)); assertFalse(result.isDone());
            assertThrows(IllegalStateException.class,()->service.persistAndAwait(order("b",17)));
            release.countDown(); result.get(3,TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); service.shutdown(); }
    }
    @Test void databaseFailureIsReturnedToConsumerWithoutPerRowRetry() {
        var orders=mock(OrderMapper.class); var config=new SeckillConfig();config.getBatch().setThreshold(1);
        var service=new BatchInsertService(orders,mock(AlertService.class),mock(TransactionLogMapper.class),new SimpleMeterRegistry());
        service.setSeckillConfig(config);service.init();
        when(orders.insertBatch(anyList())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class,()->service.persistAndAwait(order("a",1)));
        verify(orders,never()).insert(any(SeckillOrder.class));service.shutdown();
    }
    @Test void all128PhysicalLocationsAreReachableOnlyInV2() {
        Set<String> legacy=new HashSet<>(), balanced=new HashSet<>();
        for(long user=0;user<128;user++){legacy.add(OrderShardRouting.bucket(user,1));balanced.add(OrderShardRouting.bucket(user,2));}
        assertEquals(16,legacy.size());assertEquals(128,balanced.size());
        assertThrows(IllegalArgumentException.class,()->OrderShardRouting.table(1,3));
    }
    @Test void everyStockKeyIsExplicitAndSharesTheActivityHashTag() {
        var keys=StockKeys.script(42L,8,true,false);assertEquals(12,keys.size());
        assertTrue(keys.stream().allMatch(key -> key.contains("{42}")));
        for(int shard=0;shard<8;shard++)assertEquals("seckill:stock:{42}:shard:"+shard,keys.get(3+shard));
        assertEquals(keys.get(0),StockKeys.script(42L,8,true,true).get(1));
    }
    @Test void clusterRefusesLegacyKeyFormat() {
        var env=new MockEnvironment().withProperty("spring.data.redis.cluster.nodes[0]","localhost:7000");
        var config=new SeckillConfig();var guard=new StockKeyFormatGuard(config,env);
        assertThrows(IllegalStateException.class,guard::validate);config.setStockHashTagEnabled(true);guard.validate();
    }
    @Test void committedProducerLogMakesMessageVisibleEvenBeforeOrderCreation() {
        var service=mock(SeckillService.class);when(service.checkLocalTransaction("tx")).thenReturn(TransactionStatus.PROCESSING);
        var listener=new SeckillTransactionListener(service,new ObjectMapper());var message=new MessageExt();message.setKeys("tx");
        assertEquals(LocalTransactionState.COMMIT_MESSAGE,listener.checkLocalTransaction(message));
        when(service.checkLocalTransaction("tx")).thenThrow(new IllegalStateException("DB unavailable"));
        assertEquals(LocalTransactionState.UNKNOW,listener.checkLocalTransaction(message));
    }
    @Test void redeliveryOfExistingOrderRepairsEventsInsteadOfSkippingThem() throws Exception {
        var orders=mock(OrderMapper.class);var batch=mock(BatchInsertService.class);var events=mock(OrderDeliveryService.class);
        var rate=mock(DistributedRateLimiterService.class);when(rate.tryAcquire(anyString(),anyInt())).thenReturn(true);
        var tx=mock(TransactionLogMapper.class);var mapper=new ObjectMapper();
        var message=new OrderMessage();message.setOrderNo("a");message.setUserId(17L);message.setTransactionId("tx-a");
        when(orders.selectByUserAndOrderNo(17L,"a")).thenReturn(order("a",17));
        var consumer=new OrderCreateConsumer(orders,batch,new SeckillConfig(),mapper,rate,tx,events);
        doThrow(new IllegalStateException("outbox unavailable")).when(events).enqueue(any());
        assertThrows(IllegalStateException.class,()->consumer.onMessage(mapper.writeValueAsString(message)));
        verify(batch,never()).persistAndAwait(any()); verify(orders,never()).selectByOrderNo(anyString());
        doNothing().when(events).enqueue(any());consumer.onMessage(mapper.writeValueAsString(message));
        verify(tx,times(2)).markOrdersCreated(List.of("tx-a"));
    }
    @Test void enqueueEventsDoesNotCallBrokerAndDispatchRetriesOnlyMissingPart() {
        var db=mock(OrderDeliveryMapper.class);var mq=mock(RocketMQTemplate.class);
        var service=new OrderDeliveryService(db,new ObjectMapper(),mq,new SeckillConfig(),new SimpleMeterRegistry());
        var message=new OrderMessage();message.setOrderNo("a");service.enqueue(message);verifyNoInteractions(mq);
        var event=new OrderDelivery();event.setOrderNo("a");event.setPayload("{}");event.setEsSent(true);event.setDelayLevel(17);
        when(db.pending()).thenReturn(List.of(event));when(mq.syncSend(anyString(),any(org.springframework.messaging.Message.class),anyLong(),anyInt())).thenReturn(null);
        service.dispatch();verify(db,never()).markRollback(anyString());verify(db).reschedule(eq("a"),anyInt());
        var sent=new SendResult();sent.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(anyString(),any(org.springframework.messaging.Message.class),anyLong(),anyInt())).thenReturn(sent);
        service.dispatch();verify(db).markRollback("a");verify(db,never()).markEs(anyString());
    }

    @Test void timeoutDoesNotFreeTheBudgetOfAPendingWrite() {
        var orders=mock(OrderMapper.class);var config=new SeckillConfig();
        config.getBatch().setQueueCapacity(1);config.getBatch().setAcknowledgementTimeoutMs(1);
        var service=new BatchInsertService(orders,mock(AlertService.class),mock(TransactionLogMapper.class),new SimpleMeterRegistry());
        service.setSeckillConfig(config);service.init();
        assertThrows(IllegalStateException.class,()->service.persistAndAwait(order("a",1)));
        assertEquals(1,service.getQueueSize());
        assertThrows(IllegalStateException.class,()->service.persistAndAwait(order("b",17)));
        service.shutdown();verify(orders).insertBatch(argThat(list -> list.size()==1));
    }

    @Test void configuredGroovyRoutingExpressionMatchesBatchGrouping() {
        var dataSource=new ShardingDataSourceConfig("localhost",3306,"test","test",false,false);
        org.springframework.test.util.ReflectionTestUtils.setField(dataSource,"orderRoutingVersion",2);
        org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration rules =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(dataSource,"createShardingRuleConfiguration");
        String expression=rules.getShardingAlgorithms().get("order_table_inline").getProps().getProperty("algorithm-expression");
        var binding=new groovy.lang.Binding();var shell=new groovy.lang.GroovyShell(binding);
        for(long user=0;user<128;user++) {
            binding.setVariable("user_id",user);
            assertEquals("order_"+OrderShardRouting.table(user,2),shell.evaluate('"'+expression+'"').toString());
        }
    }
}
