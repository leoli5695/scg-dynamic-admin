package com.leoli.gateway.admin.service.knowledge;

import com.leoli.gateway.admin.entity.KnowledgeChunkEntity;
import com.leoli.gateway.admin.repository.KnowledgeChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * 知识库摄取服务（RAG）
 * ============================================================================
 * <p>
 * 文档 → 分块 → 批量嵌入 → 按 content_hash 去重 upsert 入库 → 刷新内存索引。
 * 内容哈希保证重复摄取不产生脏数据、内容未变则不重复嵌入（省钱）。
 * <p>
 * 语料来源：管理员上传的 runbook/文档，以及反馈闭环里被 👍 的问答对（越用越准）。
 *
 * @author leoli
 */
@Slf4j
@Service
public class KnowledgeIngestService {

    private final KnowledgeChunkRepository repository;
    private final EmbeddingClient embeddingClient;
    private final VectorIndex vectorIndex;

    /** 分块目标大小（字符）。 */
    @Value("${gateway.copilot.rag.chunk-size:800}")
    private int chunkSize;

    /** 相邻分块重叠（字符），避免语义在边界被切断。 */
    @Value("${gateway.copilot.rag.chunk-overlap:120}")
    private int chunkOverlap;

    public KnowledgeIngestService(KnowledgeChunkRepository repository,
                                  EmbeddingClient embeddingClient,
                                  VectorIndex vectorIndex) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
    }

    /**
     * 摄取一篇文档：分块 + 嵌入 + 去重入库 + 刷新索引。
     *
     * @return 实际新增的分块数（内容未变的会被跳过）
     */
    public int ingestDocument(String sourceType, String sourceRef, String title, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        List<String> chunks = chunk(text);
        List<KnowledgeChunkEntity> toEmbed = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            String hash = sha256(sourceType + "|" + sourceRef + "|" + i + "|" + c);
            if (repository.findByContentHash(hash).isPresent()) {
                continue; // 内容未变，跳过（不重复嵌入）
            }
            KnowledgeChunkEntity e = new KnowledgeChunkEntity();
            e.setSourceType(sourceType != null ? sourceType : "doc");
            e.setSourceRef(sourceRef);
            e.setTitle(title != null && !title.isBlank() ? truncate(title, 200) : truncate(c, 60));
            e.setContent(c);
            e.setContentHash(hash);
            e.setEnabled(true);
            toEmbed.add(e);
        }
        if (toEmbed.isEmpty()) {
            log.info("Ingest skipped (all {} chunks already present) for source {}", chunks.size(), sourceRef);
            return 0;
        }

        List<String> texts = toEmbed.stream().map(KnowledgeChunkEntity::getContent).toList();
        List<float[]> vectors = embeddingClient.embedBatch(texts);
        if (vectors.size() != toEmbed.size()) {
            throw new IllegalStateException("嵌入返回数量(" + vectors.size() + ")与分块数(" + toEmbed.size() + ")不一致");
        }
        for (int i = 0; i < toEmbed.size(); i++) {
            KnowledgeChunkEntity e = toEmbed.get(i);
            float[] v = vectors.get(i);
            e.setEmbedding(VectorCodec.toBytes(v));
            e.setDim(v.length);
            e.setModel(embeddingClient.getModel());
            repository.save(e);
        }
        vectorIndex.reload();
        log.info("Ingested {} new chunks for source {} (type={})", toEmbed.size(), sourceRef, sourceType);
        return toEmbed.size();
    }

    /**
     * 反馈闭环：把被 👍 的问答对沉淀为一条知识（非致命，失败只记日志）。
     */
    public void ingestFeedback(String question, String answer, Long feedbackId) {
        try {
            if (question == null || answer == null || answer.isBlank()) {
                return;
            }
            String text = "问题：" + question.trim() + "\n\n优质回答：" + answer.trim();
            ingestDocument("feedback", "feedback-" + feedbackId, truncate(question.trim(), 60), text);
        } catch (Exception e) {
            log.warn("ingestFeedback failed (non-fatal): {}", e.getMessage());
        }
    }

    /** 仅重建内存索引（不重新嵌入），用于多副本感知或手工刷新。 */
    public int reindex() {
        vectorIndex.reload();
        return vectorIndex.size();
    }

    /**
     * 分块：先按空行切段，小段累积到接近 chunkSize 再切；超长段用带 overlap 的滑窗切。
     * 提为包级方法便于单测。
     */
    List<String> chunk(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        int size = Math.max(100, chunkSize);
        int overlap = Math.max(0, Math.min(chunkOverlap, size / 2));
        int step = Math.max(1, size - overlap);

        StringBuilder cur = new StringBuilder();
        for (String rawPara : text.split("\\n\\s*\\n")) {
            String para = rawPara.trim();
            if (para.isEmpty()) {
                continue;
            }
            if (para.length() > size) {
                if (cur.length() > 0) {
                    result.add(cur.toString().trim());
                    cur.setLength(0);
                }
                for (int start = 0; start < para.length(); start += step) {
                    int end = Math.min(para.length(), start + size);
                    String piece = para.substring(start, end).trim();
                    if (!piece.isEmpty()) {
                        result.add(piece);
                    }
                    if (end == para.length()) {
                        break;
                    }
                }
                continue;
            }
            if (cur.length() + para.length() + 2 > size && cur.length() > 0) {
                result.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(para).append("\n\n");
        }
        if (cur.length() > 0) {
            result.add(cur.toString().trim());
        }
        result.removeIf(s -> s == null || s.isBlank());
        return result;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
