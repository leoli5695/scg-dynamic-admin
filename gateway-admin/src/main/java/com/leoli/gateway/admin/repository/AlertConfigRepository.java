package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.AlertConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertConfigRepository extends JpaRepository<AlertConfig, Long> {

    Optional<AlertConfig> findByEnabledTrue();

    List<AlertConfig> findByEnabled(Boolean enabled);

    /**
     * Find alert config by instanceId
     */
    Optional<AlertConfig> findByInstanceId(String instanceId);

    /**
     * Find alert config by instanceId and enabled
     */
    Optional<AlertConfig> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Find all alert configs by instanceId
     */
    List<AlertConfig> findByInstanceIdIn(List<String> instanceIds);

    /**
     * Delete alert config by instance ID.
     */
    int deleteByInstanceId(String instanceId);
}