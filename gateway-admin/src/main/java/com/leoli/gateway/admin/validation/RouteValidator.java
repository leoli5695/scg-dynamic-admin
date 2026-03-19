package com.leoli.gateway.admin.validation;

import com.leoli.gateway.admin.model.RouteDefinition;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Route definition validator.
 * Validates route fields before saving to ensure data integrity.
 *
 * @author leoli
 */
@Slf4j
public class RouteValidator {

    // Valid URI schemes for gateway routes
    private static final List<String> VALID_SCHEMES = List.of(
            "http", "https", "lb", "static"
    );

    // Route ID pattern: alphanumeric, hyphens, underscores
    private static final Pattern ROUTE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    // Predicate name pattern
    private static final Pattern PREDICATE_NAME_PATTERN = Pattern.compile("^[A-Za-z]+$");

    // Filter name pattern
    private static final Pattern FILTER_NAME_PATTERN = Pattern.compile("^[A-Za-z]+$");

    // Valid predicate names (Spring Cloud Gateway built-in)
    private static final List<String> VALID_PREDICATES = List.of(
            "Path", "Host", "Method", "Header", "Query", "Cookie", "After", "Before",
            "Between", "RemoteAddr", "Weight", "CloudFoundryRouteService", "ReadBody"
    );

    // Valid filter names (common ones)
    private static final List<String> VALID_FILTERS = List.of(
            "AddRequestHeader", "AddRequestParameter", "AddResponseHeader",
            "DedupeResponseHeader", "FallbackHeaders", "MapRequestHeader",
            "PrefixPath", "PreserveHostHeader", "RedirectTo", "RemoveRequestHeader",
            "RemoveResponseHeader", "RemoveRequestParameter", "RewritePath",
            "RewriteLocationResponseHeader", "SaveSession", "SecureHeaders",
            "SetPath", "SetRequestHeader", "SetRequestHostHeader", "SetResponseHeader",
            "SetStatus", "StripPrefix", "RequestHeaderSize", "RequestSize",
            "Retry", "RequestRateLimiter", "CircuitBreaker", "RewriteResponseHeader"
    );

    /**
     * Validate route definition.
     *
     * @param route Route definition to validate
     * @return List of validation errors, empty if valid
     */
    public static List<String> validate(RouteDefinition route) {
        List<String> errors = new ArrayList<>();

        if (route == null) {
            errors.add("Route definition cannot be null");
            return errors;
        }

        // Validate route ID
        validateRouteId(route.getId(), errors);

        // Validate URI
        validateUri(route.getUri(), errors);

        // Validate order
        validateOrder(route.getOrder(), errors);

        // Validate predicates
        validatePredicates(route.getPredicates(), errors);

        // Validate filters
        validateFilters(route.getFilters(), errors);

        return errors;
    }

