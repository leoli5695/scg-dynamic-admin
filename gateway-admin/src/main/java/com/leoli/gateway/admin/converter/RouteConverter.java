package com.leoli.gateway.admin.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Converter between RouteDefinition and RouteEntity.
 *
 * Simplified design:
 * - RouteEntity.routeId = RouteDefinition.id (UUID, primary key)
 * - RouteEntity.routeName = RouteDefinition.routeName (business name)
 * - RouteEntity.metadata = complete JSON config
 *
 * @author leoli
 */
@Slf4j
@Component
public class RouteConverter {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Convert RouteDefinition to RouteEntity for new route creation.
     * Generates new UUID for routeId.
     */
    public RouteEntity toEntity(RouteDefinition route) {
        if (route == null) {
            return null;
        }

        RouteEntity entity = new RouteEntity();

        // Use existing id if provided, otherwise generate new UUID
        if (route.getId() != null && !route.getId().isEmpty()) {
            entity.setRouteId(route.getId());
        } else {
            entity.setRouteId(java.util.UUID.randomUUID().toString());
        }

        entity.setRouteName(route.getRouteName());
        entity.setEnabled(true);
        entity.setDescription(route.getDescription());

        // Store complete configuration in metadata
        try {
            entity.setMetadata(objectMapper.writeValueAsString(route));
        } catch (Exception e) {
            log.warn("Failed to serialize route config to JSON", e);
        }

        return entity;
    }

    /**
     * Convert RouteDefinition to RouteEntity for update.
     * Uses existing routeId from entity.
     */
    public RouteEntity toEntity(RouteDefinition route, RouteEntity existingEntity) {
        if (route == null || existingEntity == null) {
            return null;
        }

        // Keep the same routeId (primary key doesn't change)
        existingEntity.setRouteName(route.getRouteName());
        existingEntity.setDescription(route.getDescription());

        // Store complete configuration in metadata
        try {
            existingEntity.setMetadata(objectMapper.writeValueAsString(route));
        } catch (Exception e) {
            log.warn("Failed to serialize route config to JSON", e);
        }

        return existingEntity;
    }

    /**
     * Convert RouteEntity to RouteDefinition.
     * Restores complete configuration from metadata JSON.
     */
    public RouteDefinition toDefinition(RouteEntity entity) {
        if (entity == null) {
            return null;
        }

        // Try to restore from metadata JSON first
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                RouteDefinition route = objectMapper.readValue(entity.getMetadata(), RouteDefinition.class);
                if (route != null) {
                    // Ensure id matches entity's routeId
                    route.setId(entity.getRouteId());
                    route.setRouteName(entity.getRouteName());
                    return route;
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize route config from JSON, using fallback", e);
            }
        }

        // Fallback: create minimal definition
        RouteDefinition route = new RouteDefinition();
        route.setId(entity.getRouteId());
        route.setRouteName(entity.getRouteName());
        return route;
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
}