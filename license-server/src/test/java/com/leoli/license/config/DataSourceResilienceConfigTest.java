package com.leoli.license.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceResilienceConfigTest {

    /** Strips env/system properties so every ${VAR:default} resolves to its declared default. */
    private StandardEnvironment yamlDefaults() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    /**
     * A DB restart black-holes every pooled socket. Connector/J defaults socketTimeout to 0, so the
     * read blocks forever, Hikari cannot retire a checked-out connection, and the pool stays wedged
     * until the process restarts.
     */
    @Test
    void jdbcUrlBoundsBothConnectAndSocketRead() throws Exception {
        String url = yamlDefaults().getProperty("spring.datasource.url");
        assertEquals(10000, jdbcParam(url, "connectTimeout"));
        assertEquals(60000, jdbcParam(url, "socketTimeout"));
    }

    @Test
    void hikariProbesIdleConnectionsAndBoundsValidation() throws Exception {
        HikariConfig hikari = Binder.get(yamlDefaults())
                .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();

        assertEquals(30000, hikari.getKeepaliveTime());

        // Hikari silently disables keepalive outside this window instead of failing startup.
        assertTrue(hikari.getKeepaliveTime() >= 30000, "keepalive below 30s is silently disabled");
        assertTrue(hikari.getKeepaliveTime() < hikari.getMaxLifetime(), "keepalive must be under maxLifetime");
        assertTrue(hikari.getValidationTimeout() < hikari.getConnectionTimeout());
    }

    private static int jdbcParam(String url, String name) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equals(name)) {
                return Integer.parseInt(pair.substring(separator + 1));
            }
        }
        throw new AssertionError("JDBC URL does not set " + name + ": " + url);
    }
}