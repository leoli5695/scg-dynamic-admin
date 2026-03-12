package com.example.gatewayadmin.converter;

import com.example.gatewayadmin.model.RouteDefinition;
import com.example.gatewayadmin.model.RouteEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Converter between RouteDefinition and RouteEntity.
 *
 * @author leoli
 */
@Slf4j
@Component
public class RouteConverter {

    /**
     * Convert RouteDefinition to RouteEntity.
     */
    public RouteEntity toEntity(RouteDefinition route) {
        if (route == null) {
            return null;
        }

        RouteEntity entity = new RouteEntity();
        // Don't set ID - let database auto-generate it
        entity.setRouteName(route.getId());  // Use route ID as business route name
        entity.setUri(route.getUri() != null ? route.getUri().toString() : null);
        
        // Convert predicates to JSON string
        if (route.getPredicates() != null && !route.getPredicates().isEmpty()) {
            entity.setPredicates(convertToJson(route.getPredicates()));
        }
        
        // Convert filters to JSON string
        if (route.getFilters() != null && !route.getFilters().isEmpty()) {
            entity.setFilters(convertToJson(route.getFilters()));
        }
        
        // Convert metadata to JSON string
        if (route.getMetadata() != null && !route.getMetadata().isEmpty()) {
            entity.setMetadata(convertToJson(route.getMetadata()));
        }
        
        entity.setOrderNum(route.getOrder());
        entity.setEnabled(true); // Default to enabled
        entity.setDescription((route.getMetadata() != null) ? (String) route.getMetadata().get("description") : null);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        return entity;
    }

    /**
     * Convert RouteEntity to RouteDefinition.
     */
    public RouteDefinition toDefinition(RouteEntity entity) {
        if (entity == null) {
            return null;
        }

        RouteDefinition route = new RouteDefinition();
        route.setId(entity.getRouteName());  // Use routeName as the business ID
        
        if (entity.getUri() != null && !entity.getUri().isEmpty()) {
            route.setUri(entity.getUri());
        }
        
        // Convert predicates from JSON string
        if (entity.getPredicates() != null && !entity.getPredicates().isEmpty()) {
            route.setPredicates(convertFromJson(entity.getPredicates(), List.class));
        } else {
            route.setPredicates(new ArrayList<>());
        }
        
        // Convert filters from JSON string
        if (entity.getFilters() != null && !entity.getFilters().isEmpty()) {
            route.setFilters(convertFromJson(entity.getFilters(), List.class));
        } else {
            route.setFilters(new ArrayList<>());
        }
        
        // Convert metadata from JSON string
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            route.setMetadata(convertFromJson(entity.getMetadata(), java.util.Map.class));
        } else {
            route.setMetadata(new java.util.HashMap<>());
        }
        
        route.setOrder(entity.getOrderNum() != null ? entity.getOrderNum() : 0);

        return route;
    }

    /**
     * Batch convert RouteDefinitions to RouteEntities.
     */
    public List<RouteEntity> toEntities(List<RouteDefinition> routes) {
        if (routes == null) {
            return new ArrayList<>();
        }
        List<RouteEntity> entities = new ArrayList<>(routes.size());
        for (RouteDefinition route : routes) {
            entities.add(toEntity(route));
        }
        return entities;
    }

    /**
     * Batch convert RouteEntities to RouteDefinitions.
     */
    public List<RouteDefinition> toDefinitions(List<RouteEntity> entities) {
        if (entities == null) {
            return new ArrayList<>();
        }
        List<RouteDefinition> routes = new ArrayList<>(entities.size());
        for (RouteEntity entity : entities) {
            routes.add(toDefinition(entity));
        }
        return routes;
    }

    /**
     * Helper method to convert object to JSON string.
     */
    private String convertToJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error converting object to JSON", e);
            return null;
        }
    }

    /**
     * Helper method to convert JSON string to object.
     */
    @SuppressWarnings("unchecked")
    private <T> T convertFromJson(String json, Class<T> clazz) {
        try {
            if (json == null || json.isEmpty()) {
                return null;
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Error converting JSON to object", e);
            return null;
        }
    }
}
