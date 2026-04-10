package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.AiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {

    Optional<AiConfig> findByProvider(String provider);

    List<AiConfig> findByRegion(String region);

    List<AiConfig> findByIsValidTrue();

    void deleteByProvider(String provider);

    /**
     * Find first valid AI config with non-null API key.
     * More efficient than findAll() when we only need one valid config.
     * Uses native query because JPQL doesn't support LIMIT.
     */
    @Query(value = "SELECT * FROM ai_config WHERE is_valid = true AND api_key IS NOT NULL AND api_key != '' LIMIT 1", nativeQuery = true)
    Optional<AiConfig> findFirstValidConfig();
}