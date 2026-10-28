package com.seckill.config;

/** v1 preserves existing rows; v2 uses independent bits for database and table. */
public final class OrderShardRouting {
    private OrderShardRouting() {}
    public static int database(long userId) { return Math.floorMod(userId, 8); }
    public static int table(long userId, int version) {
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported order routing version");
        return (int) Math.floorMod(version == 2 ? Math.floorDiv(userId, 8) : userId, 16);
    }
    public static String bucket(long userId, int version) {
        return database(userId) + ":" + table(userId, version);
    }
}
