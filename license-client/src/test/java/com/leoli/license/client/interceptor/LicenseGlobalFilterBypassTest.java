package com.leoli.license.client.interceptor;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.validator.FeatureGatekeeper;
import com.leoli.license.client.validator.LicenseStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The license bypass list must match {@code /health} exactly. A bare prefix match also
 * exempted look-alike business routes (e.g. {@code /health-data}) from licensing.
 */
@DisplayName("License filter internal-endpoint bypass")
class LicenseGlobalFilterBypassTest {

    private final LicenseStore licenseStore = mock(LicenseStore.class);
    private final FeatureGatekeeper featureGatekeeper = mock(FeatureGatekeeper.class);
    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final LicenseProperties properties = new LicenseProperties();
    private final LicenseGlobalFilter filter =
            new LicenseGlobalFilter(licenseStore, featureGatekeeper, properties);

    private MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    /** No license and no fallback: any path that is not exempt must be rejected. */
    private void givenUnlicensed() {
        properties.setEnabled(true);
        properties.setAllowBasicFallback(false);
        when(licenseStore.getLicense()).thenReturn(null);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("/health, /actuator and their real sub-paths still bypass the license check")
    void healthAndSubPathsBypass() {
        givenUnlicensed();

        filter.filter(exchangeFor("/health"), chain).block();
        filter.filter(exchangeFor("/health/liveness"), chain).block();
        filter.filter(exchangeFor("/actuator"), chain).block();
        filter.filter(exchangeFor("/actuator/info"), chain).block();

        verify(chain, times(4)).filter(any());
    }

    @Test
    @DisplayName("Look-alike business paths under /health or /actuator are NOT exempt")
    void lookAlikeBusinessPathIsNotExempt() {
        givenUnlicensed();

        for (String path : new String[]{"/health-data", "/actuator-data"}) {
            MockServerWebExchange exchange = exchangeFor(path);
            filter.filter(exchange, chain).block();
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(),
                    path + " must not bypass the license check");
        }

        verify(chain, never()).filter(any());
    }
}
