package com.leoli.gateway.admin.controller;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RequestTraceIngestionTest {
    @Test void acceptsBothStructuredAndSerializedTraceFieldsAndPersistsResponses() throws Exception {
        var buffer=mock(TraceBufferService.class);when(buffer.offer(any())).thenReturn(true);
        var controller=new RequestTraceController(mock(RequestTraceService.class),mock(FilterChainExecutionService.class),
                buffer,mock(GatewayInstanceService.class),new RestTemplate(),mock(GatewayTraceConfigRegistry.class));
        var fields=Map.<String,Object>of("traceId","typed","requestHeaders",Map.of("Accept","application/json"),
                "requestBody",Map.of("message","test"),"targetInstance",Map.of("host","127.0.0.1"),
                "responseHeaders","{\"Content-Type\":\"application/json\"}","responseBody",Map.of("ok",true));
        assertEquals(202,controller.receiveTrace(fields).getStatusCode().value());
        var trace=ArgumentCaptor.forClass(RequestTrace.class);verify(buffer).offer(trace.capture());
        var mapper=new ObjectMapper();
        assertEquals("application/json",mapper.readTree(trace.getValue().getRequestHeaders()).get("Accept").asText());
        assertTrue(mapper.readTree(trace.getValue().getResponseBody()).get("ok").asBoolean());
        assertEquals("{\"Content-Type\":\"application/json\"}",trace.getValue().getResponseHeaders());
        assertEquals("127.0.0.1",mapper.readTree(trace.getValue().getTargetInstance()).get("host").asText());
    }
}
