package com.leoli.gateway.admin.service.copilot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================================
 * 证据评估器
 * ============================================================================
 * <p>
 * 取代原先按"调了几个工具"硬凑 0-100% 置信度的做法——那种百分比与答案对错无关，
 * 是假精确，反而误导用户。这里只如实陈述<b>回答背后有没有实时数据佐证</b>：
 * <ul>
 *   <li>{@link Level#DATA_BACKED}：调用了工具且全部成功，结论有实时数据支撑</li>
 *   <li>{@link Level#PARTIAL}：调用了工具但有失败，结论可能不完整</li>
 *   <li>{@link Level#NO_DATA}：未查询任何实时数据，属通用分析</li>
 * </ul>
 *
 * @author leoli
 */
@Slf4j
@Component
public class EvidenceAssessor {

    /** 证据等级。 */
    public enum Level {
        DATA_BACKED, PARTIAL, NO_DATA
    }

    /** 一次回答的证据评估结果。 */
    public static class Evidence {
        private final Level level;
        private final String note;
        private final int toolCount;
        private final Set<String> dataTypes;
        private final Set<String> failedTools;

        public Evidence(Level level, String note, int toolCount, Set<String> dataTypes, Set<String> failedTools) {
            this.level = level;
            this.note = note;
            this.toolCount = toolCount;
            this.dataTypes = dataTypes;
            this.failedTools = failedTools;
        }

        public Level getLevel() { return level; }
        public String getNote() { return note; }
        public int getToolCount() { return toolCount; }
        public Set<String> getDataTypes() { return dataTypes; }
        public Set<String> getFailedTools() { return failedTools; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level.name());
            m.put("note", note);
            m.put("toolCount", toolCount);
            m.put("dataTypes", dataTypes);
            m.put("failedTools", failedTools);
            return m;
        }
    }

    /**
     * 评估一次回答的证据强度。
     *
     * @param calledTools 调用过的工具名（去重）
     * @param dataTypes   命中的数据类型（route/service/metrics/...）
     * @param failedTools 执行失败的工具名（去重）
     * @param language    zh / en
     */
    public Evidence assess(Set<String> calledTools, Set<String> dataTypes, Set<String> failedTools, String language) {
        Set<String> called = calledTools != null ? calledTools : Set.of();
        Set<String> types = dataTypes != null ? dataTypes : Set.of();
        Set<String> failed = failedTools != null ? failedTools : Set.of();
        boolean zh = !"en".equalsIgnoreCase(language);

        Level level;
        String note;
        if (called.isEmpty()) {
            level = Level.NO_DATA;
            note = zh
                    ? "⚠️ 未查询实时数据：以下为基于通用知识的分析，请结合你的实际网关配置核对。"
                    : "⚠️ Not grounded in live data: this is a general analysis — verify against your actual gateway config.";
        } else if (!failed.isEmpty()) {
            level = Level.PARTIAL;
            String typePart = types.isEmpty() ? "" : (zh ? "，覆盖 " + String.join("/", types) : ", covering " + String.join("/", types));
            note = zh
                    ? String.format("🔎 部分依据实时数据：调用了 %d 个工具%s，其中 %d 个失败（%s），结论可能不完整。",
                            called.size(), typePart, failed.size(), String.join(", ", failed))
                    : String.format("🔎 Partially grounded: %d tool(s) called%s, %d failed (%s) — the answer may be incomplete.",
                            called.size(), typePart, failed.size(), String.join(", ", failed));
        } else {
            level = Level.DATA_BACKED;
            String typePart = types.isEmpty() ? "" : (zh ? "，覆盖 " + String.join("/", types) : ", covering " + String.join("/", types));
            note = zh
                    ? String.format("🔎 依据实时数据：调用了 %d 个工具%s，均成功返回。", called.size(), typePart)
                    : String.format("🔎 Grounded in live data: %d tool(s) called%s, all succeeded.", called.size(), typePart);
        }

        return new Evidence(level, note, called.size(), new LinkedHashSet<>(types), new LinkedHashSet<>(failed));
    }

    /**
     * 根据工具名推断其提供的数据类型（route/service/metrics/trace/middleware...）。
     *
     * @param toolName  工具名
     * @param dataTypes 数据类型集合（会被修改）
     */
    public void recordDataType(String toolName, Set<String> dataTypes) {
        if (toolName == null || dataTypes == null) {
            return;
        }
        if (toolName.contains("route") || toolName.equals("get_routes")) {
            dataTypes.add("route");
        }
        if (toolName.contains("service") || toolName.equals("get_services")) {
            dataTypes.add("service");
        }
        if (toolName.contains("metrics") || toolName.contains("monitor") || toolName.contains("prometheus")) {
            dataTypes.add("metrics");
        }
        if (toolName.contains("diagnostic") || toolName.contains("health") || toolName.contains("diagnose")) {
            dataTypes.add("diagnostic");
        }
        if (toolName.contains("log") || toolName.contains("access_log")) {
            dataTypes.add("log");
        }
        if (toolName.contains("filter") || toolName.contains("filter_chain")) {
            dataTypes.add("filter");
        }
        if (toolName.contains("instance") || toolName.contains("gateway_instance")) {
            dataTypes.add("instance");
        }
        if (toolName.contains("trace")) {
            dataTypes.add("trace");
        }
        if (toolName.contains("middleware") || toolName.contains("redis") || toolName.contains("mysql")
                || toolName.contains("rocketmq") || toolName.contains("rabbit") || toolName.contains("mongo")
                || toolName.contains("postgre") || toolName.contains("_es_")) {
            dataTypes.add("middleware");
        }
        if (toolName.contains("knowledge")) {
            dataTypes.add("knowledge");
        }
        log.debug("Recorded data type from tool: {} -> {}", toolName, dataTypes);
    }
}
