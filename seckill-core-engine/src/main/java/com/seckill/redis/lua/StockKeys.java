package com.seckill.redis.lua;

import java.util.ArrayList;
import java.util.List;

/** One activity is one atomic Redis slot, not independently scalable stock shards. */
public final class StockKeys {
    private StockKeys() {}
    private static String id(Long id, boolean tagged) { return tagged ? "{" + id + "}" : id.toString(); }
    public static String prefix(Long id, boolean tagged) { return "seckill:stock:" + id(id, tagged) + ":shard:"; }
    public static String bought(Long id, boolean tagged) { return "seckill:bought:" + id(id, tagged); }
    public static String users(Long id, boolean tagged) { return "seckill:user_shard:" + id(id, tagged); }
    public static String released(Long id, boolean tagged) { return "seckill:released:" + id(id, tagged); }
    public static List<String> script(Long id, int shards, boolean tagged, boolean rollback) {
        if (shards < 1 || shards > 128) throw new IllegalArgumentException("Stock shard count must be 1..128");
        List<String> keys = new ArrayList<>();
        keys.add(rollback ? users(id, tagged) : bought(id, tagged));
        keys.add(rollback ? bought(id, tagged) : users(id, tagged));
        keys.add(prefix(id, tagged));
        for (int i = 0; i < shards; i++) keys.add(prefix(id, tagged) + i);
        keys.add(released(id, tagged));
        return keys;
    }
}
