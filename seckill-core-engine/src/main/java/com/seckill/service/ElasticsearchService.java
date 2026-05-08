package com.seckill.service;

import com.seckill.dto.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * ============================================================================
 * Elasticsearch 服务（异构索引查询）
 * ============================================================================
 * 
 * 功能:
 * 1. 索引订单（异构索引）
 * 2. 更新订单状态
 * 3. 多条件组合查询（解决非分片键查询问题）
 * 4. 分页查询（解决跨库分页问题）
 * 5. 统计分析（活动效果统计）
 * 
 * 索引名称: order_index
 * 
 * 设计原理:
 * - ShardingSphere 分库分表后，非分片键查询需要扫描所有库，性能极差
 * - ES 作为异构索引，存储订单副本，支持任意条件查询
 * - 运营后台查询全部切 ES，避免打到分片数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 索引名称
     */
    private static final String ORDER_INDEX = "order_index";

    /**
     * ============================================================================
     * 索引订单
     * ============================================================================
     * 
     * 使用 orderNo 作为文档 ID，保证后续更新操作可以正确匹配
     */
    public void indexOrder(OrderMessage orderMessage) {
        try {
            OrderDocument document = new OrderDocument();
            document.setOrderNo(orderMessage.getOrderNo());
            document.setUserId(orderMessage.getUserId());
            document.setSeckillId(orderMessage.getSeckillId());
            document.setProductId(orderMessage.getProductId());
            document.setQuantity(orderMessage.getQuantity());
            document.setTotalAmount(orderMessage.getTotalAmount());
            document.setStatus(0); // 待支付
            document.setCreateTime(java.time.LocalDateTime.now());

            IndexCoordinates index = IndexCoordinates.of(ORDER_INDEX);
            
            // 先检查是否存在（避免覆盖）
            if (elasticsearchOperations.exists(orderMessage.getOrderNo(), index)) {
                log.info("ES文档已存在，跳过: orderNo={}", orderMessage.getOrderNo());
                return;
            }
            
            elasticsearchOperations.save(document, index);
            log.info("ES索引创建成功: orderNo={}", orderMessage.getOrderNo());

        } catch (Exception e) {
            log.error("ES索引创建失败: orderNo={}, error={}", orderMessage.getOrderNo(), e.getMessage());
            throw new RuntimeException("ES索引创建失败", e);
        }
    }

    /**
     * ============================================================================
     * 更新订单状态
     * ============================================================================
     * 
     * 使用 orderNo 作为文档 ID 进行更新
     */
    public void updateOrderStatus(String orderNo, int status) {
        try {
            UpdateQuery updateQuery = UpdateQuery.builder(orderNo)
                    .withDocument(Document.create().append("status", status))
                    .withDocAsUpsert(false)
                    .build();

            elasticsearchOperations.update(updateQuery, IndexCoordinates.of(ORDER_INDEX));
            log.info("ES状态更新成功: orderNo={}, status={}", orderNo, status);

        } catch (Exception e) {
            log.error("ES状态更新失败: orderNo={}, error={}", orderNo, e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 按活动查询订单（简单查询）
     * ============================================================================
     * 
     * 用于运营后台查看活动效果
     */
    public List<OrderDocument> queryBySeckillId(Long seckillId, int size) {
        try {
            Criteria criteria = Criteria.where("seckillId").is(seckillId);
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setMaxResults(size);

            var hits = elasticsearchOperations.search(query, OrderDocument.class, IndexCoordinates.of(ORDER_INDEX));

            return hits.getSearchHits().stream()
                    .map(hit -> hit.getContent())
                    .toList();
        } catch (Exception e) {
            log.error("ES查询失败: seckillId={}, error={}", seckillId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * ============================================================================
     * 多条件组合查询（解决非分片键查询问题）
     * ============================================================================
     * 
     * 支持以下查询条件：
     * - seckillId: 活动ID
     * - userId: 用户ID（非分片键查询）
     * - productId: 商品ID（非分片键查询）
     * - status: 订单状态
     * - createTime 范围查询
     * 
     * 运营后台场景：
     * - 查询某活动的所有订单
     * - 查询某用户的购买记录
     * - 查询某商品的销量
     * - 查询待支付/已取消订单
     */
    public List<OrderDocument> queryByConditions(Long seckillId, Long userId, Long productId, 
                                                   Integer status, LocalDateTime startTime, 
                                                   LocalDateTime endTime, int page, int size) {
        try {
            Criteria criteria = new Criteria();

            if (seckillId != null) {
                criteria = criteria.and("seckillId").is(seckillId);
            }
            if (userId != null) {
                criteria = criteria.and("userId").is(userId);
            }
            if (productId != null) {
                criteria = criteria.and("productId").is(productId);
            }
            if (status != null) {
                criteria = criteria.and("status").is(status);
            }
            if (startTime != null && endTime != null) {
                criteria = criteria.and("createTime").between(startTime, endTime);
            }

            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setPageable(org.springframework.data.domain.PageRequest.of(page, size));

            var hits = elasticsearchOperations.search(query, OrderDocument.class, IndexCoordinates.of(ORDER_INDEX));

            log.info("ES多条件查询: seckillId={}, userId={}, productId={}, status={}, resultCount={}", 
                    seckillId, userId, productId, status, hits.getTotalHits());

            return hits.getSearchHits().stream()
                    .map(hit -> hit.getContent())
                    .toList();
        } catch (Exception e) {
            log.error("ES多条件查询失败: error={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * ============================================================================
     * 分页查询（解决跨库分页问题）
     * ============================================================================
     *
     * ShardingSphere 分库分表后，ORDER BY + LIMIT 需要每个库都执行排序，
     * 然后在内存中合并，性能极差。
     *
     * ES 分页查询：
     * - 支持 from + size 分页（适合小数据量）
     * - 支持搜索_after 分页（适合大数据量滚动查询）
     */
    public org.springframework.data.domain.Page<OrderDocument> queryWithPagination(
            Long seckillId, Integer status, int page, int size) {
        try {
            Criteria criteria = new Criteria();

            if (seckillId != null) {
                criteria = criteria.and("seckillId").is(seckillId);
            }
            if (status != null) {
                criteria = criteria.and("status").is(status);
            }

            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setPageable(org.springframework.data.domain.PageRequest.of(page, size));
            query.addSort(org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.desc("createTime")));

            var hits = elasticsearchOperations.search(query, OrderDocument.class, IndexCoordinates.of(ORDER_INDEX));

            List<OrderDocument> content = hits.getSearchHits().stream()
                    .map(hit -> hit.getContent())
                    .toList();

            return new org.springframework.data.domain.PageImpl<>(content, 
                    org.springframework.data.domain.PageRequest.of(page, size), 
                    hits.getTotalHits());
        } catch (Exception e) {
            log.error("ES分页查询失败: error={}", e.getMessage());
            return org.springframework.data.domain.Page.empty();
        }
    }

    /**
     * ============================================================================
     * 【深分页】search_after 游标分页
     * ============================================================================
     *
     * ES from + size 分页限制：
     * - 默认最大 10000 条（index.max_result_window）
     * - 超过 10000 会报错："Result window is too large"
     *
     * search_after 原理：
     * - 使用上一页最后一条记录的排序值作为游标
     * - 每次查询从游标位置开始，避免深度分页的性能问题
     * - 需要唯一排序字段（如 orderNo）保证分页不重复/不遗漏
     *
     * 使用场景：
     * - 运营后台翻页超过 100 页时
     * - 大数据量导出时
     *
     * @param seckillId 活动ID
     * @param status 订单状态
     * @param lastCreateTime 上一页最后一条记录的 createTime
     * @param lastOrderNo 上一页最后一条记录的 orderNo（辅助排序，保证唯一性）
     * @param size 每页大小
     * @return 搜索结果（包含 search_after 游标信息）
     */
    public SearchAfterResult queryWithSearchAfter(Long seckillId, Integer status,
                                                    LocalDateTime lastCreateTime, String lastOrderNo, int size) {
        try {
            // 构建 JSON 查询
            StringBuilder queryJson = new StringBuilder();
            queryJson.append("{");
            queryJson.append("\"query\":{");
            queryJson.append("\"bool\":{");
            queryJson.append("\"must\":[");

            if (seckillId != null) {
                queryJson.append("{\"term\":{\"seckillId\":").append(seckillId).append("}}");
            }
            if (status != null) {
                if (seckillId != null) {
                    queryJson.append(",");
                }
                queryJson.append("{\"term\":{\"status\":").append(status).append("}}");
            }

            queryJson.append("]");
            queryJson.append("}");
            queryJson.append("},");
            queryJson.append("\"size\":").append(size).append(",");
            queryJson.append("\"sort\":[");

            // 按 createTime 降序，orderNo 升序（保证唯一性）
            queryJson.append("{\"createTime\":{\"order\":\"desc\"}},");
            queryJson.append("{\"orderNo\":{\"order\":\"asc\"}}");  // 辅助排序字段
            queryJson.append("]");

            // 添加 search_after 游标
            if (lastCreateTime != null && lastOrderNo != null) {
                queryJson.append(",\"search_after\":[");
                // createTime 转换为 ES 格式
                String esTime = lastCreateTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                queryJson.append("\"").append(esTime).append("\",");
                queryJson.append("\"").append(lastOrderNo).append("\"");
                queryJson.append("]");
            }

            queryJson.append("}");

            StringQuery query = StringQuery.builder(queryJson.toString()).build();

            var hits = elasticsearchOperations.search(query, OrderDocument.class, IndexCoordinates.of(ORDER_INDEX));

            List<OrderDocument> content = hits.getSearchHits().stream()
                    .map(hit -> hit.getContent())
                    .toList();

            // 提取最后一条记录的游标值
            String nextCreateTime = null;
            String nextOrderNo = null;
            if (!content.isEmpty()) {
                OrderDocument lastDoc = content.get(content.size() - 1);
                nextCreateTime = lastDoc.getCreateTime() != null ? 
                        lastDoc.getCreateTime().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
                nextOrderNo = lastDoc.getOrderNo();
            }

            SearchAfterResult result = new SearchAfterResult();
            result.setContent(content);
            result.setTotalHits(hits.getTotalHits());
            result.setHasMore(content.size() == size);  // 如果返回满页，可能还有更多数据
            result.setLastCreateTime(nextCreateTime);
            result.setLastOrderNo(nextOrderNo);

            log.info("ES深分页查询: seckillId={}, size={}, resultCount={}, hasMore={}", 
                    seckillId, size, content.size(), result.isHasMore());

            return result;

        } catch (Exception e) {
            log.error("ES深分页查询失败: error={}", e.getMessage());
            return new SearchAfterResult();
        }
    }

    /**
     * ============================================================================
     * 【深分页】搜索结果
     * ============================================================================
     */
    public static class SearchAfterResult {
        private List<OrderDocument> content;
        private long totalHits;
        private boolean hasMore;
        private String lastCreateTime;  // 下次查询的游标
        private String lastOrderNo;     // 下次查询的游标

        public List<OrderDocument> getContent() { return content; }
        public void setContent(List<OrderDocument> content) { this.content = content; }
        public long getTotalHits() { return totalHits; }
        public void setTotalHits(long totalHits) { this.totalHits = totalHits; }
        public boolean isHasMore() { return hasMore; }
        public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
        public String getLastCreateTime() { return lastCreateTime; }
        public void setLastCreateTime(String lastCreateTime) { this.lastCreateTime = lastCreateTime; }
        public String getLastOrderNo() { return lastOrderNo; }
        public void setLastOrderNo(String lastOrderNo) { this.lastOrderNo = lastOrderNo; }
    }

    /**
     * ============================================================================
     * 活动效果统计（聚合查询）
     * ============================================================================
     *
     * 统计指标：
     * - 总订单数
     * - 待支付订单数
     * - 已支付订单数
     * - 已取消订单数（超时未支付）
     * - 总销售额
     */
    public ActivityStats getActivityStats(Long seckillId) {
        try {
            // 使用 StringQuery 执行聚合查询
            String queryJson = String.format(
                "{\"size\":0,\"query\":{\"term\":{\"seckillId\":%d}},\"aggs\":{\"status_count\":{\"terms\":{\"field\":\"status\"}},\"total_amount\":{\"sum\":{\"field\":\"totalAmount\"}}}}",
                seckillId
            );

            StringQuery query = StringQuery.builder(queryJson).build();
            var response = elasticsearchOperations.search(query, OrderDocument.class, IndexCoordinates.of(ORDER_INDEX));

            ActivityStats stats = new ActivityStats();
            stats.setSeckillId(seckillId);
            // 解析聚合结果（简化处理，实际需要解析 response 的聚合数据）
            stats.setTotalOrders((int) response.getTotalHits());

            log.info("活动统计: seckillId={}, totalOrders={}", seckillId, stats.getTotalOrders());
            return stats;

        } catch (Exception e) {
            log.error("活动统计失败: seckillId={}, error={}", seckillId, e.getMessage());
            return new ActivityStats();
        }
    }

    /**
     * ============================================================================
     * 活动统计结果
     * ============================================================================
     */
    public static class ActivityStats {
        private Long seckillId;
        private int totalOrders;
        private int pendingPaymentOrders;
        private int paidOrders;
        private int cancelledOrders;
        private BigDecimal totalAmount;

        public Long getSeckillId() { return seckillId; }
        public void setSeckillId(Long seckillId) { this.seckillId = seckillId; }
        public int getTotalOrders() { return totalOrders; }
        public void setTotalOrders(int totalOrders) { this.totalOrders = totalOrders; }
        public int getPendingPaymentOrders() { return pendingPaymentOrders; }
        public void setPendingPaymentOrders(int pendingPaymentOrders) { this.pendingPaymentOrders = pendingPaymentOrders; }
        public int getPaidOrders() { return paidOrders; }
        public void setPaidOrders(int paidOrders) { this.paidOrders = paidOrders; }
        public int getCancelledOrders() { return cancelledOrders; }
        public void setCancelledOrders(int cancelledOrders) { this.cancelledOrders = cancelledOrders; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    }

    /**
     * ============================================================================
     * ES订单文档
     * ============================================================================
     */
    @org.springframework.data.elasticsearch.annotations.Document(indexName = ORDER_INDEX)
    public static class OrderDocument {
        @org.springframework.data.annotation.Id
        private String orderNo;
        private Long userId;
        private Long seckillId;
        private Long productId;
        private Integer quantity;
        private java.math.BigDecimal totalAmount;
        private Integer status;
        private java.time.LocalDateTime createTime;

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getSeckillId() { return seckillId; }
        public void setSeckillId(Long seckillId) { this.seckillId = seckillId; }
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public java.math.BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(java.math.BigDecimal totalAmount) { this.totalAmount = totalAmount; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public java.time.LocalDateTime getCreateTime() { return createTime; }
        public void setCreateTime(java.time.LocalDateTime createTime) { this.createTime = createTime; }
    }
}