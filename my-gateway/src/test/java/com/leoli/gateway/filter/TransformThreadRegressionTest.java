package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.leoli.gateway.filter.transform.RequestTransformFilter;
import com.leoli.gateway.filter.transform.ResponseTransformFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.RequestTransformConfig;
import com.leoli.gateway.model.ResponseTransformConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TransformThreadRegressionTest {
    @Test void requestParsingRunsOnDedicatedWorkersAndPreservesTransformedBody() throws Exception {
        AtomicReference<String> parsingThread = new AtomicReference<>();
        ObjectMapper mapper = spy(new ObjectMapper());
        doAnswer(call -> { parsingThread.set(Thread.currentThread().getName()); return call.callRealMethod(); }).when(mapper).readTree(anyString());
        StrategyManager manager = mock(StrategyManager.class);
        RequestTransformConfig config = new RequestTransformConfig(); config.setEnabled(true);
        when(manager.getConfig(any(), any(), eq(RequestTransformConfig.class))).thenReturn(config);
        var filter = new RequestTransformFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", manager);
        ReflectionTestUtils.setField(filter, "objectMapper", mapper);
        ReflectionTestUtils.setField(filter, "xmlMapper", new XmlMapper());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/").contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}"));
        try {
            filter.filter(exchange, request -> org.springframework.core.io.buffer.DataBufferUtils.join(request.getRequest().getBody())
                    .doOnNext(buffer -> {
                        assertEquals("{\"ok\":true}", buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
                        org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    }).then()).block(Duration.ofSeconds(3));
            assertTrue(parsingThread.get().startsWith("gateway-body-transform"));
        } finally { filter.closeWorkPool(); }
    }
    @Test void responseParsingRunsOnDedicatedWorkersAndWritesCompleteBody() throws Exception {
        AtomicReference<String> parsingThread = new AtomicReference<>();
        ObjectMapper mapper = spy(new ObjectMapper());
        doAnswer(call -> { parsingThread.set(Thread.currentThread().getName()); return call.callRealMethod(); }).when(mapper).readTree(anyString());
        StrategyManager manager = mock(StrategyManager.class);
        ResponseTransformConfig config = new ResponseTransformConfig(); config.setEnabled(true);
        when(manager.getConfig(any(), any(), eq(ResponseTransformConfig.class))).thenReturn(config);
        var filter = new ResponseTransformFilter(mapper, new XmlMapper());
        ReflectionTestUtils.setField(filter, "strategyManager", manager);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        try {
            filter.filter(exchange, request -> {
                request.getResponse().setStatusCode(org.springframework.http.HttpStatus.OK);
                request.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return request.getResponse().writeWith(Mono.just(request.getResponse().bufferFactory()
                        .wrap("{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            }).block(Duration.ofSeconds(3));
            assertTrue(parsingThread.get().startsWith("gateway-body-transform"));
            assertEquals("{\"ok\":true}", exchange.getResponse().getBodyAsString().block());
        } finally { filter.closeWorkPool(); }
    }
}
