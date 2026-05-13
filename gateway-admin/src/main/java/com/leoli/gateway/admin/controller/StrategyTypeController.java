package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.StrategyTypeEntity;
import com.leoli.gateway.admin.service.StrategyTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Strategy type management controller.
 * Provides strategy type metadata for dynamic form rendering.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy-types")
@RequiredArgsConstructor
public class StrategyTypeController extends BaseController {

    private final StrategyTypeService strategyTypeService;

    /**
     * Get all strategy types (including disabled ones for admin).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllStrategyTypes() {
        List<StrategyTypeEntity> types = strategyTypeService.getAllStrategyTypes();
        List<Map<String, Object>> dtoList = types.stream()
                .map(strategyTypeService::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtoList));
    }

    /**
     * Get all enabled strategy types (for UI dropdown/form).
     */
    @GetMapping("/enabled")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllEnabledStrategyTypes() {
        List<StrategyTypeEntity> types = strategyTypeService.getAllEnabledStrategyTypes();
        List<Map<String, Object>> dtoList = types.stream()
                .map(strategyTypeService::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtoList));
    }

    /**
     * Get strategy type by code.
     */
    @GetMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStrategyType(@PathVariable String typeCode) {
        StrategyTypeEntity entity = strategyTypeService.getStrategyType(typeCode);
        if (entity == null) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("Strategy type not found: " + typeCode));
        }
        return ResponseEntity.ok(ApiResponse.success(strategyTypeService.toDto(entity)));
    }

    /**
     * Get full config schema for a strategy type.
     * This endpoint is specifically for form rendering.
     */
    @GetMapping("/{typeCode}/schema")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfigSchema(@PathVariable String typeCode) {
        Map<String, Object> schema = strategyTypeService.getFullConfigSchema(typeCode);
        if (schema == null) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("Strategy type or schema not found: " + typeCode));
        }
        return ResponseEntity.ok(ApiResponse.success(schema));
    }

    /**
     * Get strategy types by category.
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStrategyTypesByCategory(@PathVariable String category) {
        List<StrategyTypeEntity> types = strategyTypeService.getStrategyTypesByCategory(category);
        List<Map<String, Object>> dtoList = types.stream()
                .map(strategyTypeService::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtoList));
    }

    /**
     * Get all categories (for UI grouping).
     */
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<String>>> getAllCategories() {
        List<String> categories = strategyTypeService.getAllCategories();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }
}