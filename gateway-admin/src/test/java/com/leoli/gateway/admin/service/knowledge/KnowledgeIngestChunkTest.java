package com.leoli.gateway.admin.service.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeIngestService 分块逻辑测试（纯函数，不触 DB / 嵌入）。
 */
class KnowledgeIngestChunkTest {

    private KnowledgeIngestService service;

    @BeforeEach
    void setUp() {
        // 依赖对 chunk() 无用，传 null；只设置分块参数
        service = new KnowledgeIngestService(null, null, null);
        ReflectionTestUtils.setField(service, "chunkSize", 200);
        ReflectionTestUtils.setField(service, "chunkOverlap", 50);
    }

    @Test
    @DisplayName("短段落合并进一个分块")
    void mergesSmallParagraphs() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = service.chunk(text);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第三段"));
    }

    @Test
    @DisplayName("超长段落被滑窗切成多块且不超过 chunkSize")
    void splitsLongParagraph() {
        String longPara = "a".repeat(500);
        List<String> chunks = service.chunk(longPara);
        assertTrue(chunks.size() >= 3, "500 字按 200/重叠50 应切成多块，实际 " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= 200, "每块不应超过 chunkSize");
        }
    }

    @Test
    @DisplayName("空/空白输入返回空列表，无空块")
    void handlesBlank() {
        assertTrue(service.chunk(null).isEmpty());
        assertTrue(service.chunk("   \n\n  ").isEmpty());
        List<String> chunks = service.chunk("有效段落\n\n\n\n   \n\n另一个有效段落");
        assertTrue(chunks.stream().noneMatch(String::isBlank), "不应产生空白块");
    }

    @Test
    @DisplayName("sha256 稳定且区分内容")
    void hashStableAndDistinct() {
        assertEquals(KnowledgeIngestService.sha256("abc"), KnowledgeIngestService.sha256("abc"));
        assertTrue(!KnowledgeIngestService.sha256("abc").equals(KnowledgeIngestService.sha256("abd")));
        assertEquals(64, KnowledgeIngestService.sha256("x").length());
    }
}
