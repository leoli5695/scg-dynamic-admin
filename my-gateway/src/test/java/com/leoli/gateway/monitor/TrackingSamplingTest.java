package com.leoli.gateway.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;

class TrackingSamplingTest {
    @Test void unsampledRequestStillRecordsCircuitBreakerResult() {
        FilterChainTracker tracker = mock(FilterChainTracker.class);
        FilterCircuitBreakerManager breaker = mock(FilterCircuitBreakerManager.class);
        GlobalFilter original = (exchange, chain) -> chain.filter(exchange);
        var filter = new TrackedGlobalFilter(original, tracker, breaker); filter.setSamplingRate(0);
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/")), exchange -> Mono.empty()).block();
        verifyNoInteractions(tracker); verify(breaker).recordExecutionResult(anyString(), eq(true));
    }
    @Test void allWrappersShareOneSamplingDecisionPerRequest() {
        FilterChainTracker tracker = new FilterChainTracker();
        GlobalFilter original = (exchange, chain) -> chain.filter(exchange);
        var first = new TrackedGlobalFilter(original, tracker); first.setSamplingRate(1);
        var second = new TrackedGlobalFilter(original, tracker); second.setSamplingRate(0);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        exchange.getAttributes().put("traceId", "sampled");
        first.filter(exchange, request -> second.filter(request, ignored -> Mono.empty())).block();
        org.junit.jupiter.api.Assertions.assertEquals(2, tracker.getRecordForTrace("sampled").getExecutions().size());
    }
}
