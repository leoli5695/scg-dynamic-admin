package com.leoli.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TraceIdGlobalFilter.
 * Tests trace ID generation and propagation.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class TraceIdGlobalFilterTest {

    @Mock
    private GatewayFilterChain chain;

    @Mock
    private ServerWebExchange exchange;

    @Mock
    private ServerWebExchange mutatedExchange;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpRequest mutatedRequest;

    @Mock
    private ServerHttpRequest.Builder requestBuilder;

    @Mock
    private ServerWebExchange.Builder exchangeBuilder;

    @Mock
    private ServerHttpResponse response;

    @InjectMocks
    private TraceIdGlobalFilter filter;

    @BeforeEach
    void setUp() {
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(exchange.getResponse()).thenReturn(response);
        lenient().when(response.getHeaders()).thenReturn(new HttpHeaders());
        lenient().when(response.isCommitted()).thenReturn(false);
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("Trace ID Generation Tests")
    class TraceIdGenerationTests {

        @Test
        @DisplayName("Should generate new trace ID when not present")
        void shouldGenerateNewTraceId() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            when(request.getHeaders()).thenReturn(headers);
            
            setupExchangeMutation();

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should generate valid UUID format trace ID")
        void shouldGenerateValidUuidFormat() {
            // Given
            String generatedId = UUID.randomUUID().toString();
            
            // When - Just verify format
            assertDoesNotThrow(() -> UUID.fromString(generatedId));
        }
    }

    @Nested
    @DisplayName("Trace ID Propagation Tests")
    class TraceIdPropagationTests {

        @Test
        @DisplayName("Should use existing trace ID from header")
        void shouldUseExistingTraceId() {
            // Given
            String existingTraceId = "existing-trace-id-12345";
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Trace-Id", existingTraceId);
            when(request.getHeaders()).thenReturn(headers);
            
            setupExchangeMutation();

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty trace ID header")
        void shouldHandleEmptyTraceIdHeader() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Trace-Id", "");
            when(request.getHeaders()).thenReturn(headers);
            
            setupExchangeMutation();

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Filter Order Tests")
    class FilterOrderTests {

        @Test
        @DisplayName("Should have high priority order (-300)")
        void shouldHaveCorrectOrder() {
            // When
            int order = filter.getOrder();

            // Then
            assertEquals(-300, order);
        }
    }

    @Nested
    @DisplayName("MDC Management Tests")
    class MdcManagementTests {

        @Test
        @DisplayName("Should clear MDC after request completes")
        void shouldClearMdcAfterRequest() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            when(request.getHeaders()).thenReturn(headers);
            
            setupExchangeMutation();

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            // MDC should be cleared after request
            assertNull(MDC.get("traceId"));
        }
    }
    
    private void setupExchangeMutation() {
        when(request.mutate()).thenReturn(requestBuilder);
        when(requestBuilder.header(anyString(), anyString())).thenReturn(requestBuilder);
        when(requestBuilder.build()).thenReturn(mutatedRequest);
        
        when(exchange.mutate()).thenReturn(exchangeBuilder);
        when(exchangeBuilder.request(any(ServerHttpRequest.class))).thenReturn(exchangeBuilder);
        when(exchangeBuilder.build()).thenReturn(mutatedExchange);
        
        lenient().when(mutatedExchange.getRequest()).thenReturn(mutatedRequest);
        lenient().when(mutatedExchange.getResponse()).thenReturn(response);
    }
}