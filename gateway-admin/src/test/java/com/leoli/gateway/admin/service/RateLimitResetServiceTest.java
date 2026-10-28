package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitResetServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"MULTI_DIM_RATE_LIMITER", "RATE_LIMITER"})
    void customPrefixesAndNamespacedCountersAreResetWithoutClearingOtherStrategies(String strategyType) throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.scan(any(ScanOptions.class))).thenAnswer(invocation -> mock(Cursor.class));
        var center = mock(ConfigCenterService.class);
        var instances = mock(GatewayInstanceRepository.class);
        var instance = new GatewayInstanceEntity(); instance.setNacosNamespace("gateway-test");
        when(instances.findAll()).thenReturn(List.of(instance));
        var service = new RateLimitResetService();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "configCenterService", center);
        ReflectionTestUtils.setField(service, "gatewayInstanceRepository", instances);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        var strategy = new StrategyDefinition();
        strategy.setStrategyId("s1"); strategy.setStrategyType(strategyType);
        strategy.setScope("GLOBAL"); strategy.setConfig(Map.of("keyPrefix", "custom:"));
        service.resetRateLimitCounters(strategy);
        ArgumentCaptor<ScanOptions> scans = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis, atLeastOnce()).scan(scans.capture());
        var patterns = scans.getAllValues().stream().map(ScanOptions::toOptionString).toList();
        assertTrue(patterns.stream().anyMatch(pattern -> pattern.contains("custom:s1:*")));
        assertFalse(patterns.stream().anyMatch(pattern -> pattern.contains("custom:*")));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(center).publishConfig(anyString(), eq("gateway-test"), payload.capture());
        assertEquals("custom:", new ObjectMapper().readTree(payload.getValue()).get("keyPrefix").asText());
    }
}
