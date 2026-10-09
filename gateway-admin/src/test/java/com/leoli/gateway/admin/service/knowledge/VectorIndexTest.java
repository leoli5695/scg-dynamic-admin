package com.leoli.gateway.admin.service.knowledge;

import com.leoli.gateway.admin.entity.KnowledgeChunkEntity;
import com.leoli.gateway.admin.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VectorIndex 暴力余弦检索测试：最近邻排序、类型过滤、维度不一致跳过、空索引安全。
 */
class VectorIndexTest {

    private KnowledgeChunkEntity chunk(long id, String content, String type, float[] vec) {
        KnowledgeChunkEntity e = new KnowledgeChunkEntity();
        e.setId(id);
        e.setContent(content);
        e.setSourceType(type);
        e.setTitle("t" + id);
        e.setEmbedding(VectorCodec.toBytes(vec));
        e.setDim(vec.length);
        e.setEnabled(true);
        return e;
    }

    private VectorIndex indexWith(KnowledgeChunkEntity... rows) {
        KnowledgeChunkRepository repo = mock(KnowledgeChunkRepository.class);
        when(repo.findByEnabledTrue()).thenReturn(List.of(rows));
        VectorIndex idx = new VectorIndex(repo);
        idx.reload();
        return idx;
    }

    @Test
    @DisplayName("按余弦相似度降序返回，最近邻在前")
    void nearestFirst() {
        VectorIndex idx = indexWith(
                chunk(1, "东", "doc", new float[]{1, 0}),
                chunk(2, "北", "doc", new float[]{0, 1}),
                chunk(3, "东北", "doc", new float[]{1, 1}));

        List<VectorIndex.Hit> hits = idx.search(new float[]{1, 0}, 3, null);
        assertEquals(3, hits.size());
        assertEquals(1, hits.get(0).getId(), "与 [1,0] 最相近的是 chunk1");
        assertTrue(hits.get(0).getScore() >= hits.get(1).getScore());
        assertEquals(2, hits.get(hits.size() - 1).getId(), "正交的 chunk2 分最低");
    }

    @Test
    @DisplayName("topK 截断")
    void respectsTopK() {
        VectorIndex idx = indexWith(
                chunk(1, "a", "doc", new float[]{1, 0}),
                chunk(2, "b", "doc", new float[]{0, 1}));
        assertEquals(1, idx.search(new float[]{1, 0}, 1, null).size());
    }

    @Test
    @DisplayName("按 sourceType 过滤")
    void filtersByType() {
        VectorIndex idx = indexWith(
                chunk(1, "文档", "doc", new float[]{1, 0}),
                chunk(2, "手册", "runbook", new float[]{1, 0}));
        List<VectorIndex.Hit> hits = idx.search(new float[]{1, 0}, 10, "runbook");
        assertEquals(1, hits.size());
        assertEquals(2, hits.get(0).getId());
        assertEquals("runbook", hits.get(0).getSourceType());
    }

    @Test
    @DisplayName("维度不一致的分块被跳过，查询维度不符返回空")
    void dimensionMismatch() {
        VectorIndex idx = indexWith(
                chunk(1, "a", "doc", new float[]{1, 0}),
                chunk(2, "b", "doc", new float[]{1, 0, 0})); // 维度 3，应被跳过
        assertEquals(1, idx.size());
        assertEquals(2, idx.dim());
        assertTrue(idx.search(new float[]{1, 0, 0}, 5, null).isEmpty(), "查询维度不符应返回空");
    }

    @Test
    @DisplayName("空索引检索安全返回空")
    void emptyIndex() {
        VectorIndex idx = indexWith();
        assertEquals(0, idx.size());
        assertTrue(idx.search(new float[]{1, 0}, 5, null).isEmpty());
    }
}
