package com.leoli.gateway.admin.service.knowledge;

import com.leoli.gateway.admin.entity.KnowledgeChunkEntity;
import com.leoli.gateway.admin.repository.KnowledgeChunkRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ============================================================================
 * 内存向量索引（暴力余弦检索）
 * ============================================================================
 * <p>
 * 小语料场景不引入向量数据库：启动/定时/入库后把所有启用的分块 embedding 从 MySQL
 * 全量载入内存成 {@code float[][]}（已 L2 归一化），查询时对全量做点积（=余弦）取 top-k。
 * 几千分块量级下每次查询是个位数毫秒、内存几十 MB。
 * <p>
 * 线程安全：索引封装成<b>不可变快照</b>，重载时构造新快照再原子替换 volatile 引用；
 * 检索只读快照，无锁。DB 是唯一真相源，多副本各自定时重载（秒级一致性窗口）。
 *
 * @author leoli
 */
@Slf4j
@Component
public class VectorIndex {

    private final KnowledgeChunkRepository repository;

    /** 当前索引快照；volatile 保证换引用对检索线程立即可见。 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** 定时重载间隔（多副本下感知彼此的入库）。 */
    static final long RELOAD_INTERVAL_MS = 60_000L;

    public VectorIndex(KnowledgeChunkRepository repository) {
        this.repository = repository;
    }

    /** 不可变快照：向量已归一化，平行数组存元数据。 */
    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(new float[0][], new long[0], new String[0], new String[0], new String[0], 0);
        final float[][] vectors;
        final long[] ids;
        final String[] contents;
        final String[] titles;
        final String[] sourceTypes;
        final int dim;

        Snapshot(float[][] vectors, long[] ids, String[] contents, String[] titles, String[] sourceTypes, int dim) {
            this.vectors = vectors;
            this.ids = ids;
            this.contents = contents;
            this.titles = titles;
            this.sourceTypes = sourceTypes;
            this.dim = dim;
        }

        int size() {
            return vectors.length;
        }
    }

    /** 一次检索命中。 */
    public static class Hit {
        private final long id;
        private final double score;
        private final String title;
        private final String content;
        private final String sourceType;

        public Hit(long id, double score, String title, String content, String sourceType) {
            this.id = id;
            this.score = score;
            this.title = title;
            this.content = content;
            this.sourceType = sourceType;
        }

        public long getId() { return id; }
        public double getScore() { return score; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getSourceType() { return sourceType; }
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            // 启动时 DB 可能还没就绪；失败不阻断应用，靠定时重载兜底
            log.warn("VectorIndex initial load failed (will retry on schedule): {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    public void scheduledReload() {
        try {
            reload();
        } catch (Exception e) {
            log.debug("VectorIndex scheduled reload failed: {}", e.getMessage());
        }
    }

    /** 从 DB 全量重建快照并原子替换。入库后可主动调用以立即生效（单实例）。 */
    public synchronized void reload() {
        List<KnowledgeChunkEntity> rows = repository.findByEnabledTrue();
        // 以第一条有效向量的维度为准，维度不一致的跳过（换模型后需重建）
        int dim = 0;
        for (KnowledgeChunkEntity r : rows) {
            if (r.getEmbedding() != null && r.getEmbedding().length >= Float.BYTES) {
                dim = r.getEmbedding().length / Float.BYTES;
                break;
            }
        }
        if (dim == 0) {
            this.snapshot = Snapshot.EMPTY;
            log.info("VectorIndex reloaded: empty (no embedded chunks)");
            return;
        }

        List<float[]> vecs = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> types = new ArrayList<>();
        int skipped = 0;
        for (KnowledgeChunkEntity r : rows) {
            float[] v = VectorCodec.toFloats(r.getEmbedding());
            if (v.length != dim) {
                skipped++;
                continue;
            }
            normalize(v);
            vecs.add(v);
            ids.add(r.getId());
            contents.add(r.getContent());
            titles.add(r.getTitle() != null ? r.getTitle() : "");
            types.add(r.getSourceType() != null ? r.getSourceType() : "");
        }

        int n = vecs.size();
        float[][] matrix = vecs.toArray(new float[0][]);
        long[] idArr = new long[n];
        for (int i = 0; i < n; i++) idArr[i] = ids.get(i);
        Snapshot next = new Snapshot(matrix, idArr,
                contents.toArray(new String[0]), titles.toArray(new String[0]),
                types.toArray(new String[0]), dim);
        this.snapshot = next;
        log.info("VectorIndex reloaded: {} chunks (dim={}, skipped={})", n, dim, skipped);
    }

    /**
     * 余弦相似度检索（query 与库内向量都归一化后，cosine=点积）。
     *
     * @param queryVector 查询向量（无需预归一化）
     * @param topK        返回条数上限
     * @param typeFilter  来源类型过滤，null 表示不过滤
     * @return 按分数降序的命中
     */
    public List<Hit> search(float[] queryVector, int topK, String typeFilter) {
        Snapshot s = this.snapshot; // 读一次，保证整轮检索用同一快照
        if (s.size() == 0 || queryVector == null || queryVector.length != s.dim || topK <= 0) {
            return List.of();
        }
        float[] q = queryVector.clone();
        normalize(q);
        if (q.length == 0) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < s.vectors.length; i++) {
            if (typeFilter != null && !typeFilter.isEmpty() && !typeFilter.equals(s.sourceTypes[i])) {
                continue;
            }
            float[] v = s.vectors[i];
            float dot = 0f;
            for (int d = 0; d < v.length; d++) {
                dot += q[d] * v[d];
            }
            hits.add(new Hit(s.ids[i], dot, s.titles[i], s.contents[i], s.sourceTypes[i]));
        }
        hits.sort(Comparator.comparingDouble(Hit::getScore).reversed());
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    /** 当前索引的分块数（监控/测试用）。 */
    public int size() {
        return snapshot.size();
    }

    /** 当前索引维度；空索引返回 0。 */
    public int dim() {
        return snapshot.dim;
    }

    private static void normalize(float[] v) {
        double sum = 0.0;
        for (float f : v) sum += (double) f * f;
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        }
    }
}
