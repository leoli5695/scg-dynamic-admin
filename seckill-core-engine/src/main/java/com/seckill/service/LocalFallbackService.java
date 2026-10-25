package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 本地兜底服务（Redis/MQ 故障时的降级处理）
 * ============================================================================
 * <p>
 * 功能:
 * 1. 本地库存计数器（Redis 故障时的简易扣减）
 * 2. 本地购买记录（简易防重）
 * 3. 本地订单缓冲队列（MQ 故障时的异步处理）
 * 4. 缓冲队列持久化（应用重启后恢复）
 * 5. 【OPTIMIZATION P2】Redis恢复后回写降级窗口的销量
 * <p>
 * 使用场景:
 * - Redis 故障时，启用本地库存计数器
 * - MQ 故障时，先写入本地缓冲队列，后续补偿
 * - Redis 恢复后，调用 syncToRedis() 把降级窗口的销量回写 Redis
 * <p>
 * 【多实例超卖防护】:
 * - 降级模式下每实例只上架「当前剩余库存 × stock-ratio」（默认 10%）
 * - 目的：宁愿少卖，绝不超卖
 * - 部署 N 个实例时全局最多透支 N × stock-ratio，运维需保证 N × stock-ratio ≤ 1
 * <p>
 * 【缓冲队列持久化】:
 * - 写入本地磁盘文件（追加写入）
 * - 应用启动时读取未发送的订单重新处理
 * - 避免应用崩溃时订单丢失
 * <p>
 * 【OPTIMIZATION P2 - Redis恢复同步】:
 * - syncToRedis(seckillId): 相对扣减回写降级窗口的销量，并把本地买家补登进 Redis 防重 Set
 * - 多实例可叠加、与同步顺序无关；不是按分片绝对 SET（详见方法注释）
 * - 回写后清空本地库存状态，释放内存
 * <p>
 * 注意:
 * - 本地兜底仅用于极端情况，存在少卖风险（刻意设计）
 * - 需配合对账服务进行数据修正
 * <p>
 * 监控指标:
 * - seckill.fallback.stock_deduct: 本地库存扣减次数
 * - seckill.fallback.queue_size: 本地缓冲队列大小
 * - seckill.fallback.sync_to_redis: Redis恢复同步次数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFallbackService {

    private final AlertService alertService;
    private final ObjectMapper objectMapper;  // 【修复】注入 ObjectMapper 进行 JSON 序列化
    private final SeckillConfig seckillConfig;

    /**
     * 降级模式下的唯一库存真相源：库存与该活动全部用户购买记录封装在一个不可变
     * StockState 里，只能整体 CAS 替换。
     * <p>
     * 不要再加 AtomicInteger 镜像：扣减走 CAS、回补不走 CAS 时，回补会被下一次扣减整体
     * 覆盖掉，而 getLocalStock / syncToRedis 读的正是那份被覆盖的镜像。
     */
    private final Map<Long, AtomicReference<StockState>> stockStateMap = new ConcurrentHashMap<>();

    /**
     * syncToRedis 摘走状态后留在 AtomicReference 里的空壳：库存 0、无购买记录。
     * 并发中的 deductStockLocal 撞到它会立刻走"库存过低"分支返回 -3，而不是继续卖
     * 那批已经回写给 Redis 的货。
     */
    private static final StockState DRAINED = new StockState(0, new ConcurrentHashMap<>());

    /**
     * 本地订单缓冲队列（MQ 故障时暂存）
     */
    private final Map<String, OrderBufferItem> orderBuffer = new ConcurrentHashMap<>();

    /**
     * 【多实例超卖防护】降级模式下每实例上架的库存比例
     * <p>
     * 这是<b>每实例</b>比例：部署 N 个实例时，降级窗口内全局最多透支 N × ratio 的剩余库存。
     * Redis 已经挂了，实例之间无从协调，所以「N × ratio ≤ 1」只能由运维配置保证 ——
     * 默认 0.1 意味着 3 个实例合计最多卖出剩余库存的 30%，剩下 70% 在降级期间宁可少卖。
     */
    @Value("${seckill.fallback.stock-ratio:0.1}")
    private double fallbackStockRatio;

    /**
     * 【多实例超卖防护】降级模式下的并发阈值
     * 当本地库存低于此阈值时，直接返回"系统繁忙"
     */
    @Value("${seckill.fallback.min-stock-threshold:5}")
    private int minStockThreshold;

    /**
     * 【缓冲队列持久化】文件路径
     */
    @Value("${seckill.fallback.buffer-file-path:./data/fallback-orders.log}")
    private String bufferFilePath;

    /**
     * ============================================================================
     * 上架本机在降级窗口里可以卖的那份库存
     * ============================================================================
     * <p>
     * <b>入参必须是"当前剩余"，不是活动的初始总库存。</b> 传初始值的话，一个已经卖到
     * 只剩 3 件的活动在 Redis 挂掉时会被重新上架 ratio × 初始库存 件，直接超卖。
     * 剩余量由调用方（{@code LocalStockShelvingService}）负责算准。
     * <p>
     * <b>幂等</b>：走 putIfAbsent。降级 → 恢复失败 → 再降级 这种来回切换时，第二次
     * 必须保留原状态，否则这期间已经卖出去的量会被清零重写，既丢销量又双份上架。
     *
     * @param seckillId      秒杀活动ID
     * @param availableStock 当前剩余库存（不是初始总库存）
     * @return 本机上架的件数；如果本机此前已经为该活动上架过，返回那份存量的当前剩余
     */
    public int initLocalStock(Long seckillId, int availableStock) {
        // ratio 是「每实例」比例：部署 N 个实例时全局最多透支 N × ratio。
        // N × ratio > 1 就会超卖，这只能靠运维配置保证（Redis 已经挂了，无从协调），
        // 所以这里至少把 ratio 夹进 [0,1]，让 "想配 10% 却写成 10" 这种笔误不会上架 10 倍库存。
        double ratio = Math.min(1.0, Math.max(0.0, fallbackStockRatio));
        if (ratio != fallbackStockRatio) {
            log.error("seckill.fallback.stock-ratio={} 越界，已夹到 {}", fallbackStockRatio, ratio);
            alertService.sendCriticalAlert("降级库存比例配置越界",
                    "seckill.fallback.stock-ratio=" + fallbackStockRatio + " 不在 [0,1]，已按 " + ratio + " 处理");
        }

        int fallbackStock = (int) Math.ceil(Math.max(0, availableStock) * ratio);

        AtomicReference<StockState> fresh =
                new AtomicReference<>(new StockState(fallbackStock, new ConcurrentHashMap<>()));
        AtomicReference<StockState> existing = stockStateMap.putIfAbsent(seckillId, fresh);

        if (existing != null) {
            int remaining = existing.get().stock;
            log.warn("本地库存已上架，保留原状态: seckillId={}, 当前剩余={}（忽略本次 remaining={}）",
                    seckillId, remaining, availableStock);
            return remaining;
        }

        log.warn("本地库存上架: seckillId={}, availableStock={}, fallbackStock={}（降级模式，每实例{}比例）",
                seckillId, availableStock, fallbackStock, (int) (ratio * 100) + "%");
        return fallbackStock;
    }

    /**
     * 本机是否为该活动上架过降级库存（且尚未回写给 Redis）。
     */
    public boolean hasLocalStock(Long seckillId) {
        return stockStateMap.containsKey(seckillId);
    }

    /**
     * ============================================================================
     * 本地库存扣减（Redis 故障时的兜底）
     * ============================================================================
     * <p>
     * 【并发安全修复】：
     * - 使用AtomicReference.ensure复合状态（库存+购买记录）的原子性更新
     * - 避免防重检查和库存扣减之间的竞态条件
     * <p>
     * 【多实例超卖防护】：
     * - 当本地库存低于 minStockThreshold 时，返回"系统繁忙"
     * - 避免最后几个库存并发扣减时超卖
     * <p>
     * 返回值:
     * - > 0: 扣减成功，返回剩余库存
     * - 0: 扣减成功，库存归零
     * - -1: 库存不足
     * - -2: 已购买过（防重）
     * - -3: 系统繁忙（库存过低，拒绝扣减）
     */
    public int deductStockLocal(Long seckillId, Long userId, int quantity) {
        // 【并发安全修复】使用CAS循环确保原子性
        AtomicReference<StockState> stateRef = stockStateMap.get(seckillId);
        if (stateRef == null) {
            log.error("本地库存未初始化: seckillId={}", seckillId);
            return -1;
        }
        
        int maxBuyCount = seckillConfig.getMaxBuyCount();
        
        // CAS循环：原子性地检查并更新复合状态
        while (true) {
            StockState currentState = stateRef.get();
            
            // Step 1: 防重检查
            int currentBought = currentState.getUserBoughtCount(userId);
            if (currentBought >= maxBuyCount) {
                log.warn("本地防重检查：已购买过, seckillId={}, userId={}, bought={}", 
                        seckillId, userId, currentBought);
                return -2;
            }
            
            // Step 2: 检查库存阈值
            if (currentState.stock <= minStockThreshold) {
                log.warn("本地库存过低，拒绝扣减: seckillId={}, remaining={}, threshold={}",
                        seckillId, currentState.stock, minStockThreshold);
                alertService.sendAlert("降级模式库存不足",
                        "seckillId=" + seckillId + " 本地库存剩余 " + currentState.stock + "，拒绝新请求");
                return -3;
            }
            
            // Step 3: 检查库存充足性
            if (currentState.stock < quantity) {
                log.warn("本地库存不足: seckillId={}, remaining={}, request={}", 
                        seckillId, currentState.stock, quantity);
                return -1;
            }
            
            // Step 4: 创建新状态（不可变）
            int newStock = currentState.stock - quantity;
            StockState newState = currentState.withUpdatedStockAndUserBought(userId, quantity);
            
            // Step 5: CAS更新
            if (stateRef.compareAndSet(currentState, newState)) {
                log.warn("本地库存扣减成功: seckillId={}, userId={}, remaining={}（降级模式，{}比例）",
                        seckillId, userId, newStock, (int) (fallbackStockRatio * 100) + "%");
                return newStock;
            }
            // CAS失败，重试
        }
    }

    /**
     * ============================================================================
     * 本地库存回补
     * ============================================================================
     * <p>
     * 必须走和 deductStockLocal 同一个 CAS：库存和防重记录一起还原，否则回补的用户
     * 会永久卡在防重检查上。
     * <p>
     * <b>只认本机卖出去的货。</b> 用户在本地购买记录里凑不出 quantity 件时一律返回 false、
     * 一格库存都不加 —— 这是 {@link StockRollbackDispatcher} 能"先试本地、失败再走 Redis"
     * 的前提：Redis 卖出的那笔在这里必须是彻底的 no-op，否则每回补一笔 Redis 订单都会
     * 凭空给本地池加库存（超卖），而 Redis 那边一件也没还回去（少卖）。
     *
     * @return true 表示本机确实卖过、已就地回补；false 表示这笔货不是本机卖的，调用方应改走 Redis
     */
    public boolean rollbackStockLocal(Long seckillId, Long userId, int quantity) {
        AtomicReference<StockState> stateRef = stockStateMap.get(seckillId);
        if (stateRef == null) {
            // 常态：这个活动从没在本机降级卖过，或者销量已经回写给 Redis 了
            log.debug("本地无降级库存，这笔不是本机卖的: seckillId={}, userId={}", seckillId, userId);
            return false;
        }

        while (true) {
            StockState currentState = stateRef.get();

            if (currentState.getUserBoughtCount(userId) < quantity) {
                log.warn("本地购买记录不足以回补，交由Redis处理: seckillId={}, userId={}, 本地已购={}, 请求回补={}",
                        seckillId, userId, currentState.getUserBoughtCount(userId), quantity);
                return false;
            }

            StockState newState = currentState.withRolledBackStock(userId, quantity);
            if (stateRef.compareAndSet(currentState, newState)) {
                log.warn("本地库存回补: seckillId={}, userId={}, quantity={}, remaining={}（降级模式）",
                        seckillId, userId, quantity, newState.stock);
                return true;
            }
            // CAS失败，重试
        }
    }

    /**
     * ============================================================================
     * 订单缓冲队列写入（MQ 故障时的兜底）
     * ============================================================================
     * <p>
     * 【缓冲队列持久化】：
     * - 同时写入内存 Map 和磁盘文件
     * - 应用崩溃后可从文件恢复
     */
    public void bufferOrder(String orderNo, Object orderData) {
        OrderBufferItem item = new OrderBufferItem();
        item.orderNo = orderNo;
        item.orderData = orderData;
        item.createTime = System.currentTimeMillis();
        item.retryCount = 0;

        // 写入内存
        orderBuffer.put(orderNo, item);

        // 【持久化】写入磁盘文件
        persistBufferToFile(item);

        log.warn("订单已缓冲（降级模式）: orderNo={}, bufferSize={}", orderNo, orderBuffer.size());

        // 告警
        alertService.sendAlert("订单缓冲",
                "订单已写入本地缓冲队列，等待MQ恢复后处理: " + orderNo + ", 当前队列大小: " + orderBuffer.size());
    }

    /**
     * ============================================================================
     * 【持久化】将缓冲订单写入磁盘文件
     * ============================================================================
     * <p>
     * 【修复】：使用 ObjectMapper 序列化为 JSON，不能用 toString()
     * toString() 生成的是 "OrderMessage(transactionId=...)" 格式，无法反序列化
     */
    private void persistBufferToFile(OrderBufferItem item) {
        try {
            Path path = Paths.get(bufferFilePath);
            Path parentDir = path.getParent();

            // 创建目录（如果不存在）
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // 【修复】使用 ObjectMapper 序列化为 JSON
            String jsonPayload = objectMapper.writeValueAsString(item.orderData);

            // 追加写入文件（使用 JSON 格式）
            String line = String.format("%s|%s|%d|%d%n",
                    item.orderNo,
                    jsonPayload,  // <-- JSON 字符串，可反序列化
                    item.createTime,
                    item.retryCount);

            Files.write(path, line.getBytes(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);

            log.debug("缓冲订单已持久化: orderNo={}, file={}", item.orderNo, bufferFilePath);

        } catch (Exception e) {
            log.error("缓冲订单持久化失败: orderNo={}, error={}", item.orderNo, e.getMessage());
            // 持久化失败不影响内存写入，告警即可
            alertService.sendAlert("缓冲持久化失败", "orderNo=" + item.orderNo + ", error=" + e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 【持久化】应用启动时加载未发送的缓冲订单
     * ============================================================================
     * <p>
     * 【修复】：使用 ObjectMapper 反序列化 JSON 为 OrderMessage 对象
     */
    @jakarta.annotation.PostConstruct
    public void loadBufferFromFile() {
        try {
            Path path = Paths.get(bufferFilePath);
            if (!Files.exists(path)) {
                log.info("缓冲文件不存在，跳过加载: {}", bufferFilePath);
                return;
            }

            BufferedReader reader = Files.newBufferedReader(path);
            String line;
            int loadedCount = 0;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    OrderBufferItem item = new OrderBufferItem();
                    item.orderNo = parts[0];
                    item.createTime = Long.parseLong(parts[2]);
                    item.retryCount = Integer.parseInt(parts[3]);

                    // 【修复】使用 ObjectMapper 反序列化 JSON 为 OrderMessage
                    try {
                        item.orderData = objectMapper.readValue(parts[1], OrderMessage.class);
                    } catch (Exception parseEx) {
                        log.warn("反序列化缓冲订单失败，跳过: orderNo={}, error={}", item.orderNo, parseEx.getMessage());
                        continue;
                    }

                    // 只加载未处理的订单（createTime 在 24 小时内）
                    if (System.currentTimeMillis() - item.createTime < 24 * 60 * 60 * 1000) {
                        orderBuffer.put(item.orderNo, item);
                        loadedCount++;
                    }
                }
            }

            reader.close();
            log.warn("从磁盘加载缓冲订单: loadedCount={}, file={}", loadedCount, bufferFilePath);

            if (loadedCount > 0) {
                alertService.sendAlert("缓冲队列恢复",
                        "应用启动后从磁盘加载 " + loadedCount + " 个缓冲订单，等待MQ恢复后处理");
            }

        } catch (Exception e) {
            log.error("加载缓冲文件失败: error={}", e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 获取缓冲队列大小
     * ============================================================================
     */
    public int getBufferSize() {
        return orderBuffer.size();
    }

    /**
     * ============================================================================
     * 获取缓冲订单（用于补偿处理）
     * ============================================================================
     */
    public Map<String, OrderBufferItem> getBufferedOrders() {
        return new ConcurrentHashMap<>(orderBuffer);
    }

    /**
     * ============================================================================
     * 【持久化】清除已处理的缓冲订单（同时删除文件记录）
     * ============================================================================
     */
    public void clearBufferedOrder(String orderNo) {
        orderBuffer.remove(orderNo);
        log.info("缓冲订单已清除: orderNo={}", orderNo);

        // 更新文件（标记已处理）
        try {
            Path path = Paths.get(bufferFilePath);
            if (Files.exists(path)) {
                // 读取全部内容，过滤掉已处理的订单，重写文件
                String content = Files.readString(path);
                String newContent = content.lines()
                        .filter(line -> !line.startsWith(orderNo + "|"))
                        .collect(Collectors.joining("\n"));
                Files.writeString(path, newContent);
                log.debug("缓冲文件已更新: orderNo={}", orderNo);
            }
        } catch (Exception e) {
            log.warn("更新缓冲文件失败: orderNo={}, error={}", orderNo, e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 获取本地库存状态
     * ============================================================================
     */
    public int getLocalStock(Long seckillId) {
        AtomicReference<StockState> stateRef = stockStateMap.get(seckillId);
        return stateRef != null ? stateRef.get().stock : -1;
    }

    /**
     * ============================================================================
     * 获取本地购买记录（用于审计）
     * ============================================================================
     */
    public int getLocalBoughtCount(Long seckillId, Long userId) {
        AtomicReference<StockState> stateRef = stockStateMap.get(seckillId);
        return stateRef != null ? stateRef.get().getUserBoughtCount(userId) : 0;
    }

    /**
     * ============================================================================
     * 【OPTIMIZATION P2】Redis恢复后把降级窗口的销量回写Redis
     * ============================================================================
     * <p>
     * 回写的是"卖掉了多少"，不是"还剩多少"：
     * <ul>
     *   <li>降级期间没人能动 Redis，Redis 里的剩余库存原封不动</li>
     *   <li>本机上架的那份兜底库存从未真正从 Redis 扣走，没卖掉的部分直接丢弃即可 ——
     *       它们本来就在 Redis 里，恢复后自动重新可售</li>
     *   <li>真正欠 Redis 的只有卖出去的那几件，以及那批买家的防重记录</li>
     * </ul>
     * <p>
     * 因此这里走 {@link SeckillDeductLua#applyLocalSales} 的相对扣减：多实例各写各的，
     * 与同步顺序无关，且买家会被补登进 bought set，恢复后不会二次购买。
     * <b>绝不能改成 warmupStockOnly 那种按分片绝对 SET</b> —— 最后写入的实例会把自己
     * 那份余量当成全局真相，抹掉其它实例的销量（少卖）并把已卖出的库存重新上架（超卖）。
     * <p>
     * 使用场景：
     * - RedisDegradeService检测到Redis恢复后调用
     * - 手动触发同步（管理员操作）
     * <p>
     * 注意：
     * - 调用前必须确保Redis已恢复正常
     * - 同步后本地库存会被清空，无法再次扣减
     *
     * @param seckillDeductLua Lua脚本执行服务（由调用方传入，避免循环依赖）
     * @param seckillId        秒杀活动ID
     * @return 回写到Redis的销量件数；0 表示降级期间一件也没卖出；
     *         -1 表示本地库存未初始化；-2 表示回写失败（状态已还原，等下次重试）
     */
    public int syncToRedis(SeckillDeductLua seckillDeductLua, Long seckillId) {
        // 先从 map 原子摘走，再 getAndSet 取走最后一刻的状态：摘走保证同步之后本实例
        // 不能再卖那批已经回写给 Redis 的库存（双份上架），也兑现方法文档里「同步后
        // 本地库存会被清空，无法再次扣减」的承诺；getAndSet 则保证在摘走之前就已读到
        // stateRef 的并发线程，它那次 CAS 的结果一并被带走，不会漏记一笔销量。
        AtomicReference<StockState> stateRef = stockStateMap.remove(seckillId);

        if (stateRef == null) {
            log.warn("syncToRedis: 本地库存未初始化, seckillId={}", seckillId);
            return -1;
        }

        StockState state = stateRef.getAndSet(DRAINED);
        Map<Long, Integer> localSales = state.userBoughtMap;
        int locallySold = localSales.values().stream().mapToInt(Integer::intValue).sum();

        if (locallySold <= 0) {
            log.info("syncToRedis: 降级期间无销量, seckillId={}, 上架未售的 {} 件随 Redis 库存自动恢复",
                    seckillId, state.stock);
            return 0;
        }

        long applied;
        try {
            applied = seckillDeductLua.applyLocalSales(seckillId, localSales);
        } catch (Exception e) {
            return restoreAndFail(seckillId, stateRef, state, locallySold, e.getMessage());
        }

        if (applied < 0) {
            return restoreAndFail(seckillId, stateRef, state, locallySold, "Redis 脚本返回 " + applied);
        }

        if (applied < locallySold) {
            // Redis 剩余库存吸收不下本机卖出的量：降级窗口里已经超卖，必须人工核对订单，
            // 不能静默当成同步成功。
            log.error("syncToRedis 存在缺口: seckillId={}, locallySold={}, applied={}, 缺口={}",
                    seckillId, locallySold, applied, locallySold - applied);
            alertService.sendCriticalAlert("降级窗口超卖",
                    "seckillId=" + seckillId + " 降级期间本地售出 " + locallySold + " 件，Redis 仅能吸收 "
                            + applied + " 件，缺口 " + (locallySold - applied) + " 件，需人工核对订单");
        }

        log.info("syncToRedis成功: seckillId={}, locallySold={}, applied={}, 补登买家={}",
                seckillId, locallySold, applied, localSales.size());

        alertService.sendAlert("Redis恢复库存同步",
                "seckillId=" + seckillId + " 降级期间售出 " + locallySold + " 件已回写Redis，补登买家 "
                        + localSales.size() + " 人");

        return locallySold;
    }

    /**
     * 回写失败：把摘下来的状态原样放回，等下次重试。putIfAbsent 以免覆盖这期间
     * initLocalStock 建立的新状态。
     */
    private int restoreAndFail(Long seckillId, AtomicReference<StockState> stateRef,
                               StockState state, int locallySold, String reason) {
        log.error("syncToRedis失败: seckillId={}, locallySold={}, reason={}", seckillId, locallySold, reason);

        alertService.sendAlert("Redis恢复库存同步失败",
                "seckillId=" + seckillId + " 降级期间售出 " + locallySold + " 件回写失败: " + reason);

        stateRef.compareAndSet(DRAINED, state);
        stockStateMap.putIfAbsent(seckillId, stateRef);
        return -2;
    }

    /**
     * ============================================================================
     * 【OPTIMIZATION P2】批量回写所有活动的降级窗口销量
     * ============================================================================
     * <p>
     * 功能：
     * - 遍历所有本地库存，逐个回写到Redis
     * - 返回回写结果汇总
     * <p>
     * 使用场景：
     * - Redis全面恢复后批量同步
     * - 应用关闭前优雅停机
     *
     * @param seckillDeductLua Lua脚本执行服务
     * @return 回写结果Map: seckillId -&gt; 回写的销量件数（0 无销量，负数见 syncToRedis）
     */
    public Map<Long, Integer> syncAllToRedis(SeckillDeductLua seckillDeductLua) {
        Map<Long, Integer> syncResults = new ConcurrentHashMap<>();

        for (Long seckillId : new ArrayList<>(stockStateMap.keySet())) {
            int syncCount = syncToRedis(seckillDeductLua, seckillId);
            syncResults.put(seckillId, syncCount);
        }

        int totalSynced = syncResults.values().stream()
                .filter(v -> v > 0)
                .mapToInt(Integer::intValue)
                .sum();

        log.info("syncAllToRedis完成: 活动数={}, 总回写销量={}", syncResults.size(), totalSynced);

        if (totalSynced > 0) {
            alertService.sendAlert("Redis恢复批量库存同步",
                    "已回写 " + syncResults.size() + " 个活动的降级窗口销量，共 " + totalSynced + " 件");
        }

        return syncResults;
    }

    /**
     * ============================================================================
     * 【OPTIMIZATION P2】清理本地购买记录（活动结束后）
     * ============================================================================
     * <p>
     * 功能：
     * - 清理指定活动的本地购买记录
     * - 释放内存
     * <p>
     * 使用场景：
     * - 活动结束后清理
     * - 对账完成后清理
     *
     * @param seckillId 秒杀活动ID
     */
    public void clearLocalBoughtRecords(Long seckillId) {
        AtomicReference<StockState> stateRef = stockStateMap.get(seckillId);
        if (stateRef == null) {
            log.info("本地购买记录已清理（无本地状态）: seckillId={}", seckillId);
            return;
        }

        while (true) {
            StockState currentState = stateRef.get();
            if (stateRef.compareAndSet(currentState, currentState.withClearedBoughtRecords())) {
                log.info("本地购买记录已清理: seckillId={}", seckillId);
                return;
            }
            // CAS失败，重试
        }
    }

    /**
     * ============================================================================
     * 订单缓冲项
     * ============================================================================
     */
    public static class OrderBufferItem {
        public String orderNo;
        public Object orderData;
        public long createTime;
        public int retryCount;
    }
    
    /**
     * 【并发安全修复】不可变的库存状态封装
     * 确保库存和用户购买记录的原子性更新
     */
    private static class StockState {
        final int stock;
        final Map<Long, Integer> userBoughtMap; // userId -> bought count
        
        StockState(int stock, Map<Long, Integer> userBoughtMap) {
            this.stock = stock;
            this.userBoughtMap = new ConcurrentHashMap<>(userBoughtMap);
        }
        
        /**
         * 获取用户已购买数量
         */
        int getUserBoughtCount(Long userId) {
            return userBoughtMap.getOrDefault(userId, 0);
        }
        
        /**
         * 创建新状态：更新库存和用户购买数量（不可变）
         */
        StockState withUpdatedStockAndUserBought(Long userId, int quantity) {
            Map<Long, Integer> newBoughtMap = new ConcurrentHashMap<>(this.userBoughtMap);
            newBoughtMap.merge(userId, quantity, Integer::sum);
            return new StockState(this.stock - quantity, newBoughtMap);
        }

        /**
         * 创建新状态：回补库存并撤销该用户的购买记录（不可变）
         */
        StockState withRolledBackStock(Long userId, int quantity) {
            Map<Long, Integer> newBoughtMap = new ConcurrentHashMap<>(this.userBoughtMap);
            newBoughtMap.compute(userId, (k, bought) -> {
                if (bought == null) {
                    return null;
                }
                int remaining = bought - quantity;
                return remaining > 0 ? remaining : null;
            });
            return new StockState(this.stock + quantity, newBoughtMap);
        }

        /**
         * 创建新状态：保留库存，清空全部购买记录（不可变）
         */
        StockState withClearedBoughtRecords() {
            return new StockState(this.stock, new ConcurrentHashMap<>());
        }
    }
}