package com.seckill.service;

import com.seckill.dto.OrderMessage;
import com.seckill.entity.TransactionLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Compatibility facade: expiring Redis hints no longer authorize inventory release. */
@Service
@RequiredArgsConstructor
public class StockConfirmService {
    private final CompensationService compensation;
    public void createPendingMark(OrderMessage ignored) { }
    public void confirmPendingMark(Long seckillId,String orderNo) { }
    public void checkPendingStock() { compensation.processShortTimeoutTransactions(); }
    public boolean processPendingTransaction(TransactionLog tx) {
        compensation.processTimeoutTransaction(tx,"pending-hint");return false;
    }
}
