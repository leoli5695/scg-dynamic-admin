package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.ConfigTemplate;
import com.leoli.gateway.admin.service.ConfigTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ConfigTemplateController.
 */
@WebMvcTest(ConfigTemplateController.class)
class ConfigTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConfigTemplateService templateService;

    private ConfigTemplate testTemplate;

    @BeforeEach
    void setUp() {
        testTemplate = new ConfigTemplate();
        testTemplate.setId(1L);
        testTemplate.setName("Rate Limited API Route");
        testTemplate.setDescription("A route configuration with rate limiting");
        testTemplate.setCategory("route");
        testTemplate.setSubcategory("rate-limit");
        testTemplate.setConfigType("yaml");
        testTemplate.setConfigContent("spring:\n  cloud:\n    gateway:\n      routes:\n        - id: {{routeId}}");
        testTemplate.setAuthor("admin");
        testTemplate.setIsOfficial(true);
        testTemplate.setDownloadCount(100);
        testTemplate.setLikeCount(50);
        testTemplate.setRating(4.5);
    }

    @Test
    @DisplayName("GET /api/templates - should return all public templates")
    void getAllTemplates_shouldReturnTemplates() throws Exception {
        List<ConfigTemplate> templates = Arrays.asList(testTemplate);
        when(templateService.getAllPublicTemplates()).thenReturn(templates);

        mockMvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Rate Limited API Route"));

        verify(templateService).getAllPublicTemplates();
    }

    @Test
    @DisplayName("GET /api/templates/category/{category} - should return templates by category")
    void getTemplatesByCategory_shouldReturnTemplates() throws Exception {
        List<ConfigTemplate> templates = Arrays.asList(testTemplate);
        when(templateService.getTemplatesByCategory("route")).thenReturn(templates);

        mockMvc.perform(get("/api/templates/category/route"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("route"));

        verify(templateService).getTemplatesByCategory("route");
    }

    @Test
    @DisplayName("GET /api/templates/popular - should return popular templates")
    void getPopularTemplates_shouldReturnTemplates() throws Exception {
        List<ConfigTemplate> templates = Arrays.asList(testTemplate);
        when(templateService.getPopularTemplates(10)).thenReturn(templates);

        mockMvc.perform(get("/api/templates/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].downloadCount").value(100));

        verify(templateService).getPopularTemplates(10);
    }

    @Test
    @DisplayName("GET /api/templates/top-rated - should return top rated templates")
    void getTopRatedTemplates_shouldReturnTemplates() throws Exception {
        List<ConfigTemplate> templates = Arrays.asList(testTemplate);
        when(templateService.getTopRatedTemplates(10)).thenReturn(templates);

        mockMvc.perform(get("/api/templates/top-rated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rating").value(4.5));

        verify(templateService).getTopRatedTemplates(10);
    }

    @Test
    @DisplayName("GET /api/templates/search - should search templates")
    void searchTemplates_shouldReturnTemplates() throws Exception {
        List<ConfigTemplate> templates = Arrays.asList(testTemplate);
        when(templateService.searchTemplates("rate")).thenReturn(templates);

        mockMvc.perform(get("/api/templates/search")
                        .param("keyword", "rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Rate Limited API Route"));

        verify(templateService).searchTemplates("rate");
    }

    @Test
    @DisplayName("GET /api/templates/filter - should filter templates")
    void filterTemplates_shouldReturnTemplates() throws Exception {
        List<ConfigTemplate> templates = Arrays.asList(testTemplate);
        when(templateService.filterTemplates("route", "rate-limit", true)).thenReturn(templates);

        mockMvc.perform(get("/api/templates/filter")
                        .param("category", "route")
                        .param("subcategory", "rate-limit")
                        .param("officialOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("route"));

        verify(templateService).filterTemplates("route", "rate-limit", true);
    }

    @Test
    @DisplayName("GET /api/templates/{id} - should return template by ID")
    void getTemplateById_shouldReturnTemplate() throws Exception {
        when(templateService.getTemplateById(1L)).thenReturn(testTemplate);

        mockMvc.perform(get("/api/templates/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Rate Limited API Route"));

        verify(templateService).getTemplateById(1L);
    }

    @Test
    @DisplayName("GET /api/templates/{id}/preview - should return preview")
    void previewTemplate_shouldReturnPreview() throws Exception {
        ConfigTemplateService.PreviewResult result = new ConfigTemplateService.PreviewResult();
        result.setTemplate(testTemplate);
        result.setPreview("spring:\n  cloud:\n    gateway:\n      routes:\n        - id: example-route");
        Map<String, String> sampleValues = new HashMap<>();
        sampleValues.put("routeId", "example-route");
        result.setSampleValues(sampleValues);

        when(templateService.previewTemplate(1L)).thenReturn(result);

        mockMvc.perform(get("/api/templates/1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.id").value(1))
                .andExpect(jsonPath("$.preview").exists())
                .andExpect(jsonPath("$.sampleValues.routeId").value("example-route"));

        verify(templateService).previewTemplate(1L);
    }

    @Test
    @DisplayName("POST /api/templates/{id}/apply - should apply template successfully")
    void applyTemplate_shouldApplySuccessfully() throws Exception {
        ConfigTemplateService.AppliedTemplate applied = new ConfigTemplateService.AppliedTemplate();
        applied.setSuccess(true);
        applied.setConfig("spring:\n  cloud:\n    gateway:\n      routes:\n        - id: my-route");
        applied.setTemplate(testTemplate);

        when(templateService.applyTemplate(eq(1L), anyMap())).thenReturn(applied);

        Map<String, String> variables = new HashMap<>();
        variables.put("routeId", "my-route");

        mockMvc.perform(post("/api/templates/1/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(variables)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.config").exists());

        verify(templateService).applyTemplate(eq(1L), anyMap());
    }

    @Test
    @DisplayName("POST /api/templates/{id}/apply - should return error when missing variables")
    void applyTemplate_shouldReturnError_whenMissingVariables() throws Exception {
        ConfigTemplateService.AppliedTemplate applied = new ConfigTemplateService.AppliedTemplate();
        applied.setSuccess(false);
        applied.setError("Missing required variable: routeId");

        when(templateService.applyTemplate(eq(1L), anyMap())).thenReturn(applied);

        Map<String, String> variables = new HashMap<>();

        mockMvc.perform(post("/api/templates/1/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(variables)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Missing required variable: routeId"));
    }

    @Test
    @DisplayName("GET /api/templates/categories - should return all categories")
    void getAllCategories_shouldReturnCategories() throws Exception {
        List<String> categories = Arrays.asList("route", "strategy", "filter");
        when(templateService.getAllCategories()).thenReturn(categories);

        mockMvc.perform(get("/api/templates/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("route"))
                .andExpect(jsonPath("$[1]").value("strategy"))
                .andExpect(jsonPath("$[2]").value("filter"));

        verify(templateService).getAllCategories();
    }

    @Test
    @DisplayName("GET /api/templates/categories/{category}/subcategories - should return subcategories")
    void getSubcategories_shouldReturnSubcategories() throws Exception {
        List<String> subcategories = Arrays.asList("rate-limit", "circuit-breaker");
        when(templateService.getSubcategories("strategy")).thenReturn(subcategories);

        mockMvc.perform(get("/api/templates/categories/strategy/subcategories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("rate-limit"))
                .andExpect(jsonPath("$[1]").value("circuit-breaker"));

        verify(templateService).getSubcategories("strategy");
    }

    @Test
    @DisplayName("POST /api/templates - should create template")
    void createTemplate_shouldCreateTemplate() throws Exception {
        when(templateService.createTemplate(any(ConfigTemplate.class))).thenReturn(testTemplate);

        mockMvc.perform(post("/api/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testTemplate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Rate Limited API Route"));

        verify(templateService).createTemplate(any(ConfigTemplate.class));
    }

    @Test
    @DisplayName("PUT /api/templates/{id} - should update template")
    void updateTemplate_shouldUpdateTemplate() throws Exception {
        testTemplate.setName("Updated Name");
        when(templateService.updateTemplate(eq(1L), any(ConfigTemplate.class))).thenReturn(testTemplate);

        mockMvc.perform(put("/api/templates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testTemplate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));

        verify(templateService).updateTemplate(eq(1L), any(ConfigTemplate.class));
    }

    @Test
    @DisplayName("DELETE /api/templates/{id} - should delete template")
    void deleteTemplate_shouldDeleteTemplate() throws Exception {
        doNothing().when(templateService).deleteTemplate(1L);

        mockMvc.perform(delete("/api/templates/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Template deleted"));

        verify(templateService).deleteTemplate(1L);
    }

    @Test
    @DisplayName("POST /api/templates/{id}/like - should like template")
    void likeTemplate_shouldLikeTemplate() throws Exception {
        doNothing().when(templateService).likeTemplate(1L);

        mockMvc.perform(post("/api/templates/1/like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Template liked"));

        verify(templateService).likeTemplate(1L);
    }

    @Test
    @DisplayName("POST /api/templates/{id}/rate - should rate template")
    void rateTemplate_shouldRateTemplate() throws Exception {
        doNothing().when(templateService).rateTemplate(1L, 4.5);

        mockMvc.perform(post("/api/templates/1/rate")
                        .param("rating", "4.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Template rated"));

        verify(templateService).rateTemplate(1L, 4.5);
    }

    @Test
    @DisplayName("POST /api/templates/init - should initialize default templates")
    void initializeTemplates_shouldInitializeTemplates() throws Exception {
        doNothing().when(templateService).initializeDefaultTemplates();

        mockMvc.perform(post("/api/templates/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Default templates initialized"));

        verify(templateService).initializeDefaultTemplates();
    }
}