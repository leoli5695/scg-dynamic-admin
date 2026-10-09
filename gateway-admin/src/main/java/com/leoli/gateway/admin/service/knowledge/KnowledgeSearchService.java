package com.leoli.gateway.admin.service.knowledge;

import com.leoli.gateway.admin.entity.KnowledgeChunkEntity;
import com.leoli.gateway.admin.repository.KnowledgeChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * 知识库检索服务（RAG 的检索侧）
 * ============================================================================
 * <p>
 * {@code search_knowledge} 工具的实现：把查询嵌入成向量 → 在内存索引里暴力算余弦
 * 取 top-k → 返回带出处与分数的片段，供模型按需引用。
 * <p>
 * 兜底（符合"LLM 接入须兜底"）：embedding 服务不可用时降级到 MySQL LIKE 关键词检索；
 * 知识库为空/关闭时返回明确的空结果提示，让模型转通用回答而不是卡住。
 *
 * @author leoli
 */
@Slf4j
@Service
public class KnowledgeSearchService {

    private final EmbeddingClient embeddingClient;
    private final VectorIndex vectorIndex;
    private final KnowledgeChunkRepository repository;

    @Value("${gateway.copilot.rag.enabled:true}")
    private boolean enabled;

    @Value("${gateway.copilot.rag.default-top-k:4}")
    private int defaultTopK;

    /** 语义检索的最低相似度阈值，低于此分数的片段视为不相关，不返回。 */
    @Value("${gateway.copilot.rag.min-score:0.25}")
    private double minScore;

    /** 单个片段返回给模型的最大字符数（控制注入上下文的 token）。 */
    @Value("${gateway.copilot.rag.max-chunk-chars:1200}")
    private int maxChunkChars;

    public KnowledgeSearchService(EmbeddingClient embeddingClient,
                                  VectorIndex vectorIndex,
                                  KnowledgeChunkRepository repository) {
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
        this.repository = repository;
    }

    /**
     * 检索知识库，返回可直接作为工具结果的 Map。
     *
     * @param query      查询文本
     * @param topK       返回条数（可选，默认取配置，上限 10）
     * @param sourceType 来源类型过滤（可选）
     */
    public Map<String, Object> search(String query, Integer topK, String sourceType) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) {
            out.put("mode", "disabled");
            out.put("count", 0);
            out.put("results", List.of());
            out.put("hint", "知识库功能未启用，请基于通用知识回答，并说明未引用内部资料。");
            return out;
        }
        if (query == null || query.isBlank()) {
            out.put("mode", "empty-query");
            out.put("count", 0);
            out.put("results", List.of());
            return out;
        }
        int k = (topK != null && topK > 0) ? Math.min(topK, 10) : defaultTopK;

        if (vectorIndex.size() == 0) {
            out.put("mode", "empty-index");
            out.put("count", 0);
            out.put("results", List.of());
            out.put("hint", "知识库暂无内容，请基于通用知识回答，并说明未引用内部资料。");
            return out;
        }

        boolean semantic = true;
        List<VectorIndex.Hit> hits;
        try {
            float[] qv = embeddingClient.embed(query);
            hits = vectorIndex.search(qv, k, sourceType);
        } catch (Exception e) {
            log.warn("Knowledge embedding failed, falling back to keyword LIKE: {}", e.getMessage());
            semantic = false;
            hits = keywordFallback(query, k, sourceType);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (VectorIndex.Hit h : hits) {
            if (semantic && h.getScore() < minScore) {
                continue; // 相似度太低视为不相关
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", h.getTitle());
            item.put("sourceType", h.getSourceType());
            if (semantic) {
                item.put("score", Math.round(h.getScore() * 1000.0) / 1000.0);
            }
            item.put("content", truncate(h.getContent(), maxChunkChars));
            results.add(item);
        }

        out.put("mode", semantic ? "semantic" : "keyword-fallback");
        out.put("count", results.size());
        out.put("results", results);
        if (results.isEmpty()) {
            out.put("hint", "知识库无匹配内容，请基于通用知识回答，并说明未引用内部资料。");
        }
        return out;
    }

    /** embedding 不可用时的关键词兜底：取查询里最长的词做 LIKE。 */
    private List<VectorIndex.Hit> keywordFallback(String query, int k, String sourceType) {
        String kw = longestToken(query);
        if (kw.isEmpty()) {
            return List.of();
        }
        List<VectorIndex.Hit> hits = new ArrayList<>();
        for (KnowledgeChunkEntity e : repository.findTop8ByEnabledTrueAndContentContainingIgnoreCase(kw)) {
            if (sourceType != null && !sourceType.isEmpty() && !sourceType.equals(e.getSourceType())) {
                continue;
            }
            hits.add(new VectorIndex.Hit(e.getId(), 0.0,
                    e.getTitle() != null ? e.getTitle() : "", e.getContent(),
                    e.getSourceType() != null ? e.getSourceType() : ""));
            if (hits.size() >= k) {
                break;
            }
        }
        return hits;
    }

    private static String longestToken(String query) {
        String best = "";
        for (String tok : query.trim().split("[\\s,，。？?！!、;；:：]+")) {
            if (tok.length() >= 2 && tok.length() > best.length()) {
                best = tok;
            }
        }
        return best;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
