package com.leoli.gateway.ssl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Loads SSL certificates from Admin API and pushes to DynamicSslContextManager
 */
@Slf4j
@Component
public class SslCertificateLoader {

    private final RestTemplate restTemplate;
    private final DynamicSslContextManager sslContextManager;
    private final SslServerConfig sslServerConfig;
    private final ObjectMapper objectMapper;

    @Value("${gateway.admin.url:http://localhost:9090}")
    private String adminUrl;

    @Autowired
    public SslCertificateLoader(RestTemplate restTemplate,
                                DynamicSslContextManager sslContextManager,
                                SslServerConfig sslServerConfig,
                                ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.sslContextManager = sslContextManager;
        this.sslServerConfig = sslServerConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * Load certificates on application ready and start HTTPS server
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        loadCertificates();

        // Try to start HTTPS server after loading certificates
        if (!sslContextManager.getConfiguredDomains().isEmpty()) {
            sslServerConfig.startHttpsServer();
        }
    }

    /**
     * Load certificates from Admin API every 30 seconds
     */
    @Scheduled(fixedRate = 30000, initialDelay = 30000)
    public void loadCertificates() {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> certificates = restTemplate.getForObject(
                    adminUrl + "/api/ssl?enabled=true",
                    List.class);

            if (certificates == null || certificates.isEmpty()) {
                log.debug("No enabled certificates found");
                return;
            }

            log.info("Loaded {} certificates from Admin API", certificates.size());

            for (Map<String, Object> cert : certificates) {
                try {
                    Boolean enabled = (Boolean) cert.get("enabled");
                    if (enabled == null || !enabled) {
                        continue;
                    }

                    String domain = (String) cert.get("domain");
                    String certType = (String) cert.get("certType");

                    if (domain == null) {
                        log.warn("Certificate missing domain, skipping");
                        continue;
                    }

                    // Push to SSL context manager
                    sslContextManager.handleCertificateUpdate(cert);

                    log.info("Loaded certificate for domain: {} (type: {})", domain, certType);

                } catch (Exception e) {
                    log.error("Failed to process certificate: {}", cert.get("domain"), e);
                }
            }

            // Update SSL cache or start HTTPS server
            // startHttpsServer() will update cache if already running
            if (!sslContextManager.getConfiguredDomains().isEmpty()) {
                sslServerConfig.startHttpsServer();
            }

        } catch (Exception e) {
            log.error("Failed to load certificates from Admin API", e);
        }
    }
}