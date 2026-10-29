package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.SystemSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSettingsEntity, Long> {
}