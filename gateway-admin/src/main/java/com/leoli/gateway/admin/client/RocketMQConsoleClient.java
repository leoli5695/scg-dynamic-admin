package com.leoli.gateway.admin.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * RocketMQ Console API 客户端
 *
 * 通过 RocketMQ Console 的 HTTP API 获取监控指标
 * 替代 Prometheus Exporter 方式（因为 exporter v0.0.2 存在 bug）
 *
 * Console API 端点：
 * - /topic/list.query - Topic 列表
 * - /topic/stats.query?topic=<topic> - Topic 统计
 * - /consumer/groupList.query - 消费组列表（含 diffTotal = 消息堆积）
 *
 * @author leoli
 */
@Slf4j
@Component
public class RocketMQConsoleClient {

    @Value("${gateway.middleware.rocketmq-console-url:http://localhost:30808}")
    private String consoleUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 查询 RocketMQ 集群概览指标
     *
     * @return 包含 topic 数量、消费组数量、消息堆积等指标的 Map
     */
    public Map<String, Object> queryClusterOverview() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        try {
            // 1. 获取 Topic 列表
            Map<String, Object> topicListResponse = queryApi("/topic/list.query");
            if (topicListResponse != null && topicListResponse.get("data") != null) {
                Map<String, Object> topicData = (Map<String, Object>) topicListResponse.get("data");
                List<String> topics = (List<String>) topicData.get("topicList");
                if (topics != null) {
                    metrics.put("topicCount", topics.size());
                    // 过滤掉系统 Topic（以 RMQ_SYS 或 SCHEDULE_TOPIC 开头）
                    long userTopicCount = topics.stream()
                            .filter(t -> !t.startsWith("RMQ_SYS") && !t.startsWith("SCHEDULE_TOPIC")
                                    && !t.startsWith("rmq_sys") && !t.equals("OFFSET_MOVED_EVENT")
                                    && !t.equals("SELF_TEST_TOPIC") && !t.equals("TBW102"))
                            .count();
                    metrics.put("userTopicCount", userTopicCount);
                }
            }

            // 2. 获取消费组列表
            Map<String, Object> consumerGroupResponse = queryApi("/consumer/groupList.query");
            if (consumerGroupResponse != null && consumerGroupResponse.get("data") != null) {
                List<Map<String, Object>> groups = (List<Map<String, Object>>) consumerGroupResponse.get("data");
                if (groups != null) {
                    metrics.put("consumerGroupCount", groups.size());

                    // 计算总消息堆积
                    long totalLag = 0;
                    long maxLag = 0;
                    String maxLagGroup = null;
                    for (Map<String, Object> group : groups) {
                        Object diffTotal = group.get("diffTotal");
                        if (diffTotal != null && diffTotal instanceof Number) {
                            long lag = ((Number) diffTotal).longValue();
                            if (lag > 0) {
                                totalLag += lag;
                                if (lag > maxLag) {
                                    maxLag = lag;
                                    maxLagGroup = (String) group.get("group");
                                }
                            }
                        }
                    }
                    metrics.put("totalMessageLag", totalLag);
                    metrics.put("maxMessageLag", maxLag);
                    if (maxLagGroup != null) {
                        metrics.put("maxLagConsumerGroup", maxLagGroup);
                    }
                }
            }

            // 3. 标记数据来源
            metrics.put("_dataSource", "rocketmq-console");
            metrics.put("_consoleUrl", consoleUrl);

            log.debug("RocketMQ Console metrics: {}", metrics);
            return metrics;

        } catch (Exception e) {
            log.error("Failed to query RocketMQ Console: {}", e.getMessage(), e);
            metrics.put("error", e.getMessage());
            metrics.put("_dataSource", "rocketmq-console-error");
            return metrics;
        }
    }

    /**
     * 查询指定 Topic 的统计信息
     *
     * @param topic Topic 名称
     * @return Topic 统计信息（包含 offset、queue 数量等）
     */
    public Map<String, Object> queryTopicStats(String topic) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        try {
            Map<String, Object> response = queryApi("/topic/stats.query?topic=" + topic);
            if (response != null && response.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                Map<String, Object> offsetTable = (Map<String, Object>) data.get("offsetTable");

                if (offsetTable != null) {
                    long totalMessages = 0;
                    int queueCount = 0;

                    for (Map.Entry<String, Object> entry : offsetTable.entrySet()) {
                        Map<String, Object> queueInfo = (Map<String, Object>) entry.getValue();
                        if (queueInfo != null) {
                            Object maxOffset = queueInfo.get("maxOffset");
                            if (maxOffset instanceof Number) {
                                totalMessages += ((Number) maxOffset).longValue();
                            }
                            queueCount++;
                        }
                    }

                    metrics.put("topic", topic);
                    metrics.put("totalMessages", totalMessages);
                    metrics.put("queueCount", queueCount);
                }
            }

            metrics.put("_dataSource", "rocketmq-console");
            return metrics;

        } catch (Exception e) {
            log.error("Failed to query Topic stats for {}: {}", topic, e.getMessage());
            metrics.put("error", e.getMessage());
            return metrics;
        }
    }

    /**
     * 查询指定消费组的详情
     *
     * @param consumerGroup 消费组名称
     * @return 消费组信息（包含消息堆积、消费 TPS 等）
     */
    public Map<String, Object> queryConsumerGroupDetail(String consumerGroup) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        try {
            // 从消费组列表中查找指定组
            Map<String, Object> response = queryApi("/consumer/groupList.query");
            if (response != null && response.get("data") != null) {
                List<Map<String, Object>> groups = (List<Map<String, Object>>) response.get("data");
                if (groups != null) {
                    for (Map<String, Object> group : groups) {
                        if (consumerGroup.equals(group.get("group"))) {
                            metrics.put("consumerGroup", consumerGroup);
                            metrics.put("diffTotal", group.get("diffTotal"));
                            metrics.put("consumeTps", group.get("consumeTps"));
                            metrics.put("count", group.get("count")); // 消费者实例数
                            break;
                        }
                    }
                }
            }

            metrics.put("_dataSource", "rocketmq-console");
            return metrics;

        } catch (Exception e) {
            log.error("Failed to query ConsumerGroup detail for {}: {}", consumerGroup, e.getMessage());
            metrics.put("error", e.getMessage());
            return metrics;
        }
    }

    /**
     * 获取所有消费组的消息堆积情况
     *
     * @return 消费组列表，包含每个组的堆积数
     */
    public List<Map<String, Object>> queryAllConsumerGroupLags() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Map<String, Object> response = queryApi("/consumer/groupList.query");
            if (response != null && response.get("data") != null) {
                List<Map<String, Object>> groups = (List<Map<String, Object>>) response.get("data");
                if (groups != null) {
                    for (Map<String, Object> group : groups) {
                        Map<String, Object> groupInfo = new LinkedHashMap<>();
                        groupInfo.put("group", group.get("group"));
                        groupInfo.put("diffTotal", group.get("diffTotal"));
                        groupInfo.put("consumeTps", group.get("consumeTps"));
                        groupInfo.put("count", group.get("count"));
                        result.add(groupInfo);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to query consumer group lags: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 获取所有 Topic 列表
     *
     * @return Topic 名称列表
     */
    public List<String> queryAllTopics() {
        List<String> topics = new ArrayList<>();

        try {
            Map<String, Object> response = queryApi("/topic/list.query");
            if (response != null && response.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                topics = (List<String>) data.get("topicList");
            }
        } catch (Exception e) {
            log.error("Failed to query topic list: {}", e.getMessage());
        }

        return topics != null ? topics : new ArrayList<>();
    }

    /**
     * 获取集群路由信息
     *
     * @return Broker 地址和名称信息
     */
    public Map<String, Object> queryClusterRoute() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Map<String, Object> response = queryApi("/topic/route.query?topic=DefaultCluster");
            if (response != null && response.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> brokerDatas = (List<Map<String, Object>>) data.get("brokerDatas");

                if (brokerDatas != null && !brokerDatas.isEmpty()) {
                    Map<String, Object> broker = brokerDatas.get(0);
                    result.put("cluster", broker.get("cluster"));
                    result.put("brokerName", broker.get("brokerName"));
                    result.put("brokerAddrs", broker.get("brokerAddrs"));
                }

                List<Map<String, Object>> queueDatas = (List<Map<String, Object>>) data.get("queueDatas");
                if (queueDatas != null) {
                    result.put("queueCount", queueDatas.size());
                }
            }
        } catch (Exception e) {
            log.error("Failed to query cluster route: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 执行 Console API 查询
     *
     * @param path API 路径（如 /topic/list.query）
     * @return API 响应的 Map 形式
     */
    private Map<String, Object> queryApi(String path) {
        try {
            String url = consoleUrl + path;
            log.debug("Querying RocketMQ Console API: {}", url);

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null) {
                Integer status = (Integer) response.get("status");
                if (status != null && status == 0) {
                    return response;
                } else {
                    String errMsg = (String) response.get("errMsg");
                    log.warn("RocketMQ Console API error: path={}, status={}, errMsg={}", path, status, errMsg);
                }
            }

            return response;

        } catch (Exception e) {
            log.error("RocketMQ Console API call failed: path={}, error={}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 检查 Console 是否可用
     *
     * @return true 表示 Console 可访问
     */
    public boolean isConsoleAvailable() {
        try {
            Map<String, Object> response = queryApi("/topic/list.query");
            return response != null && response.get("status") != null
                    && ((Integer) response.get("status")) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 模拟 Prometheus 格式的指标输出
     *
     * 用于兼容现有的 PrometheusClient 接口
     *
     * @return Prometheus 格式的指标 Map
     */
    public Map<String, Object> queryMetricsForPrometheusFormat() {
        Map<String, Object> metrics = queryClusterOverview();

        // 转换为 Prometheus 格式的指标名称
        Map<String, Object> prometheusFormat = new LinkedHashMap<>();

        // Topic 数量
        prometheusFormat.put("rocketmq_topic_count", metrics.getOrDefault("topicCount", 0));

        // 消费组数量
        prometheusFormat.put("rocketmq_consumer_group_count", metrics.getOrDefault("consumerGroupCount", 0));

        // 消息堆积（使用 Prometheus 的指标名称）
        prometheusFormat.put("rocketmq_group_diff", metrics.getOrDefault("totalMessageLag", 0));
        prometheusFormat.put("rocketmq_message_accumulation", metrics.getOrDefault("totalMessageLag", 0));

        // 最大堆积
        prometheusFormat.put("rocketmq_max_message_lag", metrics.getOrDefault("maxMessageLag", 0));

        // 数据来源标记
        prometheusFormat.put("_dataSource", "rocketmq-console");
        prometheusFormat.put("_consoleUrl", consoleUrl);

        return prometheusFormat;
    }
}