package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.CopilotChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AI Copilot 对话消息仓储。
 *
 * @author leoli
 */
@Repository
public interface CopilotChatMessageRepository extends JpaRepository<CopilotChatMessageEntity, Long> {

    /** 载入某会话最近 N 条（按 id 倒序取，调用方再反转为时间正序）——用于内存缓存回填。 */
    List<CopilotChatMessageEntity> findTop20BySessionIdOrderByIdDesc(String sessionId);

    /** 某会话全部消息（时间正序）——用于前端回看历史。 */
    List<CopilotChatMessageEntity> findBySessionIdOrderByIdAsc(String sessionId);

    /** 清除某会话全部消息。 */
    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);
}
