package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * ============================================================================
 * 语义意图分类器（关键词快路的兜底）
 * ============================================================================
 * <p>
 * 原意图识别是纯关键词加权（{@code PromptService.detectIntent}），对没命中关键词或
 * 表述灵活的问题会退化成 general/unknown，导致下游只能 fail-safe 地把<b>全部工具</b>
 * 塞进上下文，白白烧 token。
 * <p>
 * 策略（与"关键词快路 + 语义兜底"一致）：
 * <ul>
 *   <li>关键词已高置信命中 → 不调用本分类器（零额外成本）；</li>
 *   <li>关键词弱命中/general/unknown → 发一次<b>极小</b>的分类调用，只让模型回一个领域标签；</li>
 *   <li>分类失败或返回无法识别的标签 → 返回 null，调用方 fail-safe 回退（不因此报错）。</li>
 * </ul>
 * 归类标签与 {@code ToolRegistry.getOpenAITools(domain)} 的领域关键词对齐，从而把工具
 * 收窄到相关子集。
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticIntentClassifier {

    private final AiAnalysisService aiAnalysisService;

    /** 可归类的领域标签（与 ToolRegistry 的领域关键词一致）。 */
    static final List<String> DOMAINS = List.of(
            "route", "service", "monitor", "debug", "performance",
            "trace", "middleware", "instance", "test", "audit");

    /**
     * 把用户问题语义归类到一个领域标签。
     *
     * @param provider    AI 提供商（已解析，非空）
     * @param model       模型名
     * @param userMessage 用户问题
     * @return 领域标签；无法确定或调用失败时返回 {@code general}（调用方据此 fail-safe 到全量工具）
     */
    public String classify(String provider, String model, String userMessage) {
        if (provider == null || provider.isEmpty() || userMessage == null || userMessage.isBlank()) {
            return "general";
        }
        String prompt = """
                你是网关智能助手的意图分类器。把用户问题归类到且仅到以下标签之一：
                route(路由配置/断言/过滤器/灰度), service(服务/注册发现/nacos), \
                monitor(健康检查/运行状态/指标/监控概览/告警), \
                debug(排障/故障定位/报错/为什么失败/异常/503/超时/不通), \
                performance(性能/优化/慢/JVM/GPU/CPU/内存), \
                trace(链路追踪/span/traceId), middleware(中间件/redis/mysql/mq/kafka/es/mongo), \
                instance(实例/pod/副本/扩缩容/集群/k8s), test(压测/压力测试/性能测试), \
                audit(审计/操作日志/回滚)。
                无法明确归类时输出 general。
                只输出一个标签单词，不要任何解释或标点。

                用户问题：%s
                """.formatted(userMessage.trim());
        try {
            String resp = aiAnalysisService.callAiApi(provider, model, prompt);
            String label = extractDomain(resp);
            log.debug("Semantic intent classify: '{}' -> {}", userMessage, label);
            return label;
        } catch (Exception e) {
            // 分类只是优化手段，失败不应影响主流程 → fail-safe 到 general（全量工具）
            log.debug("Semantic intent classification failed, falling back to general: {}", e.getMessage());
            return "general";
        }
    }

    /**
     * 从模型输出里提取一个已知领域标签；识别不到返回 general。
     */
    String extractDomain(String response) {
        if (response == null) {
            return "general";
        }
        String r = response.trim().toLowerCase(Locale.ROOT);
        // 优先精确等于某个标签
        for (String d : DOMAINS) {
            if (r.equals(d)) {
                return d;
            }
        }
        // 退化：输出里包含某标签词（模型可能多说了几个字）
        for (String d : DOMAINS) {
            if (r.contains(d)) {
                return d;
            }
        }
        return "general";
    }
}
