package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.ssl.DynamicSslContextManager;
import com.leoli.gateway.ssl.SslServerConfig;
import com.leoli.gateway.util.ConfigContentTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * SSL certificate configuration refresher with per-certificate incremental refresh.
 * Listens to certificates index and individual certificate changes in Nacos.
 *
 * @author leoli
 */
@Slf4j
@Component
public class SslCertificateRefresher {

    private final ObjectMapper objectMapper;
    private final DynamicSslContextManager sslContextManager;
    private final ConfigCenterService configService;
    private final SslServerConfig sslServerConfig;

    // Currently listening certificate domains
    private final Set<String> listeningDomains = ConcurrentHashMap.newKeySet();
    // Certificate listeners cache: <domain, listener>
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> certListeners = new ConcurrentHashMap<>();
    // Last applied content per certificate data ID, so the periodic reconcile can spot
    // a renewed certificate instead of only noticing additions and deletions.
    private final ConfigContentTracker appliedContent = new ConfigContentTracker();

    // FIX: keep index listener as a field so it can be removed on destroy
    // (method references create a new instance per call and never match)
    private final ConfigCenterService.ConfigListener indexListener = this::onCertificatesIndexChanged;

    @Autowired
    public SslCertificateRefresher(DynamicSslContextManager sslContextManager,
                                   ConfigCenterService configService,
                                   SslServerConfig sslServerConfig,
                                   ObjectMapper objectMapper) {
        this.sslContextManager = sslContextManager;
        this.configService = configService;
        this.sslServerConfig = sslServerConfig;
        this.objectMapper = objectMapper;
        log.info("SslCertificateRefresher initialized with per-certificate incremental refresh");
    }

    /**
     * Initialize after bean construction
     */
    @PostConstruct
    public void init() {
        // 1. Listen to certificates index changes
        configService.addListener(SSL_CERTIFICATES_INDEX, GROUP, indexListener);
        log.info("✅ Registered listener for certificates index: {}", SSL_CERTIFICATES_INDEX);

        // 2. Load all certificates initially
        loadAllCertificates();

        // 3. Try to start HTTPS server if certificates are available
        if (!sslContextManager.getConfiguredDomains().isEmpty()) {
            sslServerConfig.startHttpsServer();
        }

        log.info("✅ SslCertificateRefresher initialization completed");
    }

    /**
     * Cleanup before bean destruction
     */
    @PreDestroy
    public void destroy() {
        // Remove all certificate listeners
        for (String domain : listeningDomains) {
            String certDataId = SSL_CERTIFICATE_PREFIX + domain;
            ConfigCenterService.ConfigListener listener = certListeners.get(domain);
            if (listener != null) {
                configService.removeListener(certDataId, GROUP, listener);
            }
        }

        // Remove index listener (using the stored field instance)
        configService.removeListener(SSL_CERTIFICATES_INDEX, GROUP, indexListener);

        log.info("SslCertificateRefresher destroyed, all listeners removed");
    }

    /**
     * Handle certificates index change event.
     */
    private void onCertificatesIndexChanged(String dataId, String group, String newIndexContent) {
        log.info("📋 Certificates index changed detected");

        try {
            List<String> newDomains = parseDomains(newIndexContent);
            Set<String> oldDomains = new HashSet<>(listeningDomains);

            // Convert List to Set for difference calculation
            Set<String> newDomainSet = new HashSet<>(newDomains);

            // Calculate differences
            Set<String> addedCerts = getDifference(newDomainSet, oldDomains);
            Set<String> removedCerts = getDifference(oldDomains, newDomainSet);

            log.info("📊 Certificate changes: +{} added, -{} removed", addedCerts.size(), removedCerts.size());

            // Add listeners for new certificates
            for (String domain : addedCerts) {
                addCertificateListener(domain);
            }

            // Remove listeners for deleted certificates
            for (String domain : removedCerts) {
                removeCertificateListener(domain);
            }

            // Restart HTTPS server if there are changes
            if (!addedCerts.isEmpty() || !removedCerts.isEmpty()) {
                if (!sslContextManager.getConfiguredDomains().isEmpty()) {
                    sslServerConfig.startHttpsServer();
                }
                log.info("✅ Certificates index refresh completed");
            }

        } catch (Exception e) {
            log.error("Failed to process certificates index change", e);
        }
    }

