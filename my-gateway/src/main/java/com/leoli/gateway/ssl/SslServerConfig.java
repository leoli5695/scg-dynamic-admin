package com.leoli.gateway.ssl;

import io.netty.buffer.ByteBuf;
import io.netty.handler.ssl.SslContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * HTTPS Server Configuration for Gateway
 * Starts an HTTPS server on port 8443 when SSL certificates are available
 * Proxies requests to the main HTTP gateway with SSL termination
 */
@Slf4j
@Configuration
public class SslServerConfig {

    @Autowired(required = false)
    private DynamicSslContextManager sslContextManager;

    @Value("${gateway.ssl.enabled:true}")
    private boolean sslEnabled;

    @Value("${gateway.ssl.port:8443}")
    private int sslPort;

    @Value("${server.port:80}")
    private int httpPort;

    private DisposableServer httpsServer;
    private HttpClient httpClient;

    private final AtomicBoolean serverStarted = new AtomicBoolean(false);

    // Cache of domain -> SSL context
    private final Map<String, SslContext> sslContextCache = new ConcurrentHashMap<>();

    /**
     * Start HTTPS server - called by SslCertificateLoader after certificates are loaded
     */
    public synchronized void startHttpsServer() {
        log.info("startHttpsServer() called, sslEnabled={}, serverStarted={}, sslContextManager={}",
                sslEnabled, serverStarted.get(), sslContextManager != null ? "present" : "null");

        if (!sslEnabled) {
            log.info("SSL is disabled, not starting HTTPS server");
            return;
        }

        if (serverStarted.get()) {
            log.debug("HTTPS server already started, updating SSL context cache");
            updateSslCache();
            return;
        }

        // Always update cache first
        updateSslCache();

        if (sslContextManager == null) {
            log.warn("DynamicSslContextManager not available, HTTPS server not started");
            return;
        }

        if (sslContextManager.getConfiguredDomains().isEmpty()) {
            log.warn("No SSL certificates configured, HTTPS server not started");
            return;
        }

        try {
            // Initialize SSL cache
            updateSslCache();

            String defaultDomain = sslContextManager.getConfiguredDomains().iterator().next();
            SslContext defaultSslContext = sslContextManager.getSslContext(defaultDomain);

            if (defaultSslContext == null) {
                log.error("Failed to get SSL context for domain: {}", defaultDomain);
                return;
            }

            // Initialize HTTP client for proxying
            httpClient = HttpClient.create()
                    .baseUrl("http://127.0.0.1:" + httpPort)
                    .responseTimeout(Duration.ofSeconds(60))
                    .compress(true);

            // Create HTTPS server
            httpsServer = HttpServer.create()
                    .port(sslPort)
                    .secure(spec -> spec.sslContext(defaultSslContext))
                    .handle(createRequestHandler())
                    .bindNow(Duration.ofSeconds(30));

            serverStarted.set(true);
            log.info("HTTPS server started on port {}. Configured domains: {}",
                    sslPort, sslContextManager.getConfiguredDomains());

        } catch (Exception e) {
            log.error("Failed to start HTTPS server", e);
        }
    }

    /**
     * Update SSL cache with current certificates
     */
    private void updateSslCache() {
        if (sslContextManager == null) {
            return;
        }
        Set<String> domains = sslContextManager.getConfiguredDomains();
        sslContextCache.clear();

        for (String domain : domains) {
            SslContext context = sslContextManager.getSslContext(domain);
            if (context != null) {
                sslContextCache.put(domain, context);
            }
        }

        log.debug("SSL cache updated with {} domains", sslContextCache.size());
    }

    /**
     * Create request handler - proxies requests to HTTP gateway
     */
    private BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> createRequestHandler() {
        return (request, response) -> {
            String path = request.uri();
            String host = request.requestHeaders().get("Host", "");

            // Extract domain from host (remove port)
            String domain = host.contains(":") ? host.substring(0, host.indexOf(":")) : host;
            if (domain.isEmpty()) {
                domain = "unknown";
            }

            log.debug("HTTPS request: {} {} from host: {} (domain: {})", request.method().name(), path, host, domain);

            // Check if we have a certificate for this domain
            boolean hasCert = sslContextCache.containsKey(domain) || matchesAnyWildcard(domain);

            if (!hasCert && !domain.equals("unknown")) {
                // No certificate for this domain - show friendly error
                return sendSslErrorPage(response, domain, path);
            }

            // Proxy request to HTTP gateway
            return proxyToHttpGateway(request, response, host, path);
        };
    }

