package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.controller.RouteController;
import com.leoli.gateway.admin.converter.RouteConverter;
import com.leoli.gateway.admin.license.LicenseFeatureHelper;
import com.leoli.gateway.admin.metrics.BusinessMetrics;
import com.leoli.gateway.admin.metrics.TracingHelper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.AiRouteProjector;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RoutePluginBindingService;
import com.leoli.gateway.admin.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises frontend-generated JSON through real MVC binding, service and converter.
 * DB and Nacos are in-memory test doubles; AI projection has its own dedicated tests. */
class RouteFrontendContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RouteEntity> stored = new HashMap<>();
    private final Map<String, JsonNode> published = new HashMap<>();
    private MockMvc mvc;
    private JsonNode fixtures;

    @BeforeEach
    void setUp() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/routes.json")) {
            fixtures = mapper.readTree(input);
        }
        RouteRepository repository = mock(RouteRepository.class);
        when(repository.save(any())).thenAnswer(call -> {
            RouteEntity entity = call.getArgument(0);
            stored.put(entity.getRouteId(), entity);
            return entity;
        });
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.findByEnabledTrue()).thenAnswer(call -> stored.values().stream().filter(RouteEntity::getEnabled).toList());
        when(repository.findAll()).thenAnswer(call -> List.copyOf(stored.values()));

        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setInstanceId("instance-contract");
        instance.setNacosNamespace("gateway-config");
        GatewayInstanceRepository instances = mock(GatewayInstanceRepository.class);
        when(instances.findByInstanceId("instance-contract")).thenReturn(Optional.of(instance));
        when(instances.findByInstanceIdIn(any())).thenReturn(List.of(instance));

        ConfigCenterService configCenter = mock(ConfigCenterService.class);
        when(configCenter.publishConfig(anyString(), anyString(), any())).thenAnswer(call -> {
            assertEquals("gateway-config", call.getArgument(1));
            published.put(call.getArgument(0), mapper.valueToTree(call.getArgument(2)));
            return true;
        });

        RouteConverter converter = new RouteConverter();
        ReflectionTestUtils.setField(converter, "objectMapper", mapper);
        RouteService service = new RouteService();
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
        ReflectionTestUtils.setField(service, "routeRepository", repository);
        ReflectionTestUtils.setField(service, "routeConverter", converter);
        ReflectionTestUtils.setField(service, "gatewayInstanceRepository", instances);
        ReflectionTestUtils.setField(service, "configCenterService", configCenter);
        ReflectionTestUtils.setField(service, "routePluginBindingService", mock(RoutePluginBindingService.class));
        ReflectionTestUtils.setField(service, "aiRouteProjector", mock(AiRouteProjector.class));
        ReflectionTestUtils.setField(service, "businessMetrics", mock(BusinessMetrics.class));
        ReflectionTestUtils.setField(service, "tracingHelper", mock(TracingHelper.class));
        LicenseFeatureHelper license = mock(LicenseFeatureHelper.class);
        when(license.hasFeature(any())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new RouteController(service, mock(AuditLogService.class), mapper, license)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"discovery", "publicDiscovery", "static", "multi", "ai"})
    void frontendPayloadSurvivesCreateReadAndUpdate(String kind) throws Exception {
        ObjectNode body = fixtures.get(kind).deepCopy();
        JsonNode created = create(body);
        String id = created.path("id").asText();
        assertFalse(id.isBlank());
        assertSubmittedFields(body, created);
        assertSubmittedFields(body, mapper.readTree(stored.get(id).getMetadata()));
        assertEquals("instance-contract", stored.get(id).getInstanceId());
        assertEquals(body.path("description").asText(), stored.get(id).getDescription());
        assertSubmittedFields(body, read(id));
        assertPublished(body, id);

        body.put("order", -15);
        body.put("description", "编辑后的描述");
        update(id, body);
        assertSubmittedFields(body, read(id));
        assertPublished(body, id);
        JsonNode listed = mapper.readTree(mvc.perform(get("/api/routes")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("data");
        assertEquals(1, listed.size());
        assertSubmittedFields(body, listed.get(0));
    }

    @Test
    void canMoveDiscoveryTargetBackToPublicNamespace() throws Exception {
        String id = create(fixtures.get("discovery")).path("id").asText();
        update(id, fixtures.get("publicDiscovery"));
        assertEquals("", read(id).path("serviceNamespace").asText());
        assertEquals("DEFAULT_GROUP", read(id).path("serviceGroup").asText());
        assertPublished(fixtures.get("publicDiscovery"), id);
    }

    @Test
    void changingTargetDoesNotInheritOldDiscoveryQualifiers() throws Exception {
        String id = create(fixtures.get("discovery")).path("id").asText();
        ObjectNode body = fixtures.get("publicDiscovery").deepCopy();
        body.put("uri", "lb://payments");
        body.put("serviceId", "payments");
        body.remove(List.of("serviceNamespace", "serviceGroup"));
        update(id, body);
        assertTrue(read(id).path("serviceNamespace").isNull());
        assertTrue(read(id).path("serviceGroup").isNull());
    }

    @Test
    void legacyEditOfSameTargetPreservesOmittedQualifiers() throws Exception {
        String id = create(fixtures.get("discovery")).path("id").asText();
        ObjectNode body = fixtures.get("discovery").deepCopy();
        body.remove(List.of("serviceNamespace", "serviceGroup"));
        update(id, body);
        assertEquals("staging", read(id).path("serviceNamespace").asText());
        assertEquals("BLUE", read(id).path("serviceGroup").asText());
    }

    private JsonNode create(JsonNode body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/routes").param("instanceId", "instance-contract")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    @Test
    void invalidUpdateReturnsBadRequestWithoutSavingOrPublishing() throws Exception {
        String id = create(fixtures.get("discovery")).path("id").asText();
        String original = stored.get(id).getMetadata();
        ObjectNode body = fixtures.get("discovery").deepCopy();
        body.put("order", 10001);
        mvc.perform(put("/api/routes/" + id).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        assertEquals(original, stored.get(id).getMetadata());
        assertEquals(12, published.get("config.gateway.route-" + id).path("order").asInt());
    }

    @Test
    void missingRouteUpdateReturnsNotFound() throws Exception {
        mvc.perform(put("/api/routes/00000000-0000-0000-0000-000000000000")
                .contentType(MediaType.APPLICATION_JSON).content(fixtures.get("discovery").toString()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Path", "Host", "Header", "RemoteAddr"})
    void nullPredicateArgumentsReturnBadRequest(String name) throws Exception {
        ObjectNode body = fixtures.get("discovery").deepCopy();
        ObjectNode predicate = (ObjectNode) body.path("predicates").get(0);
        predicate.put("name", name);
        predicate.putNull("args");
        mvc.perform(post("/api/routes").param("instanceId", "instance-contract")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        assertTrue(stored.isEmpty());
        assertTrue(published.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"StripPrefix", "SetStatus"})
    void nullFilterArgumentsReturnBadRequest(String name) throws Exception {
        ObjectNode body = fixtures.get("discovery").deepCopy();
        ObjectNode filter = body.putArray("filters").addObject();
        filter.put("name", name);
        filter.putNull("args");
        mvc.perform(post("/api/routes").param("instanceId", "instance-contract")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        assertTrue(stored.isEmpty());
        assertTrue(published.isEmpty());
    }

    private void update(String id, JsonNode body) throws Exception {
        mvc.perform(put("/api/routes/" + id).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());
    }

    private JsonNode read(String id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/routes/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private void assertSubmittedFields(JsonNode body, JsonNode actual) {
        assertNotNull(actual);
        if (body.isObject()) {
            body.fields().forEachRemaining(field -> assertSubmittedFields(field.getValue(), actual.path(field.getKey())));
        } else if (body.isArray()) {
            assertEquals(body.size(), actual.size());
            for (int i = 0; i < body.size(); i++) assertSubmittedFields(body.get(i), actual.path(i));
        } else {
            assertEquals(body, actual);
        }
    }

    private void assertPublished(JsonNode body, String id) {
        JsonNode config = published.get("config.gateway.route-" + id);
        assertNotNull(config);
        // AI upstreams are projected separately into an AI_PROXY strategy by AiRouteProjector.
        for (String key : List.of("uri", "mode", "serviceId", "serviceNamespace", "serviceGroup", "services",
                "grayRules", "order", "predicates", "filters", "metadata")) {
            if (body.has(key)) assertSubmittedFields(body.get(key), config.path(key));
        }
        assertEquals(id, config.path("id").asText());
    }
}
