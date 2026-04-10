package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.TrafficTopologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for TrafficTopologyController.
 */
@WebMvcTest(TrafficTopologyController.class)
class TrafficTopologyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrafficTopologyService topologyService;

    private TrafficTopologyService.TopologyGraph testGraph;
    private TrafficTopologyService.TrafficSummary testSummary;

    @BeforeEach
    void setUp() {
        // Setup test graph
        List<TrafficTopologyService.TopologyNode> nodes = new ArrayList<>();
        TrafficTopologyService.TopologyNode gatewayNode = new TrafficTopologyService.TopologyNode();
        gatewayNode.setId("gateway-1");
        gatewayNode.setType("gateway");
        gatewayNode.setName("API Gateway");
        gatewayNode.setTraffic(1000);
        nodes.add(gatewayNode);

        TrafficTopologyService.TopologyNode serviceNode = new TrafficTopologyService.TopologyNode();
        serviceNode.setId("user-service");
        serviceNode.setType("service");
        serviceNode.setName("User Service");
        serviceNode.setTraffic(500);
        nodes.add(serviceNode);

        List<TrafficTopologyService.TopologyEdge> edges = new ArrayList<>();
        TrafficTopologyService.TopologyEdge edge = new TrafficTopologyService.TopologyEdge();
        edge.setSource("gateway-1");
        edge.setTarget("user-service");
        edge.setTraffic(500);
        edge.setLatency(25.0);
        edges.add(edge);

        testGraph = new TrafficTopologyService.TopologyGraph();
        testGraph.setNodes(nodes);
        testGraph.setEdges(edges);

        // Setup test summary
        testSummary = new TrafficTopologyService.TrafficSummary();
        testSummary.setTotalRequests(1000L);
        testSummary.setTotalErrors(10L);
        testSummary.setAvgLatency(25.0);
        testSummary.setTopEndpoints(Arrays.asList("/api/users", "/api/orders"));
        testSummary.setTopServices(Arrays.asList("user-service", "order-service"));
    }

    @Test
    @DisplayName("GET /api/topology/{instanceId} - should return topology graph")
    void getTopology_shouldReturnGraph() throws Exception {
        when(topologyService.buildTopology("test-instance", 60)).thenReturn(testGraph);

        mockMvc.perform(get("/api/topology/test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.nodes[0].id").value("gateway-1"))
                .andExpect(jsonPath("$.edges").isArray())
                .andExpect(jsonPath("$.edges[0].source").value("gateway-1"));

        verify(topologyService).buildTopology("test-instance", 60);
    }

    @Test
    @DisplayName("GET /api/topology/{instanceId} - should accept custom time range")
    void getTopology_shouldAcceptCustomTimeRange() throws Exception {
        when(topologyService.buildTopology("test-instance", 30)).thenReturn(testGraph);

        mockMvc.perform(get("/api/topology/test-instance")
                        .param("minutes", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray());

        verify(topologyService).buildTopology("test-instance", 30);
    }

    @Test
    @DisplayName("GET /api/topology/{instanceId} - should handle exception")
    void getTopology_shouldHandleException() throws Exception {
        when(topologyService.buildTopology("test-instance", 60))
                .thenThrow(new RuntimeException("Failed to build topology"));

        mockMvc.perform(get("/api/topology/test-instance"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/topology/{instanceId}/summary - should return traffic summary")
    void getTrafficSummary_shouldReturnSummary() throws Exception {
        when(topologyService.getTrafficSummary("test-instance", 60)).thenReturn(testSummary);

        mockMvc.perform(get("/api/topology/test-instance/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1000))
                .andExpect(jsonPath("$.totalErrors").value(10))
                .andExpect(jsonPath("$.avgLatency").value(25.0));

        verify(topologyService).getTrafficSummary("test-instance", 60);
    }

    @Test
    @DisplayName("GET /api/topology/{instanceId}/summary - should accept custom time range")
    void getTrafficSummary_shouldAcceptCustomTimeRange() throws Exception {
        when(topologyService.getTrafficSummary("test-instance", 120)).thenReturn(testSummary);

        mockMvc.perform(get("/api/topology/test-instance/summary")
                        .param("minutes", "120"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1000));

        verify(topologyService).getTrafficSummary("test-instance", 120);
    }

    @Test
    @DisplayName("GET /api/topology/{instanceId}/summary - should handle exception")
    void getTrafficSummary_shouldHandleException() throws Exception {
        when(topologyService.getTrafficSummary("test-instance", 60))
                .thenThrow(new RuntimeException("Failed to get summary"));

        mockMvc.perform(get("/api/topology/test-instance/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }
}