package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.model.SslCertificate;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.SslCertificateRepository;
import com.leoli.gateway.admin.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for SSL certificate configurations.
 * Ensures consistency between DB and Nacos for SSL certificates.
 *
 * <p>Certificates live in their own instance's Nacos namespace, so reconciliation has to be
 * namespace-complete. See {@link NamespacedReconcile} for the algorithm and for the bug this task
 * used to have (public-namespace index compared against per-namespace writes, which republished
 * every certificate every cycle and made orphan removal a structural no-op).
 */
@Slf4j
@Component
public class CertificateReconcileTask implements ReconcileTask<SslCertificate> {

    private static final String CERTIFICATE_PREFIX = "ssl-certificate-";
    private static final String CERTIFICATES_INDEX = "config.gateway.metadata.ssl-certificates-index";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private SslCertificateRepository sslCertificateRepository;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Autowired
    private InstanceNamespaceCache namespaceCache;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private AlertService alertService;

    @Override
    public String getType() {
        return "SSL_CERTIFICATE";
    }

    @Override
    public ReconcileResult reconcile() {
        return NamespacedReconcile.reconcile(new NamespacedReconcile.Spec<>(
                getType(),
                configCenterService,
                log,
                sslCertificateRepository.findAll(),
                cert -> Boolean.TRUE.equals(cert.getEnabled()),
                SslCertificate::getDomain,
                // A certificate belongs to exactly one namespace - unlike strategies there is no
                // GLOBAL broadcast scope. An empty result makes the reconciler skip it and warn.
                cert -> {
                    String ns = getNacosNamespace(cert.getInstanceId());
                    return ns != null ? List.of(ns) : List.of();
                },
                CERTIFICATE_PREFIX,
                CERTIFICATES_INDEX,
                this::toDefinition,
                getAllGatewayNamespaces(),
                alertService));
    }

    @Override
    public List<SslCertificate> loadFromDB() {
        // Only load ENABLED certificates - disabled certificates should not be in Nacos
        return sslCertificateRepository.findByEnabled(true);
    }

    @Override
    public Set<String> loadFromNacos() {
        // Retained for the ReconcileTask contract and for ad-hoc inspection. It reads the PUBLIC
        // namespace index, which per-instance certificate config is never written to - that mismatch
        // is exactly what reconcile() above works around, so do not use this to decide what is missing.
        try {
            // Read as List<String> since index is stored as JSON array (domains)
            List<String> domains = configCenterService.getConfig(CERTIFICATES_INDEX,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (domains == null || domains.isEmpty()) {
                return Set.of();
            }
            return domains.stream().collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load certificates index from Nacos", e);
            return Set.of();
        }
    }

    @Override
    public String extractId(SslCertificate entity) {
        return entity.getDomain();  // Use domain as business identifier
    }

    @Override
    public void repairMissingInNacos(SslCertificate entity) throws Exception {
        // Skip disabled certificates - they should NOT be in Nacos
        if (!entity.getEnabled()) {
            log.debug("Skipping disabled certificate: {}", entity.getDomain());
            return;
        }

        // Skip certificates without valid instanceId - should NOT publish to public namespace
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        if (nacosNamespace == null) {
            log.warn("Skipping certificate {} without valid instanceId (instanceId={}), will not publish to public namespace",
                     entity.getDomain(), entity.getInstanceId());
            return;
        }

        log.info("Repairing missing certificate in Nacos: {}", entity.getDomain());

        // Convert certificate to config map
        Map<String, Object> config = toDefinition(entity);

        // Push to Nacos using domain as dataId. A false return used to be discarded here, so the
        // caller counted the certificate as repaired and reported success while nothing had actually
        // landed in Nacos.
        String certDataId = CERTIFICATE_PREFIX + entity.getDomain();
        if (!configCenterService.publishConfig(certDataId, nacosNamespace, config)) {
            throw new IllegalStateException("Failed to publish certificate " + entity.getDomain()
                    + " to namespace " + nacosNamespace);
        }

        log.info("Repaired certificate: {} in namespace: {}", entity.getDomain(), nacosNamespace);

        // Rebuild certificates index to ensure consistency
        rebuildCertificatesIndex(entity.getInstanceId());
    }

    @Override
    public void removeOrphanFromNacos(String domain) throws Exception {
        log.info("Removing orphaned certificate from Nacos: {}", domain);

        // Find the certificate to get instanceId. NOTE: for a true orphan this lookup comes back
        // empty, so the namespace resolves to null and nothing is removed - which is why reconcile()
        // does orphan removal per namespace instead of through this method. Kept for the contract and
        // for the row-still-exists case (a certificate that was disabled and should stop being published).
        SslCertificate cert = sslCertificateRepository.findByDomain(domain).orElse(null);
        String nacosNamespace = cert != null ? getNacosNamespace(cert.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan certificate {} without valid instanceId, will not remove from public namespace", domain);
            return;
        }

        // Delete from Nacos using domain as dataId
        String certDataId = CERTIFICATE_PREFIX + domain;
        if (!configCenterService.removeConfig(certDataId, nacosNamespace)) {
            throw new IllegalStateException("Failed to remove certificate " + domain
                    + " from namespace " + nacosNamespace);
        }

        log.info("Removed orphan certificate: {} from namespace: {}", domain, nacosNamespace);

        // Rebuild certificates index after removal
        if (cert != null) {
            rebuildCertificatesIndex(cert.getInstanceId());
        }
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    private List<String> getAllGatewayNamespaces() {
        return gatewayInstanceRepository.findAll().stream()
                .map(i -> i.getNacosNamespace())
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Rebuild certificates index from database for a specific instance.
     * Only includes ENABLED certificates - disabled certificates should not be in Nacos.
     */
    private void rebuildCertificatesIndex(String instanceId) throws Exception {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }

        String nacosNamespace = getNacosNamespace(instanceId);
        if (nacosNamespace == null) {
            return;
        }

        // Only include ENABLED certificates for this instance
        List<String> domains = sslCertificateRepository.findByInstanceIdAndEnabledTrue(instanceId).stream()
            .map(SslCertificate::getDomain)
            .collect(Collectors.toList());

        // Publish as JSON array to instance namespace
        if (!configCenterService.publishConfig(CERTIFICATES_INDEX, nacosNamespace, domains)) {
            log.error("Failed to rebuild certificates index in namespace {}", nacosNamespace);
        } else {
            log.debug("Certificates index rebuilt with {} enabled certificates in namespace {}", domains.size(), nacosNamespace);
        }
    }

    /**
     * The published shape of a certificate: a flat config map for Nacos (this task has no metadata
     * JSON to deserialize, every field comes straight off the entity).
     */
    private Map<String, Object> toDefinition(SslCertificate cert) {
        Map<String, Object> config = new HashMap<>();
        config.put("id", cert.getId());
        config.put("domain", cert.getDomain());
        config.put("certName", cert.getCertName());
        config.put("certType", cert.getCertType());
        config.put("certContent", cert.getCertContent());
        config.put("keyContent", cert.getKeyContent());
        config.put("keystoreContent", cert.getKeystoreContent());
        config.put("keystorePassword", cert.getKeystorePassword());
        config.put("issuer", cert.getIssuer());
        config.put("serialNumber", cert.getSerialNumber());
        config.put("validFrom", cert.getValidFrom());
        config.put("validTo", cert.getValidTo());
        config.put("daysToExpiry", cert.getDaysToExpiry());
        config.put("status", cert.getStatus());
        config.put("enabled", cert.getEnabled());
        config.put("associatedRoutes", cert.getAssociatedRoutes());
        return config;
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}