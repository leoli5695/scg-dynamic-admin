package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import com.leoli.gateway.admin.service.RouteDebugAuthService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Sends a bounded request through the configured gateway, including its normal filters. */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteDebugController {
    private final RouteRepository routes;
    private final GatewayInstanceService instances;
    private final RouteDebugAuthService authentication;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).build();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> { var t = new Thread(r, "route-debug"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "route-debug-heartbeat"); t.setDaemon(true); return t;
    });
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> HOP_HEADERS = Set.of("connection", "content-length", "transfer-encoding",
            "upgrade", "proxy-authorization", "proxy-connection", "keep-alive", "te", "trailer");
    private static final int MAX_OUTPUT = 256 * 1024;

    public record DebugRequest(String method, String path, Map<String, String> headers, String body, Integer timeoutSeconds,
                               String authPolicyId, String credential, String credentialId) {
        public DebugRequest(String method, String path, Map<String, String> headers, String body, Integer timeoutSeconds) {
            this(method, path, headers, body, timeoutSeconds, null, null, null);
        }
    }

    @GetMapping("/{routeId}/debug/auth")
    public com.leoli.gateway.admin.dto.ApiResponse<List<RouteDebugAuthService.PolicyOption>> authOptions(
            @PathVariable String routeId, @RequestParam String instanceId) {
        requireRoute(routeId, instanceId);
        return com.leoli.gateway.admin.dto.ApiResponse.success(authentication.options(instanceId, routeId));
    }

    private void requireRoute(String routeId, String instanceId) {
        var route = routes.findById(routeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found"));
        if (!Objects.equals(instanceId, route.getInstanceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Route does not belong to this gateway instance");
        }
    }

    @PostMapping(value = "/{routeId}/debug", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter debug(@PathVariable String routeId, @RequestParam String instanceId,
                            @org.springframework.web.bind.annotation.RequestBody DebugRequest input,
                            jakarta.servlet.http.HttpServletResponse servletResponse) {
        requireRoute(routeId, instanceId);
        if ((input.credential() != null && input.credential().length() > 8192)
                || (input.credentialId() != null && input.credentialId().length() > 128)) {
            throw badRequest("Authentication credential is too long");
        }
        String gateway = instances.getAccessUrl(instanceId);
        if (gateway == null || gateway.isBlank()) throw badRequest("Configure the gateway access URL first");
        Request request = buildRequest(gateway, input);
        if (input.authPolicyId() != null && !input.authPolicyId().isBlank()) {
            try { request = authentication.authenticate(request, instanceId, input.authPolicyId(), input.credential(), input.credentialId()); }
            catch (IllegalArgumentException e) { throw badRequest("Invalid authentication credential or header"); }
        }
        servletResponse.setHeader("X-Accel-Buffering", "no");
        servletResponse.setHeader("Cache-Control", "no-cache, no-transform");
        int timeout = input.timeoutSeconds() == null ? 30 : input.timeoutSeconds();
        Call call = client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS).build().newCall(request);
        var emitter = new SseEmitter((timeout + 5L) * 1000);
        emitter.onCompletion(call::cancel);
        emitter.onError(error -> call.cancel());
        emitter.onTimeout(() -> { call.cancel(); emitter.complete(); });
        try {
            workers.execute(() -> execute(call, emitter));
        } catch (RejectedExecutionException e) {
            call.cancel();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many route debug requests; try again shortly");
        }
        return emitter;
    }

    static Request buildRequest(String gateway, DebugRequest input) {
        try {
            String method = input.method() == null ? "GET" : input.method().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) throw badRequest("Unsupported HTTP method");
            int timeout = input.timeoutSeconds() == null ? 30 : input.timeoutSeconds();
            if (timeout < 1 || timeout > 60) throw badRequest("Timeout must be between 1 and 60 seconds");
            String path = input.path();
            if (path == null || path.length() > 8192 || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                    || URI.create(path).getRawFragment() != null) throw badRequest("Enter a gateway path starting with /, without a fragment");
            URI base = URI.create(gateway);
            if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                    || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw badRequest("Invalid configured gateway access URL");
            }
            String body = input.body() == null ? "" : input.body();
            if (body.getBytes(StandardCharsets.UTF_8).length > 65536) throw badRequest("Request body exceeds 64 KB");
            var builder = new Request.Builder().url(gateway.replaceAll("/+$", "") + path);
            Map<String, String> headers = input.headers() == null ? Map.of() : input.headers();
            if (headers.size() > 64 || headers.toString().length() > 16384) throw badRequest("Too many or oversized headers");
            headers.forEach((name, value) -> {
                if (HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) throw badRequest("Header is managed automatically: " + name);
                builder.header(name, value);
            });
            RequestBody payload = Set.of("GET", "HEAD").contains(method) ? null
                    : RequestBody.create(body.getBytes(StandardCharsets.UTF_8), (okhttp3.MediaType) null);
            return builder.method(method, payload).build();
        } catch (ResponseStatusException e) { throw e; }
        catch (IllegalArgumentException | NullPointerException e) { throw badRequest("Invalid request path or headers"); }
    }

    private void execute(Call call, SseEmitter emitter) {
        long start = System.nanoTime();
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { emitter.send(SseEmitter.event().comment("keepalive")); }
            catch (Exception e) { call.cancel(); }
        }, 1, 1, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            emitter.send(SseEmitter.event().name("response").data(Map.of(
                    "status", response.code(), "headers", response.headers().toMultimap(),
                    "firstByteMs", elapsed(start))));
            int count = 0;
            if (response.body() != null) {
                try (var reader = new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int n;
                    while ((n = reader.read(buffer, 0, Math.min(buffer.length, MAX_OUTPUT + 1 - count))) != -1) {
                        int accepted = Math.min(n, MAX_OUTPUT - count);
                        if (accepted > 0) emitter.send(SseEmitter.event().name("chunk").data(Map.of("text", new String(buffer, 0, accepted))));
                        count += n;
                        if (count > MAX_OUTPUT) break;
                    }
                }
            }
            emitter.send(SseEmitter.event().name("complete").data(Map.of("durationMs", elapsed(start), "truncated", count > MAX_OUTPUT)));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("failure").data(Map.of("message",
                        call.isCanceled() ? "Request cancelled or timed out" : "Gateway request failed: " + e.getClass().getSimpleName(),
                        "durationMs", elapsed(start))));
                emitter.complete();
            } catch (Exception disconnected) { emitter.completeWithError(disconnected); }
        } finally { heartbeat.cancel(false); call.cancel(); }
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static ResponseStatusException badRequest(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, text); }

    @PreDestroy
    public void close() {
        workers.shutdownNow(); heartbeats.shutdownNow(); client.dispatcher().cancelAll();
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
    }
}
