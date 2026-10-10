package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.ProviderCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for provider API credentials.
 *
 * @author leoli
 */
public interface ProviderCredentialRepository extends JpaRepository<ProviderCredentialEntity, Long> {

    Optional<ProviderCredentialEntity> findByCredentialRef(String credentialRef);

    boolean existsByCredentialRef(String credentialRef);

    List<ProviderCredentialEntity> findByProvider(String provider);

    List<ProviderCredentialEntity> findByEnabledTrue();
}
