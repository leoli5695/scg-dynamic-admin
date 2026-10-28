package com.seckill.service;

import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/** Reconciliation never releases inventory based on an absent order or a stale order snapshot. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationService {
    private final OrderMapper orders;
    private final TransactionLogMapper transactions;
    private final OrderLifecycleService lifecycle;
    private final StockReleaseService releases;

    @Scheduled(fixedDelay=60000)
    @SchedulerLock(name="CompensationService_processShortTimeoutTransactions",lockAtMostFor="2m")
    public void processShortTimeoutTransactions() {
        for (var tx : transactions.selectTimeoutTransactions(0,LocalDateTime.now().minusMinutes(5))) {
            try { processTimeoutTransaction(tx,"scheduled"); }
            catch(Exception e) { log.warn("Transaction reconciliation deferred: transactionId={}",tx.getTransactionId(),e); }
        }
    }
    /** Compatibility entry point. The short scan already includes all older pending transactions. */
    public void processLongTimeoutTransactions() { processShortTimeoutTransactions(); }

    public void processTimeoutTransaction(TransactionLog tx,String source) {
        var current=transactions.selectByTransactionId(tx.getTransactionId());
        if(current==null || !Integer.valueOf(0).equals(current.getStatus()))return;
        var order=orders.selectByUserAndOrderNo(current.getUserId(),current.getOrderNo());
        if(order==null) {
            // A consumer can still be writing this order. Absence is not proof that its stock is free.
            log.warn("Unresolved reservation requires reconciliation: transactionId={}",current.getTransactionId());return;
        }
        if(Integer.valueOf(0).equals(order.getStatus()))order=lifecycle.cancelExpired(order.getUserId(),order.getOrderNo());
        if(order==null)return;
        if(Integer.valueOf(2).equals(order.getStatus())||Integer.valueOf(3).equals(order.getStatus()))
            transactions.markOrderTerminal(order.getUserId(),order.getOrderNo());
        else transactions.markOrdersCreated(List.of(current.getTransactionId()));
    }
    @Scheduled(fixedDelay=300000)
    @SchedulerLock(name="CompensationService_processUnpaidOrders",lockAtMostFor="10m")
    public void processUnpaidOrders() {
        for(var order:orders.selectUnpaidOrders(LocalDateTime.now().minusMinutes(20))) {
            try {cancelUnpaidOrder(order);}catch(Exception e){log.warn("Order cancellation deferred: orderNo={}",order.getOrderNo(),e);}
        }
    }
    public void cancelUnpaidOrder(SeckillOrder staleSnapshot) {
        lifecycle.cancelExpired(staleSnapshot.getUserId(),staleSnapshot.getOrderNo());
    }
    public boolean manualRollback(Long seckillId,Long userId,int quantity) {
        var order=orders.selectByUserAndSeckill(userId,seckillId);
        if(order==null||!Integer.valueOf(quantity).equals(order.getQuantity())||order.getStockReleaseId()==null)return false;
        if(Integer.valueOf(2).equals(order.getStockReleaseState()))return true;
        return releases.release(order);
    }
}
