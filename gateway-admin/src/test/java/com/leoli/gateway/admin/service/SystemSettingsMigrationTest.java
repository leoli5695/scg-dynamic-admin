package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.SystemSettingsEntity;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SystemSettingsMigrationTest {
    @Test
    void productionMigrationValidatesAndPreservesExistingSwitchesOnRerun() throws Exception {
        String url = "jdbc:h2:mem:settings-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        var migration = new ClassPathResource("sql/system-settings.sql");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE system_settings SET plugins_enabled=false, updated_by='operator' WHERE id=1");
            }
            ScriptUtils.executeSqlScript(connection, migration);
        }
        var configuration = new Configuration().addAnnotatedClass(SystemSettingsEntity.class);
        configuration.setProperty("hibernate.connection.url", url);
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
            var settings = session.find(SystemSettingsEntity.class, 1L);
            assertNotNull(settings);
            assertFalse(settings.isPluginsEnabled());
            assertTrue(settings.isMiddlewareEnabled());
            assertEquals("operator", settings.getUpdatedBy());
        }
    }
}
