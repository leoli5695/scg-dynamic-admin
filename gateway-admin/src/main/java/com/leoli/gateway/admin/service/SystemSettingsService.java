package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.SystemSettingsEntity;
import com.leoli.gateway.admin.repository.SystemSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Persists the console-wide feature switches used by navigation and write guards. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingsRepository repository;

    @Transactional
    public SystemSettingsEntity get() {
        return repository.findById(SystemSettingsEntity.DEFAULT_ID)
                .orElseGet(() -> repository.save(new SystemSettingsEntity()));
    }

    @Transactional
    public SystemSettingsEntity update(Boolean stressTestEnabled, Boolean middlewareEnabled,
                                       Boolean pluginsEnabled, Boolean kubernetesEnabled, String operator) {
        SystemSettingsEntity settings = get();
        if (stressTestEnabled != null) settings.setStressTestEnabled(stressTestEnabled);
        if (middlewareEnabled != null) settings.setMiddlewareEnabled(middlewareEnabled);
        if (pluginsEnabled != null) settings.setPluginsEnabled(pluginsEnabled);
        if (kubernetesEnabled != null) settings.setKubernetesEnabled(kubernetesEnabled);
        settings.setUpdatedBy(operator);
        settings.setUpdatedAt(LocalDateTime.now());
        return repository.save(settings);
    }

    public boolean isStressTestEnabled() {
        return get().isStressTestEnabled();
    }

    public boolean isMiddlewareEnabled() {
        return get().isMiddlewareEnabled();
    }

    public boolean isPluginsEnabled() {
        return get().isPluginsEnabled();
    }

    public boolean isKubernetesEnabled() {
        return get().isKubernetesEnabled();
    }
}