package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.StressTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StressTestRepository extends JpaRepository<StressTest, Long> {

    List<StressTest> findByInstanceId(String instanceId);

    List<StressTest> findByInstanceIdOrderByCreatedAtDesc(String instanceId);

    List<StressTest> findByStatus(String status);

    List<StressTest> findByInstanceIdAndStatus(String instanceId, String status);

    List<StressTest> findAllByOrderByCreatedAtDesc();

    /**
     * Delete all stress tests by instance ID.
     */
    @Modifying
    @Query("DELETE FROM StressTest s WHERE s.instanceId = :instanceId")
    int deleteByInstanceId(@Param("instanceId") String instanceId);
}