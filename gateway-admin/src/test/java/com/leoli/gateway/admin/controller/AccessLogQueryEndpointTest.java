package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.AccessLogEntryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AccessLogQueryEndpointTest {
    @Mock
    private AccessLogEntryQueryService queryService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AccessLogConfigController(null, null, null, queryService)).build();
    }

    @Test
    void entriesReturnTheServicePayloadUnchanged() throws Exception {
        Map<String, Object> data = Map.of(
                "entries", List.of(), "total", 0, "page", 0, "size", 100,
                "file", "access-2026-10-25.log");
        when(queryService.getLogEntries(null, 0, 100, null, null, null, null)).thenReturn(data);

        mvc.perform(get("/api/access-log/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.file").value("access-2026-10-25.log"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void invalidDateStaysHttp400WithTheResolverMessage() throws Exception {
        when(queryService.getLogEntries(any(), anyInt(), anyInt(), any(), any(), any(), any()))
                .thenThrow(new AccessLogEntryQueryService.InvalidLogDateException(
                        "Invalid date parameter, expected yyyy-MM-dd"));

        mvc.perform(get("/api/access-log/entries").param("date", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid date parameter, expected yyyy-MM-dd"));
    }

    @Test
    void kubernetesFailuresStayHttp500() throws Exception {
        when(queryService.getK8sLogEntries(any(), any(), any(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("cluster down"));

        mvc.perform(get("/api/access-log/k8s/entries")
                        .param("clusterId", "1").param("namespace", "ns").param("podName", "pod"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to read logs from Pod: cluster down"));
    }
}