    /**
     * Handle single certificate change event (create/update/delete).
     */
    private void onSingleCertificateChange(String domain, String content) {
        String certDataId = SSL_CERTIFICATE_PREFIX + domain;
        try {
            if (content == null || content.isBlank()) {
                // Certificate deleted - remove from SSL context manager
                appliedContent.forget(certDataId);
                sslContextManager.handleCertificateUpdate(Map.of("domain", domain, "enabled", false));
                log.info("🗑️  Certificate deleted: {}", domain);
            } else {
                // Certificate created or updated
                appliedContent.recordIfChanged(certDataId, content);
                @SuppressWarnings("unchecked")
                Map<String, Object> certData = objectMapper.readValue(content, Map.class);
                sslContextManager.handleCertificateUpdate(certData);
                log.info("✏️  Certificate updated: {}", domain);
            }

            // Restart HTTPS server to apply changes
            if (!sslContextManager.getConfiguredDomains().isEmpty()) {
                sslServerConfig.startHttpsServer();
            } else {
                // FIX: when the last certificate was removed the HTTPS server kept
                // running with the stale context; stop it explicitly.
                sslServerConfig.stopHttpsServer();
            }

        } catch (Exception e) {
            log.error("Failed to process certificate change: {}", domain, e);
        }
    }

    /**
     * Load all certificates on startup.
     */
    private void loadAllCertificates() {
        log.info("🔥 Loading all certificates on startup...");

        try {
            String indexContent = configService.getConfig(SSL_CERTIFICATES_INDEX, GROUP);
            List<String> domains = parseDomains(indexContent);

            for (String domain : domains) {
                String certDataId = SSL_CERTIFICATE_PREFIX + domain;
                String certConfig = configService.getConfig(certDataId, GROUP);

                if (certConfig != null && !certConfig.isBlank()) {
                    appliedContent.recordIfChanged(certDataId, certConfig);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> certData = objectMapper.readValue(certConfig, Map.class);
                    sslContextManager.handleCertificateUpdate(certData);

                    // Add listener for this certificate
                    addCertificateListener(domain);

                    log.debug("Loaded certificate: {}", domain);
                }
            }

            log.info("✅ Loaded {} certificates on startup", domains.size());

        } catch (Exception e) {
            log.error("Failed to load initial certificates", e);
        }
    }

