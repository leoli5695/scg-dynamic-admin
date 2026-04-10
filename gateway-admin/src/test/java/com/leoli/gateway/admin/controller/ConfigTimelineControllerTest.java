package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.ConfigTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ConfigTimelineController.
 */
@WebMvcTest(ConfigTimelineController.class)
class ConfigTimelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConfigTimelineService timelineService;

    private ConfigTimelineService.TimelineResult testTimeline;

    @BeforeEach
    void setUp() {
        List<ConfigTimelineService.TimelineEntry> entries = new ArrayList<>();
        
        ConfigTimelineService.TimelineEntry entry1 = new ConfigTimelineService.TimelineEntry();
        entry1.setId(1L);
        entry1.setOperationType("CREATE");
        entry1.setTargetType("route");
        entry1.setTargetId("user-route");
        entry1.setOperator("admin");
        entry1.setTimestamp(LocalDateTime.now());
        entry1.setSummary("Created route: user-route");
        entries.add(entry1);

        ConfigTimelineService.TimelineEntry entry2 = new ConfigTimelineService.TimelineEntry();
        entry2.setId(2L);
        entry2.setOperationType("UPDATE");
        entry2.setTargetType("strategy");
        entry2.setTargetId("rate-limit-1");
        entry2.setOperator("admin");
        entry2.setTimestamp(LocalDateTime.now().minusHours(1));
        entry2.setSummary("Updated strategy: rate-limit-1");
        entries.add(entry2);

        testTimeline = new ConfigTimelineService.TimelineResult();
        testTimeline.setEntries(entries);
        testTimeline.setTotal(2);
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId} - should return timeline")
    void getTimeline_shouldReturnTimeline() throws Exception {
        when(timelineService.getTimeline("test-instance", 7, null, 100)).thenReturn(testTimeline);

        mockMvc.perform(get("/api/timeline/test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries[0].operationType").value("CREATE"));

        verify(timelineService).getTimeline("test-instance", 7, null, 100);
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId} - should accept custom parameters")
    void getTimeline_shouldAcceptCustomParameters() throws Exception {
        when(timelineService.getTimeline("test-instance", 14, "route", 50)).thenReturn(testTimeline);

        mockMvc.perform(get("/api/timeline/test-instance")
                        .param("days", "14")
                        .param("targetType", "route")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        verify(timelineService).getTimeline("test-instance", 14, "route", 50);
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId} - should handle exception")
    void getTimeline_shouldHandleException() throws Exception {
        when(timelineService.getTimeline(anyString(), anyInt(), any(), anyInt()))
                .thenThrow(new RuntimeException("Failed to get timeline"));

        mockMvc.perform(get("/api/timeline/test-instance"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId}/target/{targetType}/{targetId} - should return target timeline")
    void getTargetTimeline_shouldReturnTimeline() throws Exception {
        when(timelineService.getTargetTimeline("test-instance", "route", "user-route", 20))
                .thenReturn(testTimeline);

        mockMvc.perform(get("/api/timeline/test-instance/target/route/user-route"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entries").isArray());

        verify(timelineService).getTargetTimeline("test-instance", "route", "user-route", 20);
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId}/target/{targetType}/{targetId} - should accept limit parameter")
    void getTargetTimeline_shouldAcceptLimit() throws Exception {
        when(timelineService.getTargetTimeline("test-instance", "route", "user-route", 10))
                .thenReturn(testTimeline);

        mockMvc.perform(get("/api/timeline/test-instance/target/route/user-route")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        verify(timelineService).getTargetTimeline("test-instance", "route", "user-route", 10);
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId}/target/{targetType}/{targetId} - should handle exception")
    void getTargetTimeline_shouldHandleException() throws Exception {
        when(timelineService.getTargetTimeline(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Failed to get target timeline"));

        mockMvc.perform(get("/api/timeline/test-instance/target/route/user-route"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/timeline/recent - should return recent changes")
    void getRecentChanges_shouldReturnChanges() throws Exception {
        when(timelineService.getRecentChanges(50)).thenReturn(testTimeline);

        mockMvc.perform(get("/api/timeline/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entries").isArray());

        verify(timelineService).getRecentChanges(50);
    }

    @Test
    @DisplayName("GET /api/timeline/recent - should accept limit parameter")
    void getRecentChanges_shouldAcceptLimit() throws Exception {
        when(timelineService.getRecentChanges(100)).thenReturn(testTimeline);

        mockMvc.perform(get("/api/timeline/recent")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        verify(timelineService).getRecentChanges(100);
    }

    @Test
    @DisplayName("GET /api/timeline/recent - should handle exception")
    void getRecentChanges_shouldHandleException() throws Exception {
        when(timelineService.getRecentChanges(anyInt()))
                .thenThrow(new RuntimeException("Failed to get recent changes"));

        mockMvc.perform(get("/api/timeline/recent"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/timeline/{instanceId} - should return empty timeline")
    void getTimeline_shouldReturnEmptyTimeline() throws Exception {
        ConfigTimelineService.TimelineResult emptyTimeline = new ConfigTimelineService.TimelineResult();
        emptyTimeline.setEntries(new ArrayList<>());
        emptyTimeline.setTotal(0);

        when(timelineService.getTimeline("test-instance", 7, null, 100)).thenReturn(emptyTimeline);

        mockMvc.perform(get("/api/timeline/test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.entries").isEmpty());
    }
}