    /**
     * Validate and throw exception if invalid.
     *
     * @param route Route definition to validate
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateAndThrow(RouteDefinition route) {
        List<String> errors = validate(route);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Route validation failed: " + String.join("; ", errors));
        }
    }

    private static void validateRouteId(String id, List<String> errors) {
        if (id == null || id.trim().isEmpty()) {
            errors.add("Route ID is required");
            return;
        }

        if (id.length() > 100) {
            errors.add("Route ID must not exceed 100 characters");
        }

        if (!ROUTE_ID_PATTERN.matcher(id).matches()) {
            errors.add("Route ID must contain only alphanumeric characters, hyphens, and underscores");
        }
    }

    private static void validateUri(String uri, List<String> errors) {
        if (uri == null || uri.trim().isEmpty()) {
            errors.add("URI is required");
            return;
        }

        try {
            URI parsedUri = new URI(uri);
            String scheme = parsedUri.getScheme();

            if (scheme == null || !VALID_SCHEMES.contains(scheme.toLowerCase())) {
                errors.add("URI scheme must be one of: " + String.join(", ", VALID_SCHEMES) +
                        ". Got: " + (scheme != null ? scheme : "null"));
            }

            // For static:// protocol, validate host
            if ("static".equalsIgnoreCase(scheme)) {
                String host = parsedUri.getHost();
                if (host == null || host.trim().isEmpty()) {
                    errors.add("Static URI must have a service name (e.g., static://my-service)");
                }
            }

            // For http(s):// protocol, validate host and port
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                String host = parsedUri.getHost();
                if (host == null || host.trim().isEmpty()) {
                    errors.add("HTTP/HTTPS URI must have a valid host");
                }

                int port = parsedUri.getPort();
                if (port != -1 && (port < 1 || port > 65535)) {
                    errors.add("Port must be between 1 and 65535");
                }
            }

            // For lb:// protocol, validate service name
            if ("lb".equalsIgnoreCase(scheme)) {
                String host = parsedUri.getHost();
                if (host == null || host.trim().isEmpty()) {
                    errors.add("Load balancer URI must have a service name (e.g., lb://my-service)");
                }
            }

        } catch (URISyntaxException e) {
            errors.add("Invalid URI format: " + e.getMessage());
        }
    }

    private static void validateOrder(int order, List<String> errors) {
        // Order is typically between -1000 and 1000 in Spring Cloud Gateway
        if (order < -10000 || order > 10000) {
            errors.add("Route order should be between -10000 and 10000");
        }
    }

    private static void validatePredicates(List<RouteDefinition.PredicateDefinition> predicates, List<String> errors) {
        if (predicates == null || predicates.isEmpty()) {
            // No predicates is technically allowed (matches all requests)
            return;
        }

        boolean hasPathPredicate = false;
        for (int i = 0; i < predicates.size(); i++) {
            RouteDefinition.PredicateDefinition predicate = predicates.get(i);

            if (predicate == null) {
                errors.add("Predicate at index " + i + " is null");
                continue;
            }

            String name = predicate.getName();
            if (name == null || name.trim().isEmpty()) {
                errors.add("Predicate at index " + i + " has no name");
                continue;
            }

            if (!PREDICATE_NAME_PATTERN.matcher(name).matches()) {
                errors.add("Predicate name '" + name + "' contains invalid characters");
            }

            // Warn about unknown predicates (but don't error, custom predicates exist)
            if (!VALID_PREDICATES.contains(name)) {
                log.debug("Predicate '{}' is not a standard Spring Cloud Gateway predicate", name);
            }

            // Validate args
            Map<String, String> args = predicate.getArgs();
            if (args == null || args.isEmpty()) {
                errors.add("Predicate '" + name + "' has no arguments");
            }

            // Track if we have a Path predicate
            if ("Path".equalsIgnoreCase(name)) {
                hasPathPredicate = true;
                validatePathArgs(name, args, errors);
            }

            // Validate RemoteAddr predicate
            if ("RemoteAddr".equalsIgnoreCase(name)) {
                validateRemoteAddrArgs(args, errors);
            }
        }

        // Recommend having a Path predicate
        if (!hasPathPredicate && !predicates.isEmpty()) {
            log.info("Consider adding a Path predicate for better route matching");
        }
    }

    private static void validatePathArgs(String predicateName, Map<String, String> args, List<String> errors) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.trim().isEmpty()) {
            // Try "patterns" for multi-pattern support
            pattern = args.get("patterns");
        }

        if (pattern != null && !pattern.trim().isEmpty()) {
            // Basic path pattern validation
            if (!pattern.startsWith("/")) {
                errors.add("Path pattern must start with '/': " + pattern);
            }
        }
    }

    private static void validateRemoteAddrArgs(Map<String, String> args, List<String> errors) {
        String sources = args.get("sources");
        if (sources == null || sources.trim().isEmpty()) {
            errors.add("RemoteAddr predicate requires 'sources' argument");
        }
    }

    private static void validateFilters(List<RouteDefinition.FilterDefinition> filters, List<String> errors) {
        if (filters == null) {
            return;
        }

        for (int i = 0; i < filters.size(); i++) {
            RouteDefinition.FilterDefinition filter = filters.get(i);

            if (filter == null) {
                errors.add("Filter at index " + i + " is null");
                continue;
            }

            String name = filter.getName();
            if (name == null || name.trim().isEmpty()) {
                errors.add("Filter at index " + i + " has no name");
                continue;
            }

            if (!FILTER_NAME_PATTERN.matcher(name).matches()) {
                errors.add("Filter name '" + name + "' contains invalid characters");
            }

            // Warn about unknown filters
            if (!VALID_FILTERS.contains(name)) {
                log.debug("Filter '{}' is not a standard Spring Cloud Gateway filter", name);
            }

            // Validate specific filters
            if ("StripPrefix".equalsIgnoreCase(name)) {
                validateStripPrefixFilter(filter.getArgs(), errors);
            }

            if ("SetStatus".equalsIgnoreCase(name)) {
                validateSetStatusFilter(filter.getArgs(), errors);
            }
        }
    }

    private static void validateStripPrefixFilter(Map<String, String> args, List<String> errors) {
        String parts = args.get("parts");
        if (parts != null) {
            try {
                int value = Integer.parseInt(parts);
                if (value < 0) {
                    errors.add("StripPrefix 'parts' must be non-negative");
                }
            } catch (NumberFormatException e) {
                errors.add("StripPrefix 'parts' must be a valid integer");
            }
        }
    }

    private static void validateSetStatusFilter(Map<String, String> args, List<String> errors) {
        String status = args.get("status");
        if (status != null) {
            try {
                int code = Integer.parseInt(status);
                if (code < 100 || code > 599) {
                    errors.add("SetStatus 'status' must be a valid HTTP status code (100-599)");
                }
            } catch (NumberFormatException e) {
                // Could be a status string like "OK"
                if (!status.matches("^[A-Z_]+$")) {
                    errors.add("SetStatus 'status' must be a valid HTTP status code or status name");
                }
            }
        }
    }
}