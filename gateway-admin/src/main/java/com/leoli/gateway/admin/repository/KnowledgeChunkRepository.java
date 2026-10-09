package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.KnowledgeChunkEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库分块仓储。
 *
 * @author leoli
 */
@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {

    /** 载入全部启用分块（供内存向量索引）。 */
    List<KnowledgeChunkEntity> findByEnabledTrue();

    /** 按内容哈希查找，用于摄取去重 / 增量刷新。 */
    Optional<KnowledgeChunkEntity> findByContentHash(String contentHash);

    long countByEnabledTrue();

    long countBySourceType(String sourceType);

    /** 管理列表（按更新时间倒序分页）。 */
    Page<KnowledgeChunkEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Page<KnowledgeChunkEntity> findBySourceTypeOrderByUpdatedAtDesc(String sourceType, Pageable pageable);

    /**
     * embedding 不可用时的关键词兜底检索（MySQL LIKE）。
     */
    List<KnowledgeChunkEntity> findTop8ByEnabledTrueAndContentContainingIgnoreCase(String keyword);
}
