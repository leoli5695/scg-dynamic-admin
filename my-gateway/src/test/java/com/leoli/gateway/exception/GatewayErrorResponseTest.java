package com.leoli.gateway.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.util.ClientIpResolver;
import com.leoli.gateway.util.GatewayResponseHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayErrorResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScgGlobalExceptionHandler handler = new ScgGlobalExceptionHandler(mapper, mock(ClientIpResolver.class));

    private MockServerWebExchange exchange() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders?token=private-token").build());
        exchange.getAttributes().put("gateway.trace.capture-errors", false);
        return exchange;
    }

    private JsonNode respond(MockServerWebExchange exchange, Throwable exception) throws Exception {
        handler.handle(exchange, exception).block();
        return mapper.readTree(exchange.getResponse().getBodyAsString().block());
    }

    @ParameterizedTest
    @CsvSource({"400,parameters", "401,credentials", "403,policy", "404,route", "405,method", "413,size limit", "415,Content-Type", "422,validation", "429,Retry-After", "500,traceId", "502,backend", "503,registration", "504,timeout"})
    void genericStatusesExplainTheCauseAndNextStepWithoutChangingTheContract(int status, String expected) throws Exception {
        var exchange = exchange();
        var body = respond(exchange, new ResponseStatusException(HttpStatus.valueOf(status)));
        assertEquals(status, exchange.getResponse().getStatusCode().value());
        assertEquals(status, body.get("httpStatus").asInt());
        assertEquals(status, body.get("code").asInt());
        assertTrue(body.get("message").asText().contains(expected), body.toString());
        assertTrue(body.get("data").isNull());
        assertFalse(body.toString().contains("private-token"));
        assertEquals(exchange.getResponse().getHeaders().getFirst("X-Trace-Id"), body.get("traceId").asText());
    }

    @ParameterizedTest
    @CsvSource({"No enabled services for route: private-route,binding", "No enabled instances for service: private-service,Enable", "No available instances for service: private-service,namespace/group", "Failed to connect to service instance private-host:8080,connect"})
    void unavailableCausesAreDistinguishedWithoutReturningInternalIdentifiers(String reason, String expected) throws Exception {
        var body = respond(exchange(), new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason));
        assertTrue(body.get("message").asText().contains(expected), body.toString());
        assertFalse(body.toString().contains("private-"));
    }

    @Test
    void internalExceptionDetailsAreNotExposedAndExistingTraceIdIsPreserved() throws Exception {
        var exchange = exchange();
        exchange.getAttributes().put("traceId", "0123456789abcdef0123456789abcdef");
        var body = respond(exchange, new IllegalStateException("jdbc://internal-db password=private-secret"));
        assertEquals(500, body.get("httpStatus").asInt());
        assertFalse(body.toString().contains("private-secret"));
        assertFalse(body.toString().contains("internal-db"));
        assertEquals("0123456789abcdef0123456789abcdef", body.get("traceId").asText());
    }

    @Test
    void structuredGatewayErrorsRetainBusinessCodesWithActionableMessages() throws Exception {
        var body = respond(exchange(), new GatewayException(ErrorCode.REQUEST_TRANSFORM_ERROR,
                new IllegalArgumentException("private transformation source")));
        assertEquals(ErrorCode.REQUEST_TRANSFORM_ERROR.getCode(), body.get("code").asInt());
        assertTrue(body.get("message").asText().contains("transformation rules"));
        assertFalse(body.toString().contains("private transformation source"));
    }

    @Test
    void validationDetailsAndRateLimitHeadersArePreserved() throws Exception {
        var validation = respond(exchange(), new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required field 'model' is missing"));
        assertEquals("Required field 'model' is missing", validation.get("message").asText());
        var rateExchange = exchange();
        var rate = respond(rateExchange, new RateLimitException("route", 10, 0, 5));
        assertEquals("5", rateExchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals(10, rate.get("limit").asInt());
        assertEquals("5s", rate.get("retryAfter").asText());
    }

    @Test
    void timeoutAndConnectionFailuresHaveSpecificStatusesAndExplanations() throws Exception {
        var timeout = respond(exchange(), new java.net.SocketTimeoutException("private-host read timed out"));
        assertEquals(504, timeout.get("httpStatus").asInt());
        assertTrue(timeout.get("message").asText().contains("timeout"));
        var connection = respond(exchange(), new java.net.ConnectException("Connection refused private-host:8080"));
        assertEquals(503, connection.get("httpStatus").asInt());
        assertTrue(connection.get("message").asText().contains("refused"));
        assertFalse(connection.toString().contains("private-host"));
    }

    @Test
    void directFilterResponsesUseDetailedDefaultsButKeepExplicitMessages() throws Exception {
        var exchange = exchange();
        GatewayResponseHelper.writeErrorResponse(exchange.getResponse(), ErrorCode.TOKEN_EXPIRED).block();
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertTrue(body.get("message").asText().contains("Obtain a new token"));
        var custom = exchange();
        GatewayResponseHelper.writeBadRequest(custom.getResponse(), "Missing header: X-Model").block();
        assertEquals("Missing header: X-Model", mapper.readTree(custom.getResponse().getBodyAsString().block()).get("message").asText());
    }
}
