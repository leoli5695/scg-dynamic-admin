package com.leoli.gateway.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.admin.dto.ProviderHealthDTO;
import com.leoli.gateway.admin.model.ServiceDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Periodic lightweight HTTP probe of AI services' model-provider endpoints.
 * <p>
 * OpenAI-compatible services are probed with GET {baseUrl}/models carrying the
 * stored credential as a Bearer token; other protocols fall back to HEAD/GET on
 * the baseUrl itself. Results live in an in-memory cache only (UNKNOWN until the
 * first round); NORMAL services keep their heartbeat-based instance health.
 * Credentials are resolved read-only via {@link ProviderCredentialService#resolveKey}.
 *
 * @author leoli
 */
@Slf4j
@Service
public class ProviderHealthProbeService {

    /**
     * Default base URLs for blank-baseUrl AI services. Mirrors the data-plane
     * catalogue (my-gateway LlmProvider); keep the two in sync when it changes.
     */
    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "deepseek", "https://api.deepseek.com",
            "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "zhipu", "https://open.bigmodel.cn/api/paas/v4",
            "moonshot", "https://api.moonshot.cn/v1",
            "anthropic", "https://api.anthropic.com/v1");

    private static final int MAX_MESSAGE_LENGTH = 200;
    private static final long RETRY_DELAY_MS = 500;
    private static final long DEFAULT_INTERVAL_MS = 3_600_000;

    private final ServiceService serviceService;
    private final ProviderCredentialService providerCredentialService;
    private final RestTemplate probeTemplate;
    private final int concurrency;
    private final Cache<String, ProviderHealthDTO> healthCache;

    public ProviderHealthProbeService(
            ServiceService serviceService,
            ProviderCredentialService providerCredentialService,
            @Value("${gateway.admin.provider-health.timeout-ms:5000}") int timeoutMs,
            @Value("${gateway.admin.provider-health.interval-ms:3600000}") long intervalMs,
            @Value("${gateway.admin.provider-health.concurrency:4}") int concurrency) {
        this.serviceService = serviceService;
        this.providerCredentialService = providerCredentialService;
        this.concurrency = Math.max(1, concurrency);
        this.probeTemplate = buildProbeTemplate(timeoutMs);
        long effectiveInterval = intervalMs > 0 ? intervalMs : DEFAULT_INTERVAL_MS;
        // TTL must outlive the probe interval, otherwise state vanishes between rounds.
        this.healthCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(effectiveInterval * 2))
                .maximumSize(1000)
                .build();
    }

    /**
     * Dedicated template: the shared bean has a 30s read timeout and attaches the
     * internal token by path, neither of which may go to external providers.
     * Redirects are disabled so a 30x cannot bounce the Bearer key to another host.
     */
    private static RestTemplate buildProbeTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    /** Probe every AI service once, bounded concurrency; failures are recorded, not thrown. */
    public void probeAll() {
        List<ServiceDefinition> targets = serviceService.getAllServices().stream()
                .filter(ServiceDefinition::isAiService)
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, targets.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ServiceDefinition service : targets) {
                futures.add(pool.submit(() -> probeOne(service)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    log.warn("Provider probe task failed unexpectedly", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    /** Latest probe result for a service; UNKNOWN when never probed or evicted. */
    public ProviderHealthDTO getHealth(String serviceName) {
        ProviderHealthDTO health = healthCache.getIfPresent(serviceName);
        return health != null ? health : ProviderHealthDTO.unknown();
    }

    private void probeOne(ServiceDefinition service) {
        String name = service.getName();
        try {
            String baseUrl = resolveBaseUrl(service);
            if (baseUrl == null) {
                record(name, ProviderHealthDTO.UNKNOWN, "未配置 baseUrl，无法探测");
                return;
            }
            if (service.getCredentialRef() == null || service.getCredentialRef().isBlank()) {
                record(name, ProviderHealthDTO.UNHEALTHY, "未配置凭证 (credentialRef)");
                return;
            }
            String apiKey;
            try {
                apiKey = providerCredentialService.resolveKey(service.getCredentialRef());
            } catch (Exception e) {
                record(name, ProviderHealthDTO.UNHEALTHY, "凭证不可用: " + sanitize(e.getMessage(), null));
                return;
            }
            ProbeResult result = probeWithRetry(baseUrl, service.getProtocol(), apiKey);
            record(name, result.healthy() ? ProviderHealthDTO.HEALTHY : ProviderHealthDTO.UNHEALTHY,
                    sanitize(result.message(), apiKey));
        } catch (Exception e) {
            log.warn("Provider probe failed for service {}: {}", name, e.getClass().getSimpleName());
            record(name, ProviderHealthDTO.UNHEALTHY, "探测异常: " + e.getClass().getSimpleName());
        }
    }

    /** Explicit baseUrl wins; blank falls back to the provider's built-in default. */
    String resolveBaseUrl(ServiceDefinition service) {
        String base = service.getBaseUrl();
        if (base != null && !base.isBlank()) {
            return base.trim();
        }
        String providerType = service.getProviderType();
        if (providerType == null || providerType.isBlank()) {
            return null;
        }
        return DEFAULT_BASE_URLS.get(providerType.trim().toLowerCase(Locale.ROOT));
    }

    private ProbeResult probeWithRetry(String baseUrl, String protocol, String apiKey) {
        ProbeResult result = probeOnce(baseUrl, protocol, apiKey);
        if (!result.healthy()) {
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = probeOnce(baseUrl, protocol, apiKey);
        }
        return result;
    }

    private ProbeResult probeOnce(String baseUrl, String protocol, String apiKey) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return ProbeResult.unhealthy("baseUrl 仅支持 http/https");
        }
        if (isOpenAiCompatible(protocol)) {
            ProbeResult models = exchange(base + "/models", HttpMethod.GET, apiKey);
            if (models.healthy() || !models.fallbackWorthy()) {
                return models;
            }
            // Some OpenAI-compatible relays do not implement /models; try the base URL.
            return exchange(base, HttpMethod.GET, apiKey);
        }
        ProbeResult head = exchange(base, HttpMethod.HEAD, apiKey);
        if (head.healthy() || !head.fallbackWorthy()) {
            return head;
        }
        return exchange(base, HttpMethod.GET, apiKey);
    }

    private static boolean isOpenAiCompatible(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return true;
        }
        String p = protocol.trim().toUpperCase(Locale.ROOT);
        return p.equals("OPENAI") || p.equals("OPENAI_COMPAT") || p.equals("OPENAI-COMPATIBLE");
    }

    private ProbeResult exchange(String url, HttpMethod method, String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            ResponseEntity<Void> response =
                    probeTemplate.exchange(URI.create(url), method, new HttpEntity<>(headers), Void.class);
            int status = response.getStatusCode().value();
            return response.getStatusCode().is2xxSuccessful()
                    ? ProbeResult.healthy(status)
                    : ProbeResult.unhealthy(status, "HTTP " + status);
        } catch (HttpStatusCodeException e) {
            // Status only: provider error bodies may echo the API key, so they are never read.
            int status = e.getStatusCode().value();
            return ProbeResult.unhealthy(status, describeHttpError(status));
        } catch (ResourceAccessException e) {
            return ProbeResult.unhealthy(describeNetworkError(e));
        } catch (Exception e) {
            return ProbeResult.unhealthy("探测异常: " + e.getClass().getSimpleName());
        }
    }

    private static String describeHttpError(int status) {
        return switch (status) {
            case 401, 403 -> "HTTP " + status + "（认证失败，请检查 API Key）";
            case 404 -> "HTTP 404（端点不存在）";
            case 429 -> "HTTP 429（提供商限流）";
            default -> status >= 500 ? "HTTP " + status + "（提供商服务端错误）" : "HTTP " + status;
        };
    }

    private static String describeNetworkError(ResourceAccessException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof SocketTimeoutException) {
            String m = cause.getMessage();
            return m != null && m.toLowerCase(Locale.ROOT).contains("read") ? "读取超时" : "连接超时";
        }
        if (cause instanceof ConnectException) {
            return "连接失败: " + cause.getMessage();
        }
        if (cause instanceof UnknownHostException) {
            return "域名解析失败: " + cause.getMessage();
        }
        return "网络错误: " + cause.getClass().getSimpleName();
    }

    /** Defense in depth: strip the key if it ever reaches a message, then cap length. */
    private static String sanitize(String message, String apiKey) {
        if (message == null) {
            return null;
        }
        String cleaned = message;
        if (apiKey != null && !apiKey.isEmpty()) {
            cleaned = cleaned.replace(apiKey, "***");
        }
        return cleaned.length() > MAX_MESSAGE_LENGTH ? cleaned.substring(0, MAX_MESSAGE_LENGTH) : cleaned;
    }

    private void record(String serviceName, String status, String message) {
        healthCache.put(serviceName, new ProviderHealthDTO(status, System.currentTimeMillis(), message));
        if (ProviderHealthDTO.UNHEALTHY.equals(status)) {
            log.warn("AI provider probe unhealthy: service={}, {}", serviceName, message);
        } else {
            log.debug("AI provider probe: service={}, {}", serviceName, status);
        }
    }

    private record ProbeResult(boolean healthy, Integer httpStatus, String message) {
        static ProbeResult healthy(int status) {
            return new ProbeResult(true, status, "HTTP " + status);
        }

        static ProbeResult unhealthy(int status, String message) {
            return new ProbeResult(false, status, message);
        }

        static ProbeResult unhealthy(String message) {
            return new ProbeResult(false, null, message);
        }

        boolean fallbackWorthy() {
            return httpStatus != null && (httpStatus == 404 || httpStatus == 405 || httpStatus == 501);
        }
    }
}