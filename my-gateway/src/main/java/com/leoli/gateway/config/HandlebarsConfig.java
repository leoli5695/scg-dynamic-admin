package com.leoli.gateway.config;

import com.github.jknack.handlebars.Handlebars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Handlebars template engine configuration.
 * <p>
 * Provides singleton Handlebars instance for template-based mock responses.
 * <p>
 * Benefits:
 * - Reduced memory footprint (single instance)
 * - Consistent template configuration
 * - Shared custom helpers can be registered centrally
 *
 * @author leoli
 */
@Configuration
public class HandlebarsConfig {

    /**
     * Handlebars bean for template operations.
     * <p>
     * Used by MockResponseFilter for template-based mock responses.
     */
    @Bean
    public Handlebars handlebars() {
        Handlebars handlebars = new Handlebars();
        // Custom helpers can be registered here if needed
        // handlebars.registerHelper("random", new RandomHelper());
        return handlebars;
    }
}