package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Base integration test class with common utilities.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ConfigCenterService configCenterService;

    /**
     * Extract data from API response
     */
    protected JsonNode extractData(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("data");
    }

    /**
     * Extract code from API response
     */
    protected int extractCode(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("code").asInt();
    }

    /**
     * Extract message from API response
     */
    protected String extractMessage(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("message").asText();
    }

    /**
     * Get config from ConfigCenter (Nacos/Consul).
     * Uses ConfigCenterService instead of direct HTTP call.
     */
    protected String getConfigFromCenter(String dataId) {
        try {
            return configCenterService.getConfig(dataId, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verify config exists in ConfigCenter (Nacos/Consul).
     * Uses ConfigCenterService instead of direct HTTP call.
     */
    protected boolean configCenterExists(String dataId) {
        try {
            return configCenterService.configExists(dataId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clean all test data
     */
    protected void cleanAllData() throws Exception {
        // Delete all routes
        MvcResult routesResult = mockMvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode routes = extractData(routesResult);
        if (routes.isArray()) {
            for (JsonNode route : routes) {
                String id = route.get("id").asText();
                mockMvc.perform(delete("/api/routes/" + id));
            }
        }

        // Delete all services
        MvcResult servicesResult = mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode services = extractData(servicesResult);
        if (services.isArray()) {
            for (JsonNode service : services) {
                String name = service.get("name").asText();
                mockMvc.perform(delete("/api/services/" + name));
            }
        }

        // Delete all strategies
        MvcResult strategiesResult = mockMvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode strategies = extractData(strategiesResult);
        if (strategies.isArray()) {
            for (JsonNode strategy : strategies) {
                String id = strategy.get("strategyId").asText();
                mockMvc.perform(delete("/api/strategies/" + id));
            }
        }
    }
}