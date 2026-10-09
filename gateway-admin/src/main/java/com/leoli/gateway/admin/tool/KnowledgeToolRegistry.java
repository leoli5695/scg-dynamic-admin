package com.leoli.gateway.admin.tool;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库（RAG）工具注册器。
 * <p>
 * 沿用 {@code MiddlewareTraceToolRegistry} 的模式，向 {@link ToolRegistry} 追加一个只读工具
 * {@code search_knowledge}，让模型在需要"内部知识/历史经验/文档依据"时按需检索，
 * 而不是把这些知识无脑塞进每条系统提示词（符合"上下文按需注入"）。
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeToolRegistry {

    private final ToolRegistry toolRegistry;

    @PostConstruct
    public void init() {
        toolRegistry.getTools().put("search_knowledge", ToolDefinition.create(
                "search_knowledge",
                "检索内部知识库（运维手册 runbook、历史故障复盘、产品/网关文档、被点赞的优质问答）。"
                        + "当答案需要团队内部约定、历史处理经验或文档依据，而实时系统状态类工具（路由/指标/链路等）查不到时，"
                        + "调用它获取可引用的知识片段。返回按相关度排序的片段（含标题、出处类型、相似度、正文）。"
                        + "若返回为空或 mode 非 semantic，请在回答中说明未引用内部资料。",
                Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "检索查询，用自然语言描述要查的知识点，如'熔断阈值怎么配'、'上次网关 OOM 怎么处理的'"
                        ),
                        "topK", Map.of(
                                "type", "integer",
                                "description", "返回片段数（默认 4，最多 10）",
                                "default", 4
                        ),
                        "sourceType", Map.of(
                                "type", "string",
                                "description", "按来源类型过滤（可选）",
                                "enum", List.of("runbook", "incident", "feedback", "doc")
                        )
                ),
                List.of("query"),
                "knowledge",
                true
        ));
        log.info("Registered knowledge tool: search_knowledge");
    }
}
