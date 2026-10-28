package com.seckill.service;

import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** No inventory is minted from ratios or stale snapshots. Independent escrow is not implemented. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStockShelvingService {
    // Retain the constructor for callers; none of these sources can prove exclusive stock ownership.
    private final ActivityMapper activityMapper;
    private final TransactionLogMapper transactionLogMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final LocalFallbackService localFallbackService;
    private final AlertService alertService;
    public int shelveForActiveActivities(String trigger) {
        log.warn("Redis degraded: new stock reservations suspended; exclusive local quota is unavailable, trigger={}",trigger);
        return 0;
    }
}
