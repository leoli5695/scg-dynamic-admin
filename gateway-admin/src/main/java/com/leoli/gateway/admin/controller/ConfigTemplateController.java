package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.ConfigTemplate;
import com.leoli.gateway.admin.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Configuration Template Controller.
 * REST endpoints for template marketplace.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class ConfigTemplateController {

    private final ConfigTemplateService templateService;

    /**
     * Get all public templates.
     */
    @GetMapping
    public ResponseEntity<List<ConfigTemplate>> getAllTemplates() {
        return ResponseEntity.ok(templateService.getAllPublicTemplates());
    }

    /**
     * Get templates by category.
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<ConfigTemplate>> getTemplatesByCategory(@PathVariable String category) {
        return ResponseEntity.ok(templateService.getTemplatesByCategory(category));
    }

    /**
     * Get popular templates.
     */
    @GetMapping("/popular")
    public ResponseEntity<List<ConfigTemplate>> getPopularTemplates(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(templateService.getPopularTemplates(limit));
    }

    /**
     * Get top rated templates.
     */
    @GetMapping("/top-rated")
    public ResponseEntity<List<ConfigTemplate>> getTopRatedTemplates(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(templateService.getTopRatedTemplates(limit));
    }

    /**
     * Search templates.
     */
    @GetMapping("/search")
    public ResponseEntity<List<ConfigTemplate>> searchTemplates(@RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(templateService.searchTemplates(keyword));
    }

    /**
     * Filter templates.
     */
    @GetMapping("/filter")
    public ResponseEntity<List<ConfigTemplate>> filterTemplates(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subcategory,
            @RequestParam(required = false) Boolean officialOnly) {
        return ResponseEntity.ok(templateService.filterTemplates(category, subcategory, officialOnly));
    }

    /**
     * Get template by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ConfigTemplate> getTemplateById(@PathVariable Long id) {
        return ResponseEntity.ok(templateService.getTemplateById(id));
    }

    /**
     * Preview template with sample values.
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> previewTemplate(@PathVariable Long id) {
        ConfigTemplateService.PreviewResult result = templateService.previewTemplate(id);

        return ResponseEntity.ok(Map.of(
                "template", result.getTemplate(),
                "preview", result.getPreview(),
                "sampleValues", result.getSampleValues()
        ));
    }

    /**
     * Apply template with variables.
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(
            @PathVariable Long id,
            @RequestBody Map<String, String> variables) {

        ConfigTemplateService.AppliedTemplate result = templateService.applyTemplate(id, variables);

        if (result.isSuccess()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "config", result.getConfig(),
                    "template", result.getTemplate()
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", result.getError()
            ));
        }
    }

    /**
     * Get all categories.
     */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getAllCategories() {
        return ResponseEntity.ok(templateService.getAllCategories());
    }

    /**
     * Get subcategories for a category.
     */
    @GetMapping("/categories/{category}/subcategories")
    public ResponseEntity<List<String>> getSubcategories(@PathVariable String category) {
        return ResponseEntity.ok(templateService.getSubcategories(category));
    }

    /**
     * Create a new template.
     */
    @PostMapping
    public ResponseEntity<ConfigTemplate> createTemplate(@RequestBody ConfigTemplate template) {
        return ResponseEntity.ok(templateService.createTemplate(template));
    }

    /**
     * Update a template.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ConfigTemplate> updateTemplate(
            @PathVariable Long id,
            @RequestBody ConfigTemplate updates) {
        return ResponseEntity.ok(templateService.updateTemplate(id, updates));
    }

    /**
     * Delete a template.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Template deleted"));
    }

    /**
     * Like a template.
     */
    @PostMapping("/{id}/like")
    public ResponseEntity<Map<String, Object>> likeTemplate(@PathVariable Long id) {
        templateService.likeTemplate(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Template liked"));
    }

    /**
     * Rate a template.
     */
    @PostMapping("/{id}/rate")
    public ResponseEntity<Map<String, Object>> rateTemplate(
            @PathVariable Long id,
            @RequestParam double rating) {
        templateService.rateTemplate(id, rating);
        return ResponseEntity.ok(Map.of("success", true, "message", "Template rated"));
    }

    /**
     * Initialize default templates.
     */
    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initializeTemplates() {
        templateService.initializeDefaultTemplates();
        return ResponseEntity.ok(Map.of("success", true, "message", "Default templates initialized"));
    }
}