package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.TokenQuotaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Token Quota Repository.
 *
 * @author leoli
 */
@Repository
public interface TokenQuotaRepository extends JpaRepository<TokenQuotaEntity, Long> {

    /**
     * Find quota by tenant ID.
     */
    Optional<TokenQuotaEntity> findByTenantId(String tenantId);

    /**
     * Find all quotas for a specific instance.
     */
    List<TokenQuotaEntity> findByInstanceId(String instanceId);

    /**
     * Find all enabled quotas.
     */
    List<TokenQuotaEntity> findByEnabledTrue();

    /**
     * Check if quota exists for tenant.
     */
    boolean existsByTenantId(String tenantId);

    /**
     * Delete quota by tenant ID.
     */
    void deleteByTenantId(String tenantId);

    /**
     * Find quotas with usage approaching threshold.
     * This requires joining with Redis data, done in service layer.
     */
    @Query("SELECT q FROM TokenQuotaEntity q WHERE q.enabled = true AND q.alertThreshold > 0")
    List<TokenQuotaEntity> findAllEnabledWithAlertConfig();
}