    /**
     * Add listener for a single certificate.
     * 
     * REACTIVE FIX: Use Mono.delay instead of Thread.sleep to avoid blocking.
     * Retry logic runs on boundedElastic scheduler to offload from main thread.
     */
    private void addCertificateListener(String domain) {
        String certDataId = SSL_CERTIFICATE_PREFIX + domain;

        // Try to load certificate config with retry using reactive approach
        loadCertificateWithRetry(domain, certDataId, 3)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                certConfig -> {
                    if (certConfig != null && !certConfig.isBlank()) {
                        try {
                            appliedContent.recordIfChanged(certDataId, certConfig);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> certData = objectMapper.readValue(certConfig, Map.class);
                            sslContextManager.handleCertificateUpdate(certData);
                            log.info("✅ Loaded certificate: {}", domain);
                        } catch (Exception e) {
                            log.error("Failed to parse certificate: {}", domain, e);
                        }
                    } else {
                        log.warn("⚠️  Certificate config not found in Nacos after retries: {}, listener will wait for config", certDataId);
                    }
                    
                    // Always register listener (even if config not found yet, it may come later)
                    registerCertificateListener(domain, certDataId);
                },
                error -> {
                    log.error("Failed to load certificate: {}", domain, error);
                    // Still register listener for future updates
                    registerCertificateListener(domain, certDataId);
                }
            );
    }

    /**
     * Load certificate with retry using reactive delay.
     */
    private Mono<String> loadCertificateWithRetry(String domain, String certDataId, int maxRetries) {
        return Mono.defer(() -> {
            String certConfig = configService.getConfig(certDataId, GROUP);
            if (certConfig != null && !certConfig.isBlank()) {
                return Mono.just(certConfig);
            }
            return Mono.empty();
        })
        .repeatWhenEmpty(maxRetries, attempts ->
            attempts.concatMap(i -> Mono.delay(java.time.Duration.ofMillis(100L * (i + 1))))
        )
        .onErrorResume(e -> {
            log.debug("Retry delay interrupted for certificate: {}", domain);
            return Mono.empty();
        });
    }

    /**
     * Register listener for certificate updates.
     */
    private void registerCertificateListener(String domain, String certDataId) {
        ConfigCenterService.ConfigListener listener = (dataId, group, content) -> {
            onSingleCertificateChange(domain, content);
        };

        configService.addListener(certDataId, GROUP, listener);
        certListeners.put(domain, listener);
        listeningDomains.add(domain);

        log.info("✅ Added listener for certificate: {}", domain);
    }

    /**
     * Remove listener for a deleted certificate.
     */
    private void removeCertificateListener(String domain) {
        String certDataId = SSL_CERTIFICATE_PREFIX + domain;
        ConfigCenterService.ConfigListener listener = certListeners.remove(domain);

        if (listener != null) {
            configService.removeListener(certDataId, GROUP, listener);
            log.info("🗑️  Removed listener for certificate: {}", domain);
        }

        // Outside the null check: the periodic sweep keys off this set, so a stale domain
        // here would make it re-remove the same certificate on every cycle.
        listeningDomains.remove(domain);
        appliedContent.forget(certDataId);

        // Remove from SSL context manager
        sslContextManager.handleCertificateUpdate(Map.of("domain", domain, "enabled", false));
        log.info("🗑️  Removed certificate from SSL context: {}", domain);
    }

    /**
     * Parse domains from index JSON.
     */
    private List<String> parseDomains(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.error("Failed to parse domains from index", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get difference between two sets (elements in set1 but not in set2).
     */
    private Set<String> getDifference(Set<String> set1, Set<String> set2) {
        Set<String> result = new HashSet<>(set1);
        result.removeAll(set2);
        return result;
    }

    /**
     * Periodic reconcile against the config center.
     *
     * <p>Stands in for change pushes, which this deployment never receives (nacos-client
     * 3.0.3 against nacos-server 2.5.4 - measured live: the server's remote-push.log stays
     * empty while the publish succeeds). Comparing only the index domain set, as this
     * method used to, recovers additions but leaves the two operations that matter most
     * for certificates invisible: a renewal (same domain, new cert) and a revocation.
     *
     * <p>A null/blank index returns early rather than removing every certificate -
     * {@code getConfig} cannot distinguish "deleted" from "config center unreachable", and
     * an HTTPS outage on a transient blip is worse than serving a certificate a little
     * longer. The index is also parsed strictly, not via {@link #parseDomains}, which
     * swallows parse errors into an empty list and would read a corrupt index as
     * "every certificate was revoked".
     */
    @Scheduled(fixedRateString = "${gateway.config-sync.interval-ms:60000}")
    public void periodicSyncCertificates() {
        try {
            String indexContent = configService.getConfig(SSL_CERTIFICATES_INDEX, GROUP);
            if (indexContent == null || indexContent.isBlank()) {
                return;
            }

            List<String> domains = objectMapper.readValue(indexContent,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            Set<String> indexed = new HashSet<>(domains);

            int removed = 0;
            for (String domain : new HashSet<>(listeningDomains)) {
                if (!indexed.contains(domain)) {
                    log.warn("🔍 Periodic sync found a deleted certificate: {}", domain);
                    removeCertificateListener(domain);
                    removed++;
                }
            }

            int applied = 0;
            for (String domain : domains) {
                String certDataId = SSL_CERTIFICATE_PREFIX + domain;

                if (!listeningDomains.contains(domain)) {
                    log.warn("🔍 Found missing certificate during periodic sync: {}", domain);
                    // Registers the listener too (asynchronously) - the old version only
                    // pushed the cert into the SSL context, so the same domain was
                    // "recovered" again on every cycle and never got a listener.
                    addCertificateListener(domain);
                    continue;
                }

                String certConfig = configService.getConfig(certDataId, GROUP);
                if (certConfig == null || certConfig.isBlank()) {
                    continue;
                }
                if (appliedContent.recordIfChanged(certDataId, certConfig)) {
                    log.warn("🔍 Periodic sync found a certificate change: {}", domain);
                    onSingleCertificateChange(domain, certConfig);
                    applied++;
                }
            }

            if (applied > 0 || removed > 0) {
                log.info("📊 Periodic certificate sync: {} applied, {} removed", applied, removed);
            }

            // onSingleCertificateChange already restarts HTTPS for the applied ones; a
            // removal does not, and removing the last certificate must stop the server
            // rather than leave it running on a stale context.
            if (removed > 0 && applied == 0) {
                if (!sslContextManager.getConfiguredDomains().isEmpty()) {
                    sslServerConfig.startHttpsServer();
                } else {
                    sslServerConfig.stopHttpsServer();
                }
            }

        } catch (Exception e) {
            log.warn("Periodic certificate sync failed - certificate changes stay unapplied until it succeeds", e);
        }
    }
}