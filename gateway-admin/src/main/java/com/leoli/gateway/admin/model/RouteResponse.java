package com.leoli.gateway.admin.model;

import lombok.Data;

import java.io.Serializable;

/**
 * Route response DTO with both route name and UUID.
 */
@Data
public class RouteResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Route ID (UUID - system identifier, used for deletion)
     */
    private String id;

    /**
     * Route name (business identifier)
     */
    private String routeName;

    /**
     * Target URI (for backward compatibility)
     */
    private String uri;

    /**
     * Routing mode: SINGLE or MULTI
     */
    private RouteDefinition.RoutingMode mode;

    /**
     * Single service ID (for SINGLE mode)
     */
    private String serviceId;

    /**
     * Service bindings for multi-service routing (for MULTI mode)
     */
    private java.util.List<RouteServiceBinding> services;

    /**
     * Gray release rules for multi-service routing
     */
    private GrayRules grayRules;

    /**
     * Route order
     */
    private int order;

    /**
     * Route predicates
     */
    private java.util.List<RouteDefinition.PredicateDefinition> predicates;

    /**
     * Route filters
     */
    private java.util.List<RouteDefinition.FilterDefinition> filters;

    /**
     * Metadata
     */
    private java.util.Map<String, Object> metadata;

    /**
     * Enabled status
     */
    private Boolean enabled;

    /**
     * Description
     */
    private String description;
}
