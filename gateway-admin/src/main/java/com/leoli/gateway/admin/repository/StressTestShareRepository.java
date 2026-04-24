package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.StressTestShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for Stress Test Share records.
 *
 * @author leoli
 */
@Repository
public interface StressTestShareRepository extends JpaRepository<StressTestShare, Long> {

    Optional<StressTestShare> findByShareId(String shareId);

    void deleteByShareId(String shareId);

    boolean existsByShareId(String shareId);
}