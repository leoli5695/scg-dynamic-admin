package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.service.StressTestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for StressTestController.
 */
@WebMvcTest(StressTestController.class)
class StressTestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StressTestService stressTestService;

    private StressTest testStressTest;
    private StressTestService.StressTestConfig testConfig;

    @BeforeEach
    void setUp() {
        testStressTest = new StressTest();
        testStressTest.setId(1L);
        testStressTest.setInstanceId("test-instance");
        testStressTest.setTestName("Test Load");
        testStressTest.setTargetUrl("http://localhost:8080/api/test");
        testStressTest.setMethod("GET");
        testStressTest.setConcurrentUsers(10);
        testStressTest.setTotalRequests(100);
        testStressTest.setStatus("CREATED");
        testStressTest.setCreatedAt(LocalDateTime.now());

        testConfig = new StressTestService.StressTestConfig();
        testConfig.setTestName("Test Load");
        testConfig.setMethod("GET");
        testConfig.setConcurrentUsers(10);
        testConfig.setTotalRequests(100);
    }

    @Test
    @DisplayName("POST /api/stress-test/start - should start stress test successfully")
    void startTest_shouldReturnSuccess() throws Exception {
        when(stressTestService.createAndStartTest(eq("test-instance"), any(StressTestService.StressTestConfig.class)))
                .thenReturn(testStressTest);

        mockMvc.perform(post("/api/stress-test/start")
                        .param("instanceId", "test-instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testConfig)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.testId").value(1))
                .andExpect(jsonPath("$.status").value("CREATED"));

        verify(stressTestService).createAndStartTest(eq("test-instance"), any(StressTestService.StressTestConfig.class));
    }

    @Test
    @DisplayName("POST /api/stress-test/start - should return error when instance not found")
    void startTest_shouldReturnError_whenInstanceNotFound() throws Exception {
        when(stressTestService.createAndStartTest(eq("non-existent"), any(StressTestService.StressTestConfig.class)))
                .thenThrow(new RuntimeException("Instance not found: non-existent"));

        mockMvc.perform(post("/api/stress-test/start")
                        .param("instanceId", "non-existent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testConfig)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Instance not found: non-existent"));
    }

    @Test
    @DisplayName("GET /api/stress-test/{testId} - should return test by ID")
    void getTest_shouldReturnTest() throws Exception {
        when(stressTestService.getTest(1L)).thenReturn(testStressTest);

        mockMvc.perform(get("/api/stress-test/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.testName").value("Test Load"))
                .andExpect(jsonPath("$.instanceId").value("test-instance"));

        verify(stressTestService).getTest(1L);
    }

    @Test
    @DisplayName("GET /api/stress-test/instance/{instanceId} - should return tests for instance")
    void getTestsForInstance_shouldReturnTests() throws Exception {
        List<StressTest> tests = Arrays.asList(testStressTest);
        when(stressTestService.getTestsForInstance("test-instance")).thenReturn(tests);

        mockMvc.perform(get("/api/stress-test/instance/test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].testName").value("Test Load"));

        verify(stressTestService).getTestsForInstance("test-instance");
    }

    @Test
    @DisplayName("GET /api/stress-test/{testId}/status - should return test status")
    void getTestStatus_shouldReturnStatus() throws Exception {
        StressTestService.StressTestStatus status = new StressTestService.StressTestStatus();
        status.setTestId(1L);
        status.setStatus("RUNNING");
        status.setProgress(0.5);
        status.setLiveRps(100.0);

        when(stressTestService.getTestStatus(1L)).thenReturn(status);

        mockMvc.perform(get("/api/stress-test/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testId").value(1))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.progress").value(0.5))
                .andExpect(jsonPath("$.liveRps").value(100.0));

        verify(stressTestService).getTestStatus(1L);
    }

    @Test
    @DisplayName("POST /api/stress-test/{testId}/stop - should stop running test")
    void stopTest_shouldStopTest() throws Exception {
        testStressTest.setStatus("STOPPED");
        when(stressTestService.stopTest(1L)).thenReturn(testStressTest);

        mockMvc.perform(post("/api/stress-test/1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("STOPPED"));

        verify(stressTestService).stopTest(1L);
    }

    @Test
    @DisplayName("POST /api/stress-test/{testId}/stop - should return error when test not running")
    void stopTest_shouldReturnError_whenTestNotRunning() throws Exception {
        when(stressTestService.stopTest(1L)).thenThrow(new RuntimeException("Test is not running"));

        mockMvc.perform(post("/api/stress-test/1/stop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Test is not running"));
    }

    @Test
    @DisplayName("GET /api/stress-test/{testId}/analyze - should return AI analysis")
    void analyzeTestResults_shouldReturnAnalysis() throws Exception {
        when(stressTestService.analyzeTestResults(1L, "QWEN", "zh"))
                .thenReturn("Test analysis result");

        mockMvc.perform(get("/api/stress-test/1/analyze")
                        .param("provider", "QWEN")
                        .param("language", "zh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.analysis").value("Test analysis result"));

        verify(stressTestService).analyzeTestResults(1L, "QWEN", "zh");
    }

    @Test
    @DisplayName("GET /api/stress-test/{testId}/analyze - should return error when test not completed")
    void analyzeTestResults_shouldReturnError_whenTestNotCompleted() throws Exception {
        when(stressTestService.analyzeTestResults(1L, "QWEN", "zh"))
                .thenThrow(new RuntimeException("Test must be completed before analysis"));

        mockMvc.perform(get("/api/stress-test/1/analyze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Test must be completed before analysis"));
    }

    @Test
    @DisplayName("DELETE /api/stress-test/{testId} - should delete test")
    void deleteTest_shouldDeleteTest() throws Exception {
        doNothing().when(stressTestService).deleteTest(1L);

        mockMvc.perform(delete("/api/stress-test/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Test deleted"));

        verify(stressTestService).deleteTest(1L);
    }

    @Test
    @DisplayName("DELETE /api/stress-test/{testId} - should return error when deleting running test")
    void deleteTest_shouldReturnError_whenDeletingRunningTest() throws Exception {
        doThrow(new RuntimeException("Cannot delete running test")).when(stressTestService).deleteTest(1L);

        mockMvc.perform(delete("/api/stress-test/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Cannot delete running test"));
    }

    @Test
    @DisplayName("POST /api/stress-test/quick - should start quick test")
    void quickTest_shouldStartQuickTest() throws Exception {
        when(stressTestService.createAndStartTest(eq("test-instance"), any(StressTestService.StressTestConfig.class)))
                .thenReturn(testStressTest);

        mockMvc.perform(post("/api/stress-test/quick")
                        .param("instanceId", "test-instance")
                        .param("requests", "100")
                        .param("concurrent", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.testId").value(1));

        verify(stressTestService).createAndStartTest(eq("test-instance"), any(StressTestService.StressTestConfig.class));
    }

    @Test
    @DisplayName("POST /api/stress-test/quick - should use default values")
    void quickTest_shouldUseDefaultValues() throws Exception {
        when(stressTestService.createAndStartTest(eq("test-instance"), any(StressTestService.StressTestConfig.class)))
                .thenReturn(testStressTest);

        mockMvc.perform(post("/api/stress-test/quick")
                        .param("instanceId", "test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/stress-test/quick - should accept path parameter")
    void quickTest_shouldAcceptPath() throws Exception {
        when(stressTestService.createAndStartTest(eq("test-instance"), any(StressTestService.StressTestConfig.class)))
                .thenReturn(testStressTest);

        mockMvc.perform(post("/api/stress-test/quick")
                        .param("instanceId", "test-instance")
                        .param("path", "/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}