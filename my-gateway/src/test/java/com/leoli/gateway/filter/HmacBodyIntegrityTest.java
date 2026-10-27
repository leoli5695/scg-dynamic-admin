package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.auth.HmacSignatureAuthProcessor;
import com.leoli.gateway.filter.security.AuthenticationGlobalFilter;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class HmacBodyIntegrityTest {
    private final HmacSignatureAuthProcessor processor = new HmacSignatureAuthProcessor();
    private AuthenticationGlobalFilter filter;

    @BeforeEach
    void setUp() {
        var bindings = new AuthBindingManager(new ObjectMapper());
        var config = new AuthConfig();
        config.setAuthType("HMAC");
        config.setAccessKey("key");
        config.setSecretKey("secret");
        bindings.putPolicy("policy", config);
        bindings.setPolicyRoutes("policy", List.of("signed-route"));
        var strategies = new StrategyManager(new ObjectMapper());
        var strategy = new StrategyDefinition();
        strategy.setStrategyId("hmac");
        strategy.setStrategyType(StrategyDefinition.TYPE_AUTH);
        strategy.setScope("ROUTE");
        strategy.setRouteId("signed-route");
        strategy.setConfig(Map.of("authType", "HMAC"));
        strategies.putStrategy("hmac", strategy);
        filter = new AuthenticationGlobalFilter(strategies, bindings, new AuthProcessManager(List.of(processor)));
    }

    private MockServerWebExchange request(String signedBody, String actualBody, String md5) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String canonical = "POST\n/pay\n\n" + timestamp + "\n" + nonce;
        if (!signedBody.isEmpty()) {
            canonical += "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(signedBody.getBytes(StandardCharsets.UTF_8)));
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        var builder = MockServerHttpRequest.post("/pay").header("X-Access-Key", "key")
                .header("X-Signature", signature).header("X-Timestamp", timestamp).header("X-Nonce", nonce);
        if (md5 != null) builder.header("X-Content-MD5", md5);
        var exchange = MockServerWebExchange.from(builder.body(actualBody));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("signed-route")
                .uri("http://localhost").predicate(ignored -> true).build());
        return exchange;
    }

    @Test
    void signedBodyIsForwardedIntactAndCanBeReadAgainForRetry() throws Exception {
        String body = "{\"amount\":1,\"name\":\"测试\"}";
        var exchange = request(body, body, HmacSignatureAuthProcessor.calculateMD5(body));
        AtomicInteger subscriptions = new AtomicInteger();
        var onceOnlyRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> subscriptions.incrementAndGet() == 1 ? super.getBody()
                        : Flux.error(new IllegalStateException("original body already consumed")));
            }
        };
        AtomicBoolean called = new AtomicBoolean();
        filter.filter(exchange.mutate().request(onceOnlyRequest).build(), e -> {
            called.set(true);
            return readBody(e.getRequest().getBody(), body)
                    .then(Mono.defer(() -> readBody(e.getRequest().getBody(), body)));
        }).block();
        assertTrue(called.get());
        assertEquals(1, subscriptions.get());
    }

    private Mono<Void> readBody(Flux<DataBuffer> publisher, String expected) {
        return DataBufferUtils.join(publisher).doOnNext(buffer -> {
            try {
                assertEquals(expected, buffer.toString(StandardCharsets.UTF_8));
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).then();
    }

    private void assertRejected(MockServerWebExchange exchange, HttpStatus status) {
        AtomicBoolean called = new AtomicBoolean();
        filter.filter(exchange, ignored -> {
            called.set(true);
            return Mono.empty();
        }).block();
        assertFalse(called.get());
        assertEquals(status, exchange.getResponse().getStatusCode());
    }

    @Test
    void changingBodyFailsEvenIfMd5IsChangedToMatch() throws Exception {
        assertRejected(request("{\"amount\":1}", "{\"amount\":999}",
                HmacSignatureAuthProcessor.calculateMD5("{\"amount\":999}")), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void legacySignatureCannotAuthorizeNonEmptyBody() throws Exception {
        assertRejected(request("", "unsigned-body", null), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void optionalMd5MustMatchActualBody() throws Exception {
        assertRejected(request("body", "body", HmacSignatureAuthProcessor.calculateMD5("other")), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oversizedBodyIsRejectedBeforeForwarding() throws Exception {
        ReflectionTestUtils.setField(processor, "maxBodyBytes", 4);
        assertRejected(request("large-body", "large-body", null), HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void emptyBodyRetainsLegacyCanonicalString() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        filter.filter(request("", "", null), ignored -> {
            called.set(true);
            return Mono.empty();
        }).block();
        assertTrue(called.get());
    }
}
