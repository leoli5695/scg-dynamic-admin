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
    public List<SslCertificate> loadFromDB() {
        // Only load ENABLED certificates - disabled certificates should not be in Nacos
        return sslCertificateRepository.findByEnabled(true);
    }

    @Override
    public Set<String> loadFromNacos() {
        // Note: This loads from default namespace (public), which is legacy behavior
        // The actual reconciliation now uses per-instance namespace
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
        Map<String, Object> config = toConfigMap(entity);

        // Push to Nacos using domain as dataId
        String certDataId = CERTIFICATE_PREFIX + entity.getDomain();
        configCenterService.publishConfig(certDataId, nacosNamespace, config);

        log.info("Repaired certificate: {} in namespace: {}", entity.getDomain(), nacosNamespace);

        // Rebuild certificates index to ensure consistency
        rebuildCertificatesIndex(entity.getInstanceId());
    }

    @Override
    public void removeOrphanFromNacos(String domain) throws Exception {
        log.info("Removing orphaned certificate from Nacos: {}", domain);

        // Find the certificate to get instanceId
        SslCertificate cert = sslCertificateRepository.findByDomain(domain).orElse(null);
        String nacosNamespace = cert != null ? getNacosNamespace(cert.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan certificate {} without valid instanceId, will not remove from public namespace", domain);
            return;
        }

        // Delete from Nacos using domain as dataId
        String certDataId = CERTIFICATE_PREFIX + domain;
        configCenterService.removeConfig(certDataId, nacosNamespace);

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
        configCenterService.publishConfig(CERTIFICATES_INDEX, nacosNamespace, domains);
        log.debug("Certificates index rebuilt with {} enabled certificates in namespace {}", domains.size(), nacosNamespace);
    }

    /**
     * Convert certificate entity to config map for Nacos.
     */
    private Map<String, Object> toConfigMap(SslCertificate cert) {
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