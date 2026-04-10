package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.ConfigTemplate;
import com.leoli.gateway.admin.repository.ConfigTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration Template Service.
 * Provides template management, application, and customization.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTemplateService {

    private final ConfigTemplateRepository templateRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Variable pattern: {{variableName}}
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}}");

    /**
     * Get all public templates.
     */
    public List<ConfigTemplate> getAllPublicTemplates() {
        return templateRepository.findByIsPublicTrue();
    }

    /**
     * Get templates by category.
     */
    public List<ConfigTemplate> getTemplatesByCategory(String category) {
        return templateRepository.findByIsPublicTrue()
                .stream()
                .filter(t -> t.getCategory().equals(category))
                .sorted(Comparator.comparing(ConfigTemplate::getDownloadCount).reversed())
                .toList();
    }

    /**
     * Get popular templates.
     */
    public List<ConfigTemplate> getPopularTemplates(int limit) {
        return templateRepository.findPopularTemplates()
                .stream()
                .limit(limit)
                .toList();
    }

    /**
     * Get top rated templates.
     */
    public List<ConfigTemplate> getTopRatedTemplates(int limit) {
        return templateRepository.findTopRatedTemplates()
                .stream()
                .limit(limit)
                .toList();
    }

    /**
     * Search templates.
     */
    public List<ConfigTemplate> searchTemplates(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllPublicTemplates();
        }
        return templateRepository.searchTemplates(keyword.trim());
    }

    /**
     * Filter templates.
     */
    public List<ConfigTemplate> filterTemplates(String category, String subcategory, Boolean officialOnly) {
        return templateRepository.filterTemplates(category, subcategory, officialOnly);
    }

    /**
     * Get template by ID.
     */
    public ConfigTemplate getTemplateById(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found: " + id));
    }

    /**
     * Get all categories.
     */
    public List<String> getAllCategories() {
        return templateRepository.findAllCategories();
    }

    /**
     * Get subcategories for a category.
     */
    public List<String> getSubcategories(String category) {
        return templateRepository.findSubcategoriesByCategory(category);
    }

    /**
     * Apply template with variable substitution.
     *
     * @param templateId Template ID
     * @param variables  Variable values
     * @return Applied configuration
     */
    public AppliedTemplate applyTemplate(Long templateId, Map<String, String> variables) {
        ConfigTemplate template = getTemplateById(templateId);

        // Validate required variables
        List<String> missingVars = validateVariables(template, variables);
        if (!missingVars.isEmpty()) {
            return new AppliedTemplate(false, null, "Missing required variables: " + missingVars, null);
        }

        // Substitute variables
        String config = substituteVariables(template.getConfigContent(), variables);

        // Increment download count
        template.setDownloadCount(template.getDownloadCount() + 1);
        templateRepository.save(template);

        return new AppliedTemplate(true, config, null, template);
    }

    /**
     * Preview template with sample values.
     */
    public PreviewResult previewTemplate(Long templateId) {
        ConfigTemplate template = getTemplateById(templateId);

        // Generate sample values for variables
        Map<String, String> sampleValues = generateSampleValues(template);

        // Substitute with sample values
        String preview = substituteVariables(template.getConfigContent(), sampleValues);

        return new PreviewResult(template, preview, sampleValues);
    }

    /**
     * Create a new template.
     */
    @Transactional
    public ConfigTemplate createTemplate(ConfigTemplate template) {
        // Validate template
        validateTemplateContent(template);

        template.setDownloadCount(0);
        template.setLikeCount(0);
        template.setIsPublic(true);

        return templateRepository.save(template);
    }

    /**
     * Update a template.
     */
    @Transactional
    public ConfigTemplate updateTemplate(Long id, ConfigTemplate updates) {
        ConfigTemplate existing = getTemplateById(id);

        // Update allowed fields
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
        if (updates.getSubcategory() != null) existing.setSubcategory(updates.getSubcategory());
        if (updates.getConfigContent() != null) {
            validateTemplateContent(updates);
            existing.setConfigContent(updates.getConfigContent());
        }
        if (updates.getTags() != null) existing.setTags(updates.getTags());
        if (updates.getUsageNotes() != null) existing.setUsageNotes(updates.getUsageNotes());
        if (updates.getVariables() != null) existing.setVariables(updates.getVariables());

        return templateRepository.save(existing);
    }

    /**
     * Delete a template.
     */
    @Transactional
    public void deleteTemplate(Long id) {
        ConfigTemplate template = getTemplateById(id);
        templateRepository.delete(template);
    }

    /**
     * Like a template.
     */
    @Transactional
    public void likeTemplate(Long id) {
        ConfigTemplate template = getTemplateById(id);
        template.setLikeCount(template.getLikeCount() + 1);
        templateRepository.save(template);
    }

    /**
     * Rate a template.
     */
    @Transactional
    public void rateTemplate(Long id, double rating) {
        if (rating < 0 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 0 and 5");
        }

        ConfigTemplate template = getTemplateById(id);
        template.setRating(rating);
        templateRepository.save(template);
    }

    /**
     * Initialize default templates.
     */
    @Transactional
    public void initializeDefaultTemplates() {
        if (templateRepository.count() > 0) {
            return;
        }

        log.info("Initializing default configuration templates...");

        // Route templates
        createRouteTemplates();

        // Strategy templates
        createStrategyTemplates();

        // Filter templates
        createFilterTemplates();

        log.info("Default templates initialized");
    }

    // ============== Private Methods ==============

    private void validateTemplateContent(ConfigTemplate template) {
        if (template.getConfigContent() == null || template.getConfigContent().isEmpty()) {
            throw new IllegalArgumentException("Template content cannot be empty");
        }

        // Validate JSON/YAML format based on configType
        if ("json".equals(template.getConfigType())) {
            try {
                objectMapper.readTree(template.getConfigContent());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON content: " + e.getMessage());
            }
        }
    }

    private List<String> validateVariables(ConfigTemplate template, Map<String, String> provided) {
        List<String> requiredVars = extractVariables(template.getConfigContent());
        List<String> missing = new ArrayList<>();

        for (String var : requiredVars) {
            if (!provided.containsKey(var) || provided.get(var) == null || provided.get(var).isEmpty()) {
                missing.add(var);
            }
        }

        return missing;
    }

    private List<String> extractVariables(String content) {
        List<String> variables = new ArrayList<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private String substituteVariables(String content, Map<String, String> values) {
        String result = content;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private Map<String, String> generateSampleValues(ConfigTemplate template) {
        Map<String, String> samples = new LinkedHashMap<>();

        List<String> variables = extractVariables(template.getConfigContent());
        for (String var : variables) {
            samples.put(var, getSampleValue(var));
        }

        return samples;
    }

    private String getSampleValue(String variableName) {
        // Generate appropriate sample values based on variable name
        return switch (variableName.toLowerCase()) {
            case "serviceid", "service_name" -> "sample-service";
            case "routeid", "route_id" -> "sample-route";
            case "path", "pattern" -> "/api/sample/**";
            case "uri", "backend_uri" -> "lb://sample-service";
            case "rate", "limit" -> "100";
            case "timeout", "timeoutms" -> "3000";
            case "retry", "retries" -> "3";
            case "header", "header_name" -> "X-Custom-Header";
            case "value", "header_value" -> "custom-value";
            default -> "sample-" + variableName;
        };
    }

    private void createRouteTemplates() {
        // Simple route template
        ConfigTemplate simpleRoute = new ConfigTemplate();
        simpleRoute.setName("Simple Route");
        simpleRoute.setDescription("A basic route configuration with path matching");
        simpleRoute.setCategory("route");
        simpleRoute.setSubcategory("basic");
        simpleRoute.setConfigType("json");
        simpleRoute.setConfigContent("""
            {
              "id": "{{routeId}}",
              "uri": "{{uri}}",
              "predicates": [
                {"name": "Path", "args": {"pattern": "{{path}}"}}
              ]
            }
            """);
        simpleRoute.setIsOfficial(true);
        simpleRoute.setVariables("[{\"name\":\"routeId\",\"description\":\"Route ID\",\"required\":true}," +
                "{\"name\":\"uri\",\"description\":\"Backend URI (e.g., lb://service-name)\",\"required\":true}," +
                "{\"name\":\"path\",\"description\":\"Path pattern\",\"required\":true}]");
        simpleRoute.setTags("route,basic,simple");
        simpleRoute.setAuthor("Gateway Team");
        templateRepository.save(simpleRoute);

        // Rate limited route
        ConfigTemplate rateLimitedRoute = new ConfigTemplate();
        rateLimitedRoute.setName("Rate Limited Route");
        rateLimitedRoute.setDescription("Route with built-in rate limiting");
        rateLimitedRoute.setCategory("route");
        rateLimitedRoute.setSubcategory("rate-limit");
        rateLimitedRoute.setConfigType("json");
        rateLimitedRoute.setConfigContent("""
            {
              "id": "{{routeId}}",
              "uri": "{{uri}}",
              "predicates": [
                {"name": "Path", "args": {"pattern": "{{path}}"}}
              ],
              "filters": [
                {"name": "RequestRateLimiter", "args": {
                  "replenishRate": "{{rate}}",
                  "burstCapacity": "{{burst}}"
                }}
              ]
            }
            """);
        rateLimitedRoute.setIsOfficial(true);
        rateLimitedRoute.setTags("route,rate-limit,throttle");
        rateLimitedRoute.setAuthor("Gateway Team");
        templateRepository.save(rateLimitedRoute);

        // Circuit breaker route
        ConfigTemplate cbRoute = new ConfigTemplate();
        cbRoute.setName("Circuit Breaker Route");
        cbRoute.setDescription("Route with circuit breaker for fault tolerance");
        cbRoute.setCategory("route");
        cbRoute.setSubcategory("circuit-breaker");
        cbRoute.setConfigType("json");
        cbRoute.setConfigContent("""
            {
              "id": "{{routeId}}",
              "uri": "{{uri}}",
              "predicates": [
                {"name": "Path", "args": {"pattern": "{{path}}"}}
              ],
              "filters": [
                {"name": "CircuitBreaker", "args": {
                  "name": "{{routeId}}CB",
                  "fallbackUri": "forward:/fallback"
                }}
              ]
            }
            """);
        cbRoute.setIsOfficial(true);
        cbRoute.setTags("route,circuit-breaker,resilience");
        cbRoute.setAuthor("Gateway Team");
        templateRepository.save(cbRoute);
    }

    private void createStrategyTemplates() {
        // Rate limiter strategy
        ConfigTemplate rateStrategy = new ConfigTemplate();
        rateStrategy.setName("Rate Limiter Strategy");
        rateStrategy.setDescription("Default rate limiting configuration");
        rateStrategy.setCategory("strategy");
        rateStrategy.setSubcategory("rate-limit");
        rateStrategy.setConfigType("json");
        rateStrategy.setConfigContent("""
            {
              "strategyId": "{{strategyId}}",
              "rateLimiter": {
                "enabled": true,
                "replenishRate": {{rate}},
                "burstCapacity": {{burst}},
                "keyResolver": "IP"
              }
            }
            """);
        rateStrategy.setIsOfficial(true);
        rateStrategy.setTags("strategy,rate-limit,throttle");
        templateRepository.save(rateStrategy);

        // Circuit breaker strategy
        ConfigTemplate cbStrategy = new ConfigTemplate();
        cbStrategy.setName("Circuit Breaker Strategy");
        cbStrategy.setDescription("Circuit breaker with retry configuration");
        cbStrategy.setCategory("strategy");
        cbStrategy.setSubcategory("circuit-breaker");
        cbStrategy.setConfigType("json");
        cbStrategy.setConfigContent("""
            {
              "strategyId": "{{strategyId}}",
              "circuitBreaker": {
                "enabled": true,
                "failureRateThreshold": {{failureRate}},
                "slowCallRateThreshold": {{slowCallRate}},
                "slowCallDurationThreshold": "{{slowCallMs}}ms",
                "minimumNumberOfCalls": {{minCalls}},
                "waitDurationInOpenState": "{{waitMs}}ms"
              },
              "retry": {
                "enabled": true,
                "maxAttempts": {{retries}},
                "waitDuration": "{{retryWaitMs}}ms"
              }
            }
            """);
        cbStrategy.setIsOfficial(true);
        cbStrategy.setTags("strategy,circuit-breaker,retry,resilience");
        templateRepository.save(cbStrategy);

        // Timeout strategy
        ConfigTemplate timeoutStrategy = new ConfigTemplate();
        timeoutStrategy.setName("Timeout Strategy");
        timeoutStrategy.setDescription("Connection and response timeout configuration");
        timeoutStrategy.setCategory("strategy");
        timeoutStrategy.setSubcategory("timeout");
        timeoutStrategy.setConfigType("json");
        timeoutStrategy.setConfigContent("""
            {
              "strategyId": "{{strategyId}}",
              "timeout": {
                "enabled": true,
                "connectTimeout": {{connectTimeout}},
                "responseTimeout": {{responseTimeout}}
              }
            }
            """);
        timeoutStrategy.setIsOfficial(true);
        timeoutStrategy.setTags("strategy,timeout");
        templateRepository.save(timeoutStrategy);
    }

    private void createFilterTemplates() {
        // Add header filter
        ConfigTemplate headerFilter = new ConfigTemplate();
        headerFilter.setName("Add Header Filter");
        headerFilter.setDescription("Filter to add custom headers to requests");
        headerFilter.setCategory("filter");
        headerFilter.setSubcategory("header");
        headerFilter.setConfigType("json");
        headerFilter.setConfigContent("""
            {
              "name": "AddRequestHeader",
              "args": {
                "name": "{{headerName}}",
                "value": "{{headerValue}}"
              }
            }
            """);
        headerFilter.setIsOfficial(true);
        headerFilter.setTags("filter,header,request");
        templateRepository.save(headerFilter);

        // Strip prefix filter
        ConfigTemplate stripFilter = new ConfigTemplate();
        stripFilter.setName("Strip Prefix Filter");
        stripFilter.setDescription("Strip path prefix before forwarding to backend");
        stripFilter.setCategory("filter");
        stripFilter.setSubcategory("path");
        stripFilter.setConfigType("json");
        stripFilter.setConfigContent("""
            {
              "name": "StripPrefix",
              "args": {
                "parts": {{parts}}
              }
            }
            """);
        stripFilter.setIsOfficial(true);
        stripFilter.setTags("filter,path,rewrite");
        templateRepository.save(stripFilter);

        // Rewrite path filter
        ConfigTemplate rewriteFilter = new ConfigTemplate();
        rewriteFilter.setName("Rewrite Path Filter");
        rewriteFilter.setDescription("Rewrite request path using regex");
        rewriteFilter.setCategory("filter");
        rewriteFilter.setSubcategory("path");
        rewriteFilter.setConfigType("json");
        rewriteFilter.setConfigContent("""
            {
              "name": "RewritePath",
              "args": {
                "regexp": "{{regexp}}",
                "replacement": "{{replacement}}"
              }
            }
            """);
        rewriteFilter.setIsOfficial(true);
        rewriteFilter.setTags("filter,path,rewrite,regex");
        templateRepository.save(rewriteFilter);
    }

    // ============== Data Classes ==============

    public static class AppliedTemplate {
        private final boolean success;
        private final String config;
        private final String error;
        private final ConfigTemplate template;

        public AppliedTemplate(boolean success, String config, String error, ConfigTemplate template) {
            this.success = success;
            this.config = config;
            this.error = error;
            this.template = template;
        }

        public boolean isSuccess() { return success; }
        public String getConfig() { return config; }
        public String getError() { return error; }
        public ConfigTemplate getTemplate() { return template; }
    }

    public static class PreviewResult {
        private final ConfigTemplate template;
        private final String preview;
        private final Map<String, String> sampleValues;

        public PreviewResult(ConfigTemplate template, String preview, Map<String, String> sampleValues) {
            this.template = template;
            this.preview = preview;
            this.sampleValues = sampleValues;
        }

        public ConfigTemplate getTemplate() { return template; }
        public String getPreview() { return preview; }
        public Map<String, String> getSampleValues() { return sampleValues; }
    }
}