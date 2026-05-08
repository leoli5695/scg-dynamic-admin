package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 本地兜底服务（Redis/MQ 故障时的降级处理）
 * ============================================================================
 *
 * 功能:
 * 1. 本地库存计数器（Redis 故障时的简易扣减）
 * 2. 本地购买记录（简易防重）
 * 3. 本地订单缓冲队列（MQ 故障时的异步处理）
 * 4. 缓冲队列持久化（应用重启后恢复）
 *
 * 使用场景:
 * - Redis 故障时，启用本地库存计数器
 * - MQ 故障时，先写入本地缓冲队列，后续补偿
 *
 * 【多实例超卖防护】:
 * - 降级模式下，本地库存只分配 10% 的总库存
 * - 目的：宁愿少卖，绝不超卖
 * - 假设部署 N 个实例，每个实例最多卖 totalStock * 0.1
 *
 * 【缓冲队列持久化】:
 * - 写入本地磁盘文件（追加写入）
 * - 应用启动时读取未发送的订单重新处理
 * - 避免应用崩溃时订单丢失
 *
 * 注意:
 * - 本地兜底仅用于极端情况，存在少卖风险（刻意设计）
 * - 需配合对账服务进行数据修正
 *
 * 监控指标:
 * - seckill.fallback.stock_deduct: 本地库存扣减次数
 * - seckill.fallback.queue_size: 本地缓冲队列大小
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFallbackService {

    private final SeckillConfig seckillConfig;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;  // 【修复】注入 ObjectMapper 进行 JSON 序列化

    /**
     * 本地库存计数器
     * Key: seckillId
     * Value: 剩余库存
     */
    private final Map<Long, AtomicInteger> localStockMap = new ConcurrentHashMap<>();

    /**
     * 本地购买记录（防重）
     * Key: seckillId:userId
     * Value: 已购买数量
     */
    private final Map<String, AtomicInteger> localBoughtMap = new ConcurrentHashMap<>();

    /**
     * 本地订单缓冲队列（MQ 故障时暂存）
     */
    private final Map<String, OrderBufferItem> orderBuffer = new ConcurrentHashMap<>();

    /**
     * 【多实例超卖防护】降级模式下本地库存比例
     * 默认 10%，假设 3 个实例，总共分配 30% 库存，避免超卖
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
     * 初始化本地库存（从预热数据或数据库加载）
     * ============================================================================
     *
     * 【多实例超卖防护】：
     * - 只分配 totalStock * fallbackStockRatio 的库存
     * - 宁愿少卖，绝不超卖
     */
    public void initLocalStock(Long seckillId, int totalStock) {
        // 降级模式下只分配部分库存（避免多实例超卖）
        int fallbackStock = (int) Math.ceil(totalStock * fallbackStockRatio);
        localStockMap.put(seckillId, new AtomicInteger(fallbackStock));
        log.warn("本地库存初始化: seckillId={}, totalStock={}, fallbackStock={}（降级模式，{}比例）", 
                seckillId, totalStock, fallbackStock, (int)(fallbackStockRatio * 100) + "%");
    }

    /**
     * ============================================================================
     * 本地库存扣减（Redis 故障时的兜底）
     * ============================================================================
     *
     * 【多实例超卖防护】：
     * - 当本地库存低于 minStockThreshold 时，返回"系统繁忙"
     * - 避免最后几个库存并发扣减时超卖
     *
     * 返回值:
     * - > 0: 扣减成功，返回剩余库存
     * - 0: 扣减成功，库存归零
     * - -1: 库存不足
     * - -2: 已购买过（防重）
     * - -3: 系统繁忙（库存过低，拒绝扣减）
     */
    public int deductStockLocal(Long seckillId, Long userId, int quantity) {
        // Step 1: 防重检查
        String boughtKey = seckillId + ":" + userId;
        AtomicInteger boughtCount = localBoughtMap.computeIfAbsent(boughtKey, k -> new AtomicInteger(0));

        int currentBought = boughtCount.get();
        int maxBuyCount = seckillConfig.getShardCount(); // 默认限购数量

        if (currentBought >= maxBuyCount) {
            log.warn("本地防重检查：已购买过, seckillId={}, userId={}", seckillId, userId);
            return -2;
        }

        // Step 2: 库存扣减
        AtomicInteger stock = localStockMap.get(seckillId);
        if (stock == null) {
            log.error("本地库存未初始化: seckillId={}", seckillId);
            return -1;
        }

        // 【多实例超卖防护】库存过低时直接拒绝
        int remaining = stock.get();
        if (remaining <= minStockThreshold) {
            log.warn("本地库存过低，拒绝扣减: seckillId={}, remaining={}, threshold={}", 
                    seckillId, remaining, minStockThreshold);
            alertService.sendAlert("降级模式库存不足", 
                    "seckillId=" + seckillId + " 本地库存剩余 " + remaining + "，拒绝新请求");
            return -3;
        }

        if (remaining < quantity) {
            log.warn("本地库存不足: seckillId={}, remaining={}, request={}", seckillId, remaining, quantity);
            return -1;
        }

        // CAS 扣减
        while (true) {
            int oldVal = stock.get();
            if (oldVal <= minStockThreshold) {
                return -3;  // 再次检查，防止并发穿透
            }
            if (oldVal < quantity) {
                return -1;
            }
            int newVal = oldVal - quantity;
            if (stock.compareAndSet(oldVal, newVal)) {
                // 更新购买记录
                boughtCount.addAndGet(quantity);
                log.warn("本地库存扣减成功: seckillId={}, userId={}, remaining={}（降级模式，{}比例）",
                        seckillId, userId, newVal, (int)(fallbackStockRatio * 100) + "%");
                return newVal;
            }
        }
    }

    /**
     * ============================================================================
     * 本地库存回补
     * ============================================================================
     */
    public void rollbackStockLocal(Long seckillId, Long userId, int quantity) {
        AtomicInteger stock = localStockMap.get(seckillId);
        if (stock != null) {
            stock.addAndGet(quantity);
            log.warn("本地库存回补: seckillId={}, userId={}, quantity={}（降级模式）",
                    seckillId, userId, quantity);
        }

        // 清除购买记录
        String boughtKey = seckillId + ":" + userId;
        AtomicInteger boughtCount = localBoughtMap.get(boughtKey);
        if (boughtCount != null) {
            boughtCount.addAndGet(-quantity);
        }
    }

    /**
     * ============================================================================
     * 订单缓冲队列写入（MQ 故障时的兜底）
     * ============================================================================
     *
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
     *
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
     *
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
        AtomicInteger stock = localStockMap.get(seckillId);
        return stock != null ? stock.get() : -1;
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
}