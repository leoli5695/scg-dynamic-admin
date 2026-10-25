package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth2 token introspection (RFC 7662): the shape of the outbound request to the authorization
 * server and, above all, that the server's answer is actually enforced.
 *
 * <p>This processor had no tests. Both fail-closed rules in it were added as P0 fixes - "no
 * introspection endpoint configured" used to answer {@code {active:true}} for any Bearer token, and
 * the {@code active} flag used to be ignored - so they are the rules most likely to regress, and a
 * regression here is a full authentication bypass rather than a 500.
 *
 * <p>The WebClient is stubbed at the {@code ExchangeFunction} level, so these tests exercise the
 * real serialization of the request (method, URI, headers, form body) without binding a port.
 */
class OAuth2AuthProcessorTest {

    private static final String INTROSPECTION_ENDPOINT = "https://idp.example.com/oauth2/introspect";
    private static final String TOKEN = "ya29.a0AfH6SMB-example-access-token";

    /** Captures the outbound request and answers with a canned introspection response. */
    private static final class IntrospectionStub {

        private final AtomicReference<ClientRequest> captured = new AtomicReference<>();
        private final HttpStatus status;
        private final String body;

        private IntrospectionStub(HttpStatus status, String body) {
            this.status = status;
            this.body = body;
        }

        WebClient webClient() {
            return WebClient.builder()
                    .exchangeFunction(request -> {
                        captured.set(request);
                        ClientResponse.Builder builder = ClientResponse.create(status);
                        if (body != null) {
                            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                    .body(body);
                        }
                        return Mono.just(builder.build());
                    })
                    .build();
        }

        ClientRequest request() {
            return captured.get();
        }
    }

    private static IntrospectionStub respondingWith(String json) {
        return new IntrospectionStub(HttpStatus.OK, json);
    }

    private static AuthConfig config(String tokenEndpoint) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("OAUTH2");
        config.setTokenEndpoint(tokenEndpoint);
        config.setClientId("gateway-client");
        config.setClientSecret("gateway-secret");
        return config;
    }

    private static MockServerWebExchange exchangeWithBearer(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static OAuth2AuthProcessor processorWith(IntrospectionStub stub) {
        return new OAuth2AuthProcessor(stub.webClient());
    }

    /** Re-materialises the request body so the form encoding can be asserted on. */
    private static String requestBody(ClientRequest request) {
        MockClientHttpRequest materialised = new MockClientHttpRequest(request.method(), request.url());
        BodyInserter.Context context = new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ClientCodecConfigurer.create().getWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return new HashMap<>();
            }
        };
        request.body().insert(materialised, context).block();
        return materialised.getBodyAsString().block();
    }

    @Nested
    @DisplayName("Auth type")
    class AuthTypeTests {

        @Test
        @DisplayName("Should return OAUTH2")
        void shouldReturnOAuth2AuthType() {
            assertEquals(AuthType.OAUTH2, processorWith(respondingWith("{\"active\":true}")).getAuthType());
        }
    }

    @Nested
    @DisplayName("Configuration and header preconditions")
    class PreconditionTests {

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchangeWithBearer(TOKEN), null))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid OAuth2 auth configuration"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject disabled config")
        void shouldRejectDisabledConfig() {
            AuthConfig config = config(INTROSPECTION_ENDPOINT);
            config.setEnabled(false);

            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchangeWithBearer(TOKEN), config))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid OAuth2 auth configuration"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject a missing Authorization header")
        void shouldRejectMissingAuthorizationHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/orders"));

            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchange, config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Missing or invalid Authorization header"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject a non-Bearer scheme")
        void shouldRejectNonBearerScheme() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + TOKEN));

            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchange, config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Missing or invalid Authorization header"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject an empty Bearer token")
        void shouldRejectEmptyBearerToken() {
            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchangeWithBearer(""), config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Missing or invalid Authorization header"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Fail-closed without an authorization server")
    class MissingEndpointTests {

        @Test
        @DisplayName("Should reject when no token endpoint is configured")
        void shouldRejectWhenTokenEndpointNotConfigured() {
            // Before the fix this returned a fabricated {active:true} and let ANY Bearer token in.
            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchangeWithBearer(TOKEN), config(null)))
                    .expectErrorMatches(e -> e.getMessage().contains("token endpoint not configured"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when the token endpoint is an empty string")
        void shouldRejectWhenTokenEndpointEmpty() {
            StepVerifier.create(processorWith(respondingWith("{\"active\":true}"))
                            .process(exchangeWithBearer(TOKEN), config("")))
                    .expectErrorMatches(e -> e.getMessage().contains("token endpoint not configured"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Introspection result enforcement")
    class IntrospectionResultTests {

        @Test
        @DisplayName("Should accept an active token and record the exchange attribute")
        void shouldAcceptActiveToken() {
            MockServerWebExchange exchange = exchangeWithBearer(TOKEN);

            StepVerifier.create(processorWith(respondingWith("{\"active\":true,\"scope\":\"read write\"}"))
                            .process(exchange, config(INTROSPECTION_ENDPOINT)))
                    .verifyComplete();

            assertEquals(Boolean.TRUE, exchange.getAttributes().get("oauth2_token_active"));
        }

        @Test
        @DisplayName("Should reject a token the server reports inactive")
        void shouldRejectInactiveToken() {
            StepVerifier.create(processorWith(respondingWith("{\"active\":false}"))
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Token is not active"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when the active flag is absent")
        void shouldRejectWhenActiveFlagAbsent() {
            // An introspection answer with no "active" key is not an approval.
            StepVerifier.create(processorWith(respondingWith("{\"scope\":\"read\",\"client_id\":\"x\"}"))
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Token is not active"))
                    .verify();
        }

        @Test
        @DisplayName("Should accept the string form active=true")
        void shouldAcceptStringTrueActiveFlag() {
            StepVerifier.create(processorWith(respondingWith("{\"active\":\"true\"}"))
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject the string form active=false")
        void shouldRejectStringFalseActiveFlag() {
            StepVerifier.create(processorWith(respondingWith("{\"active\":\"false\"}"))
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Token is not active"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject an empty introspection response body")
        void shouldRejectEmptyIntrospectionBody() {
            StepVerifier.create(processorWith(new IntrospectionStub(HttpStatus.OK, null))
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().contains("Empty OAuth2 introspection response"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when the authorization server answers 401")
        void shouldRejectWhenServerReturnsUnauthorized() {
            StepVerifier.create(processorWith(new IntrospectionStub(HttpStatus.UNAUTHORIZED, "{\"error\":\"invalid_client\"}"))
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .expectErrorMatches(e -> e.getMessage().startsWith("Token validation failed"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Outbound introspection request shape")
    class RequestShapeTests {

        @Test
        @DisplayName("Should POST an RFC 7662 form body with client Basic auth")
        void shouldSendRfc7662IntrospectionRequest() {
            IntrospectionStub stub = respondingWith("{\"active\":true}");

            StepVerifier.create(processorWith(stub)
                            .process(exchangeWithBearer(TOKEN), config(INTROSPECTION_ENDPOINT)))
                    .verifyComplete();

            ClientRequest request = stub.request();
            assertEquals(HttpMethod.POST, request.method());
            assertEquals(INTROSPECTION_ENDPOINT, request.url().toString());
            assertEquals(MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                    request.headers().getFirst(HttpHeaders.CONTENT_TYPE));

            String expectedBasic = "Basic " + Base64.getEncoder()
                    .encodeToString("gateway-client:gateway-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(expectedBasic, request.headers().getFirst(HttpHeaders.AUTHORIZATION));

            String body = requestBody(request);
            assertTrue(body.contains("token=" + TOKEN), "the token must be introspected: " + body);
            assertTrue(body.contains("token_type_hint=access_token"), "the hint must be sent: " + body);
        }

        @Test
        @DisplayName("Should send an empty Basic credential when client id or secret is missing")
        void shouldSendEmptyBasicCredentialsWhenClientIdMissing() {
            IntrospectionStub stub = respondingWith("{\"active\":true}");
            AuthConfig config = config(INTROSPECTION_ENDPOINT);
            config.setClientSecret(null);

            StepVerifier.create(processorWith(stub).process(exchangeWithBearer(TOKEN), config))
                    .verifyComplete();

            assertEquals("Basic ", stub.request().headers().getFirst(HttpHeaders.AUTHORIZATION));
        }
    }
}
