package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.StrategyTypeEntity;
import com.leoli.gateway.admin.service.StrategyTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Strategy type management controller.
 * Provides strategy type metadata for dynamic form rendering.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy-types")
public class StrategyTypeController {

    @Autowired
    private StrategyTypeService strategyTypeService;

    /**
     * Get all strategy types (including disabled ones for admin).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllStrategyTypes() {
        List<StrategyTypeEntity> types = strategyTypeService.getAllStrategyTypes();
        List<Map<String, Object>> dtoList = types.stream()
                .map(strategyTypeService::toDto)
                .collect(Collectors.toList());
        return ok(dtoList);
    }

    /**
     * Get all enabled strategy types (for UI dropdown/form).
     */
    @GetMapping("/enabled")
    public ResponseEntity<Map<String, Object>> getAllEnabledStrategyTypes() {
        List<StrategyTypeEntity> types = strategyTypeService.getAllEnabledStrategyTypes();
        List<Map<String, Object>> dtoList = types.stream()
                .map(strategyTypeService::toDto)
                .collect(Collectors.toList());
        return ok(dtoList);
    }

    /**
     * Get strategy type by code.
     */
    @GetMapping("/{typeCode}")
    public ResponseEntity<Map<String, Object>> getStrategyType(@PathVariable String typeCode) {
        StrategyTypeEntity entity = strategyTypeService.getStrategyType(typeCode);
        if (entity == null) {
            return notFound("Strategy type not found: " + typeCode);
        }
        return ok(strategyTypeService.toDto(entity));
    }

    /**
     * Get full config schema for a strategy type.
     * This endpoint is specifically for form rendering.
     */
    @GetMapping("/{typeCode}/schema")
    public ResponseEntity<Map<String, Object>> getConfigSchema(@PathVariable String typeCode) {
        Map<String, Object> schema = strategyTypeService.getFullConfigSchema(typeCode);
        if (schema == null) {
            return notFound("Strategy type or schema not found: " + typeCode);
        }
        return ok(schema);
    }

    /**
     * Get strategy types by category.
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<Map<String, Object>> getStrategyTypesByCategory(@PathVariable String category) {
        List<StrategyTypeEntity> types = strategyTypeService.getStrategyTypesByCategory(category);
        List<Map<String, Object>> dtoList = types.stream()
                .map(strategyTypeService::toDto)
                .collect(Collectors.toList());
        return ok(dtoList);
    }

    /**
     * Get all categories (for UI grouping).
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> getAllCategories() {
        List<String> categories = strategyTypeService.getAllCategories();
        return ok(categories);
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", message);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 404);
        result.put("message", message);
        return ResponseEntity.status(404).body(result);
    }
}