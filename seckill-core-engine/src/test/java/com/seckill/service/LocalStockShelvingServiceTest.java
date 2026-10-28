package com.seckill.service;

import com.seckill.mapper.*;
import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalStockShelvingServiceTest {
    @Test void degradedInstancesDoNotMintInventoryFromSharedSnapshots(){
        var activities=mock(ActivityMapper.class);var tx=mock(TransactionLogMapper.class);var redis=mock(SeckillDeductLua.class);
        var local=mock(LocalFallbackService.class);var alerts=mock(AlertService.class);
        for(int instance=0;instance<100;instance++) {
            var service=new LocalStockShelvingService(activities,tx,redis,local,alerts);
            assertEquals(0,service.shelveForActiveActivities("autoscale"));
        }
        verifyNoInteractions(activities,tx,redis,local,alerts);
    }
}
