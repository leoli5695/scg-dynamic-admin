package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.AlertHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    Page<AlertHistory> findByOrderByCreatedAtDesc(Pageable pageable);

    Page<AlertHistory> findByAlertTypeOrderByCreatedAtDesc(String alertType, Pageable pageable);

    List<AlertHistory> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    List<AlertHistory> findByAlertTypeAndCreatedAtAfterOrderByCreatedAtDesc(String alertType, LocalDateTime since);
}