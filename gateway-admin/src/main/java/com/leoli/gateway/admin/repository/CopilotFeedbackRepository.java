package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.CopilotFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AI Copilot 反馈仓储。
 *
 * @author leoli
 */
@Repository
public interface CopilotFeedbackRepository extends JpaRepository<CopilotFeedbackEntity, Long> {

    /** 某会话的反馈（按时间倒序）。 */
    List<CopilotFeedbackEntity> findBySessionIdOrderByIdDesc(String sessionId);

    /** 按评价统计数量（UP/DOWN），用于概览回答质量。 */
    long countByRating(String rating);
}
