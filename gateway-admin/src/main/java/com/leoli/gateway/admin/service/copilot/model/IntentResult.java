package com.leoli.gateway.admin.service.copilot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 意图检测结果
 * ============================================================================
 * <p>
 * 封装用户消息意图检测的结果，包含:
 * - 检测到的意图类型
 * - 意图置信度分数
 * - 是否高置信度
 * - 是否需要 AI 进一步提炼
 * - 本地兜底回复（如适用）
 * <p>
 * 意图类型:
 * - greeting: 问候
 * - thanks: 感谢
 * - config_query: 配置查询
 * - route_create: 路由创建
 * - error_analysis: 错误分析
 * - optimization: 性能优化
 * - unknown: 未知意图
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntentResult {

    /**
     * 检测到的意图类型
     */
    private String intent;

    /**
     * 意图置信度分数 (0-100)
     */
    private int score;

    /**
     * 是否高置信度（>=80分）
     */
    private boolean isHighConfidence;

    /**
     * 是否需要 AI 进一步提炼意图
     * 低置信度或复杂意图需要 AI 辅助
     */
    private boolean needsAiRefinement;

    /**
     * 本地兜底回复（如适用）
     * 用于常见问题的快速响应
     */
    private String localFallback;

    /**
     * 用户消息语言
     */
    private String language;

    /**
     * 判断是否有本地兜底回复
     */
    public boolean hasLocalFallback() {
        return localFallback != null && !localFallback.isEmpty();
    }

    /**
     * 判断意图是否明确
     */
    public boolean isIntentClear() {
        return intent != null && !intent.isEmpty() && !"unknown".equals(intent);
    }

    /**
     * 创建未知意图结果
     */
    public static IntentResult unknown(String language) {
        return new IntentResult("unknown", 0, false, true, null, language);
    }

    /**
     * 创建高置信度意图结果
     */
    public static IntentResult highConfidence(String intent, String language) {
        return new IntentResult(intent, 85, true, false, null, language);
    }

    /**
     * 创建带本地兜底的意图结果
     */
    public static IntentResult withFallback(String intent, String fallback, String language) {
        return new IntentResult(intent, 100, true, false, fallback, language);
    }
}