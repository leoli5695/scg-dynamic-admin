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
    @Test
    void bindsUpstreamClientAndDiscoverySettingsFromApplicationYaml() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        Binder binder = Binder.get(environment);
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
}
