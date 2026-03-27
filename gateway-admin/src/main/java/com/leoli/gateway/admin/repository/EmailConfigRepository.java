package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.EmailConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailConfigRepository extends JpaRepository<EmailConfig, Long> {

    /**
     * Find enabled email config
     */
    Optional<EmailConfig> findByEnabledTrue();

    /**
     * Find all enabled configs
     */
    List<EmailConfig> findByEnabled(Boolean enabled);

    /**
     * Find by config name
     */
    Optional<EmailConfig> findByConfigName(String configName);
}