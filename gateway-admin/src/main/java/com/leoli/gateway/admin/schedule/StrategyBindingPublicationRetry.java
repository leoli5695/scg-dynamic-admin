package com.leoli.gateway.admin.schedule;

import com.leoli.gateway.admin.repository.StrategyRepository;
import com.leoli.gateway.admin.service.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable intents live in strategy metadata; each recovery takes a fresh locked row. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyBindingPublicationRetry {
    private final StrategyRepository strategies;
    private final StrategyService service;

    @Scheduled(fixedDelayString = "${gateway.strategy.binding-publication-retry-ms:10000}")
    public void retry() {
        // A new process scans the same persisted intents, including when the old config still exists.
        for (var candidate : strategies.findByMetadataContaining("\"_bindingPublicationPending\"")) {
            if (!service.hasPendingBindingPublication(candidate)) continue;
            try { service.retryBindingPublication(candidate.getStrategyId()); }
            catch (Exception error) {
                log.warn("Binding publication remains pending for {}: {}", candidate.getStrategyId(), error.toString());
            }
        }
    }
}