    /**
     * Proxy request to HTTP gateway
     */
    private Publisher<Void> proxyToHttpGateway(HttpServerRequest request, HttpServerResponse response, String host, String path) {
        log.info("Proxying request: {} {} to HTTP port {}", request.method().name(), path, httpPort);

        return httpClient
                .headers(headers -> {
                    // Add forwarded headers
                    headers.add("X-Forwarded-Proto", "https");
                    headers.add("X-Forwarded-Port", String.valueOf(sslPort));
                    headers.add("X-Forwarded-Host", host);
                    // Copy select original headers
                    String contentType = request.requestHeaders().get("Content-Type");
                    if (contentType != null) {
                        headers.add("Content-Type", contentType);
                    }
                })
                .request(request.method())
                .uri(path)
                .send(request.receive())
                .response((proxyResponse, bodyFlux) -> {
                    log.info("Got response: {} for {}", proxyResponse.status(), path);
                    // Set response status
                    response.status(proxyResponse.status());
                    // Copy response headers (skip hop-by-hop headers)
                    proxyResponse.responseHeaders().forEach(entry -> {
                        if (!isHopByHopHeader(entry.getKey())) {
                            response.header(entry.getKey(), entry.getValue());
                        }
                    });
                    // Stream response body - retain ByteBuf to prevent premature release
                    return response.send(bodyFlux.map(ByteBuf::retain));
                });
    }

    /**
     * Check if header is hop-by-hop (should not be forwarded)
     */
    private boolean isHopByHopHeader(String headerName) {
        String name = headerName.toLowerCase();
        return name.equals("connection") ||
                name.equals("keep-alive") ||
                name.equals("proxy-authenticate") ||
                name.equals("proxy-authorization") ||
                name.equals("te") ||
                name.equals("trailers") ||
                name.equals("transfer-encoding") ||
                name.equals("upgrade");
    }

    /**
     * Check if domain matches any wildcard certificate
     */
    private boolean matchesAnyWildcard(String domain) {
        for (String certDomain : sslContextCache.keySet()) {
            if (matchesWildcard(domain, certDomain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if domain matches wildcard pattern
     */
    private boolean matchesWildcard(String domain, String certDomain) {
        if (certDomain == null || !certDomain.startsWith("*.")) {
            return false;
        }
        String suffix = certDomain.substring(1); // Remove *
        return domain != null && domain.endsWith(suffix);
    }

    /**
     * Send friendly SSL error page
     */
    private Publisher<Void> sendSslErrorPage(HttpServerResponse response, String domain, String path) {
        String html = buildSslErrorPage(domain, path);
        return response.status(200)
                .header("Content-Type", "text/html; charset=utf-8")
                .sendString(Mono.just(html), StandardCharsets.UTF_8);
    }

    /**
     * Build friendly SSL error page HTML
     */
    private String buildSslErrorPage(String domain, String path) {
        Set<String> availableDomains = sslContextCache.keySet();

        StringBuilder domainList = new StringBuilder();
        for (String d : availableDomains) {
            domainList.append("<li><code>").append(escapeHtml(d)).append("</code></li>");
        }

        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>SSL Certificate Not Found</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                "               max-width: 800px; margin: 50px auto; padding: 20px; background: #f5f5f5; }\n" +
                "        .container { background: white; border-radius: 8px; padding: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                "        h1 { color: #e74c3c; margin-top: 0; }\n" +
                "        .domain { background: #f8f9fa; padding: 10px 15px; border-radius: 4px; font-family: monospace; margin: 10px 0; }\n" +
                "        .available { background: #e8f5e9; padding: 15px; border-radius: 4px; margin-top: 20px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>🔒 SSL Certificate Not Found</h1>\n" +
                "        <p>No SSL certificate is configured for:</p>\n" +
                "        <div class=\"domain\">" + escapeHtml(domain) + "</div>\n" +
                (availableDomains.isEmpty() ? "" :
                "        <div class=\"available\">\n" +
                "            <strong>Available domains:</strong>\n" +
                "            <ul>" + domainList + "</ul>\n" +
                "        </div>\n") +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * Escape HTML special characters
     */
    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Stop HTTPS server
     */
    @PreDestroy
    public void stopHttpsServer() {
        if (httpsServer != null) {
            httpsServer.disposeNow(Duration.ofSeconds(10));
            log.info("HTTPS server stopped");
        }
    }

    /**
     * Check if HTTPS server is running
     */
    public boolean isRunning() {
        return serverStarted.get() && httpsServer != null && !httpsServer.isDisposed();
    }

    /**
     * Get the port HTTPS server is listening on
     */
    public int getPort() {
        return sslPort;
    }

    /**
     * Get configured domains
     */
    public Set<String> getConfiguredDomains() {
        return sslContextCache.keySet();
    }
}