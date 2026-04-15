package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.AccessLogConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Access Log Config Repository.
 *
 * @author leoli
 */
@Repository
public interface AccessLogConfigRepository extends JpaRepository<AccessLogConfigEntity, Long> {

    /**
     * Find config by instance ID.
     */
    Optional<AccessLogConfigEntity> findByInstanceId(String instanceId);

    /**
     * Check if config exists for instance.
     */
    boolean existsByInstanceId(String instanceId);
}