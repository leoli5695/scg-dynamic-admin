package com.seckill.service;

import com.seckill.entity.SeckillOrder;
import com.seckill.mapper.OrderMapper;
import com.seckill.config.SeckillConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** Terminal transitions use DB compare-and-set; Redis effects are handled by StockReleaseService. */
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {
    private final OrderMapper orders;
    private final SeckillConfig config;

    public SeckillOrder cancelExpired(Long userId, String orderNo) {
        orders.cancelExpired(userId, orderNo, LocalDateTime.now().minusSeconds(config.getDelay().getUnpaidTimeoutSeconds()));
        return orders.selectByUserAndOrderNo(userId, orderNo);
    }
    public SeckillOrder refundPaid(Long userId, String orderNo, java.math.BigDecimal amount) {
        orders.refundPaid(userId, orderNo, amount);
        return orders.selectByUserAndOrderNo(userId, orderNo);
    }
}
