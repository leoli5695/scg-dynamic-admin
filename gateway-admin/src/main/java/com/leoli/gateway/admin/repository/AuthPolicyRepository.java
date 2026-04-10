package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.enums.AuthType;
import com.leoli.gateway.admin.model.AuthPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for AuthPolicyEntity.
 *
 * @author leoli
 */
@Repository
public interface AuthPolicyRepository extends JpaRepository<AuthPolicyEntity, Long> {

    /**
     * Find policy by policyId (UUID).
     */
    Optional<AuthPolicyEntity> findByPolicyId(String policyId);

    /**
     * Find all policies by instanceId.
     */
    List<AuthPolicyEntity> findByInstanceId(String instanceId);

    /**
     * Find all enabled policies by instanceId.
     */
    List<AuthPolicyEntity> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Find policy by policyId and instanceId.
     */
    Optional<AuthPolicyEntity> findByPolicyIdAndInstanceId(String policyId, String instanceId);

    /**
     * Find policy by policy name.
     */
    Optional<AuthPolicyEntity> findByPolicyName(String policyName);

    /**
     * Find policy by policy name and instanceId.
     */
    Optional<AuthPolicyEntity> findByPolicyNameAndInstanceId(String policyName, String instanceId);

    /**
     * Check if policy name exists.
     */
    boolean existsByPolicyName(String policyName);

    /**
     * Check if policy name exists within instanceId.
     */
    boolean existsByPolicyNameAndInstanceId(String policyName, String instanceId);

    /**
     * Find all policies by auth type.
     */
    List<AuthPolicyEntity> findByAuthType(AuthType authType);

    /**
     * Find all policies by auth type and instanceId.
     */
    List<AuthPolicyEntity> findByAuthTypeAndInstanceId(AuthType authType, String instanceId);

    /**
     * Find all policies by instanceId and auth type.
     */
    List<AuthPolicyEntity> findByInstanceIdAndAuthType(String instanceId, AuthType authType);

    /**
     * Find all enabled policies.
     */
    List<AuthPolicyEntity> findByEnabledTrue();

    /**
     * Find all policies by auth type and enabled status.
     */
    List<AuthPolicyEntity> findByAuthTypeAndEnabled(AuthType authType, Boolean enabled);

    /**
     * Delete policy by policyId.
     */
    void deleteByPolicyId(String policyId);

    /**
     * Delete all policies by instance ID.
     */
    int deleteByInstanceId(String instanceId);

    /**
     * Count policies by enabled status.
     */
    long countByEnabled(Boolean enabled);
}