package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.ssl.DynamicSslContextManager;
import com.leoli.gateway.ssl.SslServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leoli.gateway.constants.GatewayConfigConstants.GROUP;
import static com.leoli.gateway.constants.GatewayConfigConstants.SSL_CERTIFICATES_INDEX;
import static com.leoli.gateway.constants.GatewayConfigConstants.SSL_CERTIFICATE_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the periodic reconcile that stands in for config-center push, which
 * nacos-client 3.0.3 never receives from nacos-server 2.5.4.
 * <p>
 * A certificate renewal is the canonical edit that an ID-set comparison cannot see: the
 * domain stays in the index, only the payload changes. Left unnoticed, the gateway keeps
 * serving the expired certificate until it restarts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SslCertificateRefresherTest {

    @Mock
    private DynamicSslContextManager sslContextManager;

    @Mock
    private ConfigCenterService configService;

    @Mock
    private SslServerConfig sslServerConfig;

    private SslCertificateRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SslCertificateRefresher(sslContextManager, configService,
                sslServerConfig, new ObjectMapper());
        when(sslContextManager.getConfiguredDomains()).thenReturn(Set.of("d1"));
    }

    private static String certJson(String domain, String pem) {
        return "{\"domain\":\"" + domain + "\",\"enabled\":true,\"certificate\":\"" + pem + "\"}";
    }

    private void stubIndex(String... domains) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < domains.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(domains[i]).append('"');
        }
        when(configService.getConfig(SSL_CERTIFICATES_INDEX, GROUP)).thenReturn(json.append(']').toString());
    }

    private void stubCertificate(String domain, String pem) {
        when(configService.getConfig(SSL_CERTIFICATE_PREFIX + domain, GROUP)).thenReturn(certJson(domain, pem));
    }

    /**
     * Drives the same listener registration the async load path performs, so the tests stay
     * deterministic instead of racing boundedElastic.
     */
    private void givenListeningDomain(String domain) {
        ReflectionTestUtils.invokeMethod(refresher, "registerCertificateListener",
                domain, SSL_CERTIFICATE_PREFIX + domain);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> appliedCertificates(int expectedCount) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sslContextManager, times(expectedCount)).handleCertificateUpdate(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a renewed certificate is applied - the case ID-set sync misses")
    void appliesRenewalToKnownCertificate() {
        givenListeningDomain("d1");
        stubIndex("d1");
        stubCertificate("d1", "PEM-OLD");
        refresher.periodicSyncCertificates();

        stubCertificate("d1", "PEM-NEW");
        refresher.periodicSyncCertificates();

        List<Map<String, Object>> applied = appliedCertificates(2);
        assertEquals("PEM-OLD", applied.get(0).get("certificate"));
        assertEquals("PEM-NEW", applied.get(1).get("certificate"),
                "the renewed certificate must reach the SSL context without a restart");
        verify(sslServerConfig, times(2)).startHttpsServer();
    }

    @Test
    @DisplayName("unchanged content is not reapplied, so a steady-state cycle is silent")
    void doesNotReapplyUnchangedContent() {
        givenListeningDomain("d1");
        stubIndex("d1");
        stubCertificate("d1", "PEM-OLD");
        refresher.periodicSyncCertificates();

        refresher.periodicSyncCertificates();
        refresher.periodicSyncCertificates();

        appliedCertificates(1);
        verify(sslServerConfig, times(1)).startHttpsServer();
    }

    @Test
    @DisplayName("a certificate dropped from the index is revoked and HTTPS stops when it was the last one")
    void removesCertificateMissingFromIndex() {
        givenListeningDomain("d1");
        stubIndex("d1");
        stubCertificate("d1", "PEM-OLD");
        refresher.periodicSyncCertificates();
        Mockito.clearInvocations(sslContextManager, sslServerConfig);
        when(sslContextManager.getConfiguredDomains()).thenReturn(Set.of());

        stubIndex();
        refresher.periodicSyncCertificates();

        Map<String, Object> revoked = appliedCertificates(1).get(0);
        assertEquals("d1", revoked.get("domain"));
        assertFalse((Boolean) revoked.get("enabled"), "the certificate must be disabled, not left installed");
        verify(configService).removeListener(eq(SSL_CERTIFICATE_PREFIX + "d1"), eq(GROUP), any());
        verify(sslServerConfig).stopHttpsServer();
        verify(sslServerConfig, never()).startHttpsServer();
    }

    @Test
    @DisplayName("an unreachable (null) index must not revoke live certificates")
    void nullIndexDoesNotWipe() {
        givenListeningDomain("d1");
        stubIndex("d1");
        stubCertificate("d1", "PEM-OLD");
        refresher.periodicSyncCertificates();
        Mockito.clearInvocations(sslContextManager, sslServerConfig);

        when(configService.getConfig(SSL_CERTIFICATES_INDEX, GROUP)).thenReturn(null);
        refresher.periodicSyncCertificates();

        verify(sslContextManager, never()).handleCertificateUpdate(any());
        verify(sslServerConfig, never()).stopHttpsServer();
    }

    @Test
    @DisplayName("a corrupt index must not be read as 'every certificate was revoked'")
    void corruptIndexDoesNotWipe() {
        givenListeningDomain("d1");
        stubIndex("d1");
        stubCertificate("d1", "PEM-OLD");
        refresher.periodicSyncCertificates();
        Mockito.clearInvocations(sslContextManager, sslServerConfig);

        when(configService.getConfig(SSL_CERTIFICATES_INDEX, GROUP)).thenReturn("{not a list");
        refresher.periodicSyncCertificates();

        verify(sslContextManager, never()).handleCertificateUpdate(any());
        verify(sslServerConfig, never()).stopHttpsServer();
    }
}
