package com.seckill.service;

import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ============================================================================
 * 库存回补的唯一入口：把货还回它被扣走的那个池子
 * ============================================================================
 * <p>
 * 一件货可能是在两个完全不同的地方扣走的：
 * <ul>
 *   <li>正常模式 —— Redis 分片库存，回补走 {@code stock_rollback.lua}</li>
 *   <li>Redis 降级窗口 —— 本机内存里的兜底库存，回补走 {@link LocalFallbackService}</li>
 * </ul>
 * 此前所有回补点都无条件调 Redis。降级窗口里卖出的那笔在 Redis 防重 Set 里没有记录，
 * Lua 的 SISMEMBER 守卫会让它返回 0（还算是个 no-op，不会凭空给 Redis 加库存），但
 * <b>本机那份库存和购买记录永远还原不了</b>：那件货就此消失（少卖），而买家还卡在本地
 * 防重记录里，恢复回写时又被当成真实销量扣一遍 Redis。
 * <p>
 * 判定「这笔货是谁卖的」不靠 {@code isDegraded()}：降级开关会在扣减和回补之间翻转
 * （自动恢复、手动恢复、又一轮降级），拿它判定必然错一半。这里靠两边各自的自证：
 * <ol>
 *   <li>先问本地 —— {@link LocalFallbackService#rollbackStockLocal} 只在本机确实卖给过
 *       该用户足够件数时才动手，否则一格库存都不加、返回 false</li>
 *   <li>本地不认，才走 Redis —— {@code stock_rollback.lua} 同样有 SISMEMBER 守卫</li>
 * </ol>
 * 两边都只会回补自己真扣走过的货，所以顺序无关、重复调用也不会凭空造库存。
 * <p>
 * <b>已知的一个残留缺口</b>：同一个用户既在降级前从 Redis 买过、又在降级窗口里从本机买过
 * （本地防重记录是上架时新建的，不知道 Redis 那边卖过什么），此时失败的是哪一笔无从分辨，
 * 本类会优先认成"本机卖的那笔"。要彻底关掉它得在降级那一刻把 Redis 的 bought set 灌进本地
 * 防重记录 —— 但 autoDegrade 时 Redis 已经读不到，而 SMEMBERS 一个百万级的 Set 本身就是
 * 事故，所以这里选择留下缺口而不是引入更大的风险。后果是这一笔回补走错了池子（少卖一件），
 * 由对账服务告警、人工核对。
 * <p>
 * <b>所有回补都必须走这里</b>，不要再直接调 {@link SeckillDeductLua#rollbackStock}，
 * 否则新加的回补点会把降级窗口的那笔货弄丢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRollbackDispatcher {

    private final SeckillDeductLua seckillDeductLua;
    private final LocalFallbackService localFallbackService;

    /**
     * 回补库存。
     *
     * @return 与 {@link SeckillDeductLua#rollbackStock} 同义：
     *         ≥ {@link SeckillDeductLua#LUA_SUCCESS_BASE} 表示回补成功
     *         （本地回补没有分片概念，统一记为分片 0），0 表示该用户没买过，负数表示失败
     */
    public long rollback(Long seckillId, Long userId, int quantity) {
        if (localFallbackService.rollbackStockLocal(seckillId, userId, quantity)) {
            return SeckillDeductLua.LUA_SUCCESS_BASE;
        }
        return seckillDeductLua.rollbackStock(seckillId, userId, quantity);
    }
}
