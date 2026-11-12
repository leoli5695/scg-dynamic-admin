package com.leoli.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigurationTest {

    /** Strips env/system properties so every ${VAR:default} resolves to its declared default. */
    private Binder yamlDefaults() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment);
    }

    @Test
    void bindsUpstreamClientAndDiscoverySettingsFromApplicationYaml() throws Exception {
        Binder binder = yamlDefaults();
        HttpClientProperties client = binder.bind("spring.cloud.gateway.httpclient",
                Bindable.of(HttpClientProperties.class)).get();
        assertEquals(HttpClientProperties.Pool.PoolType.FIXED, client.getPool().getType());
        assertEquals(2000, client.getPool().getMaxConnections());
        assertEquals(5000, client.getConnectTimeout());
        assertEquals(Duration.ofSeconds(300), client.getResponseTimeout());
        assertFalse(client.isCompression());
        DiscoveryLocatorProperties discovery = binder.bind("spring.cloud.gateway.discovery.locator",
                Bindable.of(DiscoveryLocatorProperties.class)).get();
        assertTrue(discovery.isEnabled());
        assertFalse(discovery.isLowerCaseServiceId());
        assertFalse(binder.bind("spring.gateway.httpclient", Bindable.of(HttpClientProperties.class)).isBound());
    }

    /**
     * The YAML default and the Java default must agree on fail-closed. They disagreed once: the
     * class said false while application.yml said true, so every shipped deployment trusted any
     * private-network peer and let the caller choose the IP that drives allow/deny and rate limits.
     */
    @Test
    void trustedProxyDefaultsAreFailClosedInBothLayers() throws Exception {
        TrustedProxyProperties fromYaml = yamlDefaults()
                .bind("gateway.trusted-proxy", Bindable.of(TrustedProxyProperties.class)).get();
        TrustedProxyProperties fromJava = new TrustedProxyProperties();

        assertFalse(fromYaml.isTrustPrivateNetworks(), "application.yml must not default to trusting private networks");
        assertFalse(fromJava.isTrustPrivateNetworks(), "TrustedProxyProperties must default to fail-closed");
        assertEquals(fromJava.isTrustPrivateNetworks(), fromYaml.isTrustPrivateNetworks());

        assertTrue(fromYaml.isEnabled());
        assertTrue(fromYaml.getProxyIps().isEmpty());
        assertEquals(TrustedProxyProperties.ForwardedIpPosition.FIRST, fromYaml.getPosition());
        assertEquals(10, fromYaml.getMaxForwardedIps());
    }
}
