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
}