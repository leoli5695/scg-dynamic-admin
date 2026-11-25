package com.leoli.gateway.admin.scheduler;

import com.leoli.gateway.admin.service.ProviderHealthProbeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the AI provider endpoint probe. Providers are stable, so the default
 * cadence is hourly; the first round is delayed after startup to avoid a probe
 * storm while the rest of the application is still initializing.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderHealthProbeScheduler {

    private final ProviderHealthProbeService probeService;

    @Value("${gateway.admin.provider-health.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${gateway.admin.provider-health.interval-ms:3600000}",
            initialDelayString = "${gateway.admin.provider-health.initial-delay-ms:60000}")
    public void probeProviders() {
        if (!enabled) {
            return;
        }
        try {
            probeService.probeAll();
        } catch (Exception e) {
            log.error("AI provider health probe round failed", e);
        }
    }
}