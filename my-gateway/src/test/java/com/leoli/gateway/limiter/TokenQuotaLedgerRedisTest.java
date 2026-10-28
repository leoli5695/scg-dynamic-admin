package com.leoli.gateway.limiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in contract tests against a dedicated Redis, using only uniquely named disposable keys. */
@EnabledIfEnvironmentVariable(named="GATEWAY_TEST_REDIS_HOST",matches=".+")
class TokenQuotaLedgerRedisTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    List<String> keys;
    DefaultRedisScript<List> script;
    String deadline,expiry,context;
    @BeforeEach void connect() {
        var config = new RedisStandaloneConfiguration(System.getenv("GATEWAY_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("GATEWAY_TEST_REDIS_PORT","6379")));
        String password = System.getenv("GATEWAY_TEST_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        factory = new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet(); redis = new StringRedisTemplate(factory);
        String prefix="quota-ledger-test:" + UUID.randomUUID();
        keys=List.of(prefix+":receipt",prefix+":monthly:used",prefix+":monthly:total",prefix+":daily:used",prefix+":daily:total");
        script=new DefaultRedisScript<>(); script.setLocation(new ClassPathResource("lua/token-quota-ledger.lua")); script.setResultType(List.class);
        deadline=Long.toString(Instant.now().getEpochSecond()+3600); expiry=Long.toString(Instant.now().getEpochSecond()+7200);
        context=UUID.randomUUID().toString();
    }
    @AfterEach void cleanup() { try { if (redis != null && keys != null) redis.delete(keys); } finally { if (factory != null) factory.destroy(); } }
    List<?> execute(String mode,long target) {
        return redis.execute(script,keys,mode,Long.toString(target),"1000","1000","1","1",expiry,expiry,deadline,context);
    }
    @Test void scriptReservesAndSettlesBothCountersExactlyOnce() {
        assertEquals(1L,execute("RESERVE",100).getFirst());
        assertEquals(1L,execute("RESERVE",100).getFirst());
        assertEquals(1L,execute("FINAL",30).getFirst());
        assertEquals(1L,execute("FINAL",30).getFirst());
        assertEquals("30",redis.opsForValue().get(keys.get(1))); assertEquals("30",redis.opsForValue().get(keys.get(3)));
        assertTrue(redis.getExpire(keys.getFirst())>0);
    }
    @Test void invalidSecondBucketDoesNotAdjustFirstAndFenceBlocksLateRequests() {
        execute("RESERVE",100);
        redis.opsForValue().set(keys.get(3),"-1");
        assertEquals(-5L,execute("FINAL",30).getFirst());
        assertEquals("100",redis.opsForValue().get(keys.get(1)));
        assertEquals(-6L,execute("FENCE",30).getFirst());
        redis.opsForValue().set(keys.get(3),"100");
        assertEquals(-6L,execute("FINAL",30).getFirst());
        assertEquals("100",redis.opsForValue().get(keys.get(1))); assertEquals("100",redis.opsForValue().get(keys.get(3)));
    }
}
