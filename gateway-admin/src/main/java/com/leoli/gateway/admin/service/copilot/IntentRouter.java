package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.prompt.PromptService;
import com.leoli.gateway.admin.service.copilot.model.IntentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ============================================================================
 * 意图检测路由器
 * ============================================================================
 * <p>
 * 检测用户消息意图并路由到合适的处理逻辑。
 * <p>
 * 功能:
 * - 语言检测（中文/英文）
 * - 本地兜底回复匹配（常见问题快速响应）
 * - AI 意图提炼（复杂问题使用轻量模型）
 * - 意图延续（多轮对话上下文记忆）
 * <p>
 * 意图类别:
 * - route（路由配置）
 * - service（服务配置）
 * - strategy（策略配置）
 * - auth（认证配置）
 * - monitor（监控诊断）
 * - instance（实例管理）
 * - alert（告警管理）
 * - debug（问题排查）
 * - performance（性能优化）
 * - general（一般问题）
 * <p>
 * 本地兜底常见问题:
 * - 问候类: "你好", "您好", "hi", "hello"
 * - 感谢类: "谢谢", "感谢", "thanks"
 * - 功能询问: "你是谁", "你能做什么"
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final PromptService promptService;

    // ===================== 本地兜底回复配置 =====================

    /**
     * 中文常见问题本地回复（避免调用AI，降低延迟和成本）
     */
    private static final Map<String, String> LOCAL_FALLBACKS_ZH = createLocalFallbacksZH();

    private static Map<String, String> createLocalFallbacksZH() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        // 问候类
        fallbacks.put("你好", "你好！我是网关 AI Copilot 智能助手。我可以帮你配置路由、排查故障、优化性能等。有什么问题随时问我！");
        fallbacks.put("您好", "您好！我是网关 AI Copilot。有什么我可以帮你的吗？");
        fallbacks.put("hi", "Hi！有什么问题吗？我可以帮你配置路由、排查问题等。");
        fallbacks.put("hello", "Hello！我是网关智能助手，随时为你提供帮助。");

        // 感谢类
        fallbacks.put("谢谢", "不客气！有问题随时问我。");
        fallbacks.put("感谢", "很高兴能帮到你！如有其他问题，随时找我。");
        fallbacks.put("thanks", "You're welcome! Feel free to ask anytime.");

        // 简单功能确认
        fallbacks.put("你是谁", "我是网关管理系统的 AI Copilot 智能助手，专注于帮助你配置和管理 Spring Cloud Gateway。");
        fallbacks.put("你能做什么", "我可以帮你：\n1. **配置路由**：生成路由配置、解释断言/过滤器\n2. **排查故障**：分析错误日志、诊断健康状态\n3. **优化性能**：调优建议、JVM参数、限流阈值\n4. **管理实例**：K8s部署、副本调整\n5. **监控告警**：指标解读、告警降噪\n\n有什么需要帮忙的？");
        fallbacks.put("你能帮我什么", "我可以帮你配置路由、排查故障、优化性能、管理网关实例等。具体问吧！");

        return fallbacks;
    }

    /**
     * 英文常见问题本地回复
     */
    private static final Map<String, String> LOCAL_FALLBACKS_EN = createLocalFallbacksEN();

    private static Map<String, String> createLocalFallbacksEN() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        // Greetings
        fallbacks.put("hi", "Hi! I'm the Gateway AI Copilot. I can help you configure routes, troubleshoot issues, optimize performance, etc. Feel free to ask!");
        fallbacks.put("hello", "Hello! I'm the Gateway AI Copilot assistant. What can I help you with?");
        fallbacks.put("hey", "Hey! Ready to help with your gateway configuration and troubleshooting.");

        // Thanks
        fallbacks.put("thanks", "You're welcome! Feel free to ask if you need more help.");
        fallbacks.put("thank you", "Happy to help! Let me know if you have other questions.");

        // Function queries
        fallbacks.put("who are you", "I'm the AI Copilot for the Gateway Management System, specialized in helping you configure and manage Spring Cloud Gateway.");
        fallbacks.put("what can you do", "I can help you:\n1. **Configure Routes**: Generate route configs, explain predicates/filters\n2. **Troubleshoot**: Analyze error logs, diagnose health status\n3. **Optimize Performance**: Tuning suggestions, JVM params, rate limits\n4. **Manage Instances**: K8s deployment, replica scaling\n5. **Monitor & Alert**: Metrics interpretation, alert noise reduction\n\nWhat do you need help with?");

        return fallbacks;
    }

    // ===================== 核心方法 =====================

    /**
     * 检测用户消息语言
     *
     * @param message 用户消息
     * @return 语言代码 ("zh" 或 "en")
     */
    public String detectLanguage(String message) {
        // 简单判断：如果包含中文字符，则为中文
        for (char c : message.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                return "zh";
            }
        }
        return "en";
    }

    /**
     * 检查本地兜底回复
     *
     * @param userMessage 用户消息
     * @param language    语言代码
     * @return 如果匹配返回回复内容，否则返回 null
     */
    public String checkLocalFallback(String userMessage, String language) {
        String lowerMessage = userMessage.toLowerCase().trim();

        Map<String, String> fallbacks = "zh".equals(language) ? LOCAL_FALLBACKS_ZH : LOCAL_FALLBACKS_EN;

        // 完全匹配检查
        if (fallbacks.containsKey(lowerMessage)) {
            return fallbacks.get(lowerMessage);
        }

        // 模糊匹配：检查是否以关键词开头
        for (Map.Entry<String, String> entry : fallbacks.entrySet()) {
            if (lowerMessage.startsWith(entry.getKey()) && lowerMessage.length() <= entry.getKey().length() + 5) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 处理意图识别流程
     *
     * @param sessionId   会话ID
     * @param userMessage 用户消息
     * @param lastIntent  上次意图（用于延续）
     * @return 意图检测结果
     */
    public IntentResult processIntent(String sessionId, String userMessage, String lastIntent) {
        String language = detectLanguage(userMessage);

        // 1. 检查本地兜底
        String localFallback = checkLocalFallback(userMessage, language);
        if (localFallback != null) {
            log.info("Local fallback matched: session={}, message={}", sessionId, userMessage);
            return IntentResult.withFallback("greeting", localFallback, language);
        }

        // 2. 使用 PromptService 检测意图
        PromptService.IntentResult result = promptService.detectIntent(userMessage);
        String detectedIntent = result.intent();
        int confidenceScore = result.score();

        // 3. 判断是否需要 AI 提炼
        boolean needsAiRefinement = confidenceScore < 80;

        // 4. 意图延续逻辑（如果新意图不明确，使用上次意图）
        if ((detectedIntent == null || "unknown".equals(detectedIntent)) && lastIntent != null) {
            log.info("Intent continuation: session={}, using lastIntent={}", sessionId, lastIntent);
            detectedIntent = lastIntent;
            confidenceScore = 60;  // 延续意图置信度较低
        }

        log.debug("Intent detected: session={}, intent={}, confidence={}, needsAiRefinement={}",
                sessionId, detectedIntent, confidenceScore, needsAiRefinement);

        return new IntentResult(detectedIntent, confidenceScore, confidenceScore >= 80,
                needsAiRefinement, null, language);
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取本地兜底配置
     *
     * @param language 语言代码
     * @return 本地兜底回复 Map
     */
    public Map<String, String> getLocalFallbacks(String language) {
        return "zh".equals(language) ? LOCAL_FALLBACKS_ZH : LOCAL_FALLBACKS_EN;
    }

    /**
     * 判断是否为常见问题（可使用本地兜底）
     *
     * @param userMessage 用户消息
     * @return 是否为常见问题
     */
    public boolean isCommonQuestion(String userMessage) {
        String language = detectLanguage(userMessage);
        return checkLocalFallback(userMessage, language) != null;
    }

    /**
     * 检查意图是否有效
     *
     * @param intent 意图类别
     * @return 是否为有效的意图类别
     */
    public boolean isValidIntent(String intent) {
        if (intent == null || intent.isEmpty()) {
            return false;
        }
        return promptService.isValidIntent(intent);
    }
}