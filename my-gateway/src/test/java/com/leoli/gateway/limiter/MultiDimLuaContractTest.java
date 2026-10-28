package com.leoli.gateway.limiter;

import com.leoli.gateway.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the real generated Lua with a deterministic implementation of the Redis commands it uses. */
class MultiDimLuaContractTest {
    private final Map<String, List<Double>> scores = new HashMap<>();
    private final Set<String> members = new HashSet<>();
    private long now = 10000;
    private static LuaTable table(LuaValue... values) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < values.length; i++) table.set(i + 1, values[i]);
        return table;
    }
    private LuaValue evaluate(boolean firstHit, int firstLimit) {
        var limiter = new DistributedRateLimiter();
        ReflectionTestUtils.setField(limiter, "rateLimitScript", new RedisConfig().rateLimitScript());
        DefaultRedisScript<?> script = ReflectionTestUtils.invokeMethod(limiter, "batchScript");
        Globals lua = JsePlatform.standardGlobals();
        LuaTable redis = new LuaTable();
        redis.set("call", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String command = args.arg(1).tojstring(), key = args.arg(2).tojstring();
                switch (command) {
                    case "TIME": return table(new LuaValue[]{LuaValue.valueOf(now / 1000), LuaValue.valueOf((now % 1000) * 1000)});
                    case "ZREMRANGEBYSCORE":
                        scores.computeIfAbsent(key, k -> new ArrayList<>()).removeIf(score -> score <= args.arg(4).todouble());
                        return LuaValue.ZERO;
                    case "ZCARD": return LuaValue.valueOf(scores.computeIfAbsent(key, k -> new ArrayList<>()).size());
                    case "ZADD":
                        assertTrue(members.add(key + ":" + args.arg(4).tojstring()), "member collision");
                        scores.computeIfAbsent(key, k -> new ArrayList<>()).add(args.arg(3).todouble()); return LuaValue.ONE;
                    case "EXPIRE": return LuaValue.ONE;
                    default: throw new AssertionError(command);
                }
            }
        });
        lua.set("redis", redis);
        lua.set("KEYS", table(new LuaValue[]{LuaValue.valueOf("global"), LuaValue.valueOf("user")}));
        lua.set("ARGV", table(new LuaValue[]{LuaValue.valueOf(firstHit ? "1" : "0"), LuaValue.valueOf("1000"), LuaValue.valueOf(firstLimit), LuaValue.ZERO,
                LuaValue.valueOf("1000"), LuaValue.valueOf("10"), LuaValue.ZERO}));
        return lua.load(script.getScriptAsString()).call();
    }
    @Test void firstHitDoesNotChargeLaterDimensionsWhenEarlierDimensionDenies() {
        var result = evaluate(true, 0);
        assertEquals(1, result.length()); assertEquals(0, result.get(1).toint()); assertFalse(scores.containsKey("user"));
    }
    @Test void allHitStillChargesAllowedLaterDimensionAfterEarlierDenial() {
        var result = evaluate(false, 0);
        assertEquals(2, result.length()); assertEquals(0, result.get(1).toint()); assertEquals(10, result.get(2).toint());
        assertEquals(1, scores.get("user").size());
    }
    @Test void windowExpiryMatchesSingleDimensionSemantics() {
        assertEquals(1, evaluate(true, 1).get(1).toint());
        assertEquals(0, evaluate(true, 1).get(1).toint());
        now += 1100;
        assertEquals(1, evaluate(true, 1).get(1).toint());
        assertEquals(1, scores.get("global").size());
    }
    @Test void repeatedAcquisitionsAtSameMicrosecondStayUniqueAndReturnRemainingPlusOne() {
        assertEquals(3, evaluate(true, 3).get(1).toint());
        assertEquals(2, evaluate(true, 3).get(1).toint());
        assertEquals(1, evaluate(true, 3).get(1).toint());
        assertEquals(0, evaluate(true, 3).get(1).toint());
        assertEquals(3, scores.get("global").size());
    }

    @Test void singleDimensionScriptNeverAccessesAnUndeclaredClusterKey() {
        Globals lua = JsePlatform.standardGlobals();
        LuaTable redis = new LuaTable();
        redis.set("call", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if ("TIME".equals(args.arg(1).tojstring())) {
                    return table(LuaValue.valueOf(1000), LuaValue.valueOf(123456));
                }
                assertEquals("rate_limit:strategy:global", args.arg(2).tojstring());
                return LuaValue.ZERO;
            }
        });
        lua.set("redis", redis);
        lua.set("KEYS", table(LuaValue.valueOf("rate_limit:strategy:global")));
        lua.set("ARGV", table(LuaValue.valueOf(1000), LuaValue.valueOf(2), LuaValue.valueOf(1)));
        assertEquals(3, lua.load(new RedisConfig().rateLimitScript().getScriptAsString()).call().toint());
    }
    @Test void clusterNeverEnablesMultiKeyBatch() {
        var limiter = new DistributedRateLimiter();
        ReflectionTestUtils.setField(limiter, "redisMode", "cluster");
        assertFalse(limiter.supportsBatch());
    }
}
