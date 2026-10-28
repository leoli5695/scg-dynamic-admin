package com.seckill.redis.lua;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Redis verifies the script rather than mocking an expected return code. */
@Testcontainers(disabledWithoutDocker=true)
class OrderStockReleaseLuaIntegrationTest {
    @Container static final GenericContainer<?> SERVER=new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static DefaultRedisScript<Long> deduct,release;
    static final String STOCK="seckill:stock:{42}:shard:2",BOUGHT="seckill:bought:{42}",USERS="seckill:user_shard:{42}",LEDGER="seckill:released:{42}";
    @BeforeAll static void connect(){
        factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration(SERVER.getHost(),SERVER.getMappedPort(6379)));factory.afterPropertiesSet();
        redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        deduct=new DefaultRedisScript<>();deduct.setLocation(new ClassPathResource("lua/seckill_deduct.lua"));deduct.setResultType(Long.class);
        release=new DefaultRedisScript<>();release.setLocation(new ClassPathResource("lua/order_stock_release.lua"));release.setResultType(Long.class);
    }
    @AfterAll static void disconnect(){if(factory!=null)factory.destroy();}
    @BeforeEach void reserve(){
        var keys=new ArrayList<>(StockKeys.script(42L,8,true,false));keys.add(LEDGER);redis.delete(keys);
        for(int shard=0;shard<8;shard++)redis.opsForValue().set(StockKeys.prefix(42L,true)+shard,"2");
        assertEquals(1002L,redis.execute(deduct,StockKeys.script(42L,8,true,false),"42","1","8","2"));
    }
    private Long restore(){return redis.execute(release,List.of(STOCK,BOUGHT,USERS,LEDGER),"order-a","42","1","2");}
    @Test void duplicateDeliveryAfterDatabaseConfirmationLossDoesNotIncrementTwice(){
        assertEquals("1",redis.opsForValue().get(STOCK));assertEquals(1002L,restore());assertEquals(1002L,restore());
        assertEquals("2",redis.opsForValue().get(STOCK));assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(BOUGHT,"42")));
        assertEquals("1",redis.opsForHash().get(LEDGER,"order-a"));
        assertEquals(-1L,redis.execute(deduct,StockKeys.script(42L,8,true,false),"42","1","8","2"));
        assertEquals("2",redis.opsForValue().get(STOCK));
    }
    @Test void missingOriginalStockIsNeverRecreated(){
        redis.delete(STOCK);assertEquals(-2L,restore());assertFalse(Boolean.TRUE.equals(redis.hasKey(STOCK)));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(BOUGHT,"42")));
    }
    @Test void incorrectLedgerTypeCannotCauseAPartialStockMutation(){
        redis.opsForValue().set(LEDGER,"wrong-type");assertEquals(-99L,restore());assertEquals("1",redis.opsForValue().get(STOCK));
    }
    @Test void concurrentWorkersStillPerformOneRelease() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var results=new ArrayList<Future<Long>>();for(int i=0;i<64;i++)results.add(pool.submit(this::restore));
            for(var result:results)assertEquals(1002L,result.get(5,TimeUnit.SECONDS));
        }
        assertEquals("2",redis.opsForValue().get(STOCK));
    }
}
