package com.leoli.gateway.filter.token;

import com.leoli.gateway.constants.InternalApiConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把已结算的 token 用量异步上报给 admin（POST /api/token-quota/internal/record-usage），
 * 供控制台的 Token 计量可观测页按 路由/租户/模型/实例/时间 维度统计。
 * <p>
 * 设计约束：计量上报绝不能影响代理路径——fire-and-forget、失败不阻断代理、
 * 未配 internal-token 时整体关闭（与 AdminProviderKeyResolver 同一 posture）。
 * 失败虽不重投，但会计入 {@code token.usage.report.failures} 指标并采样 warn，
 * 便于对账 Redis 扣减量与 admin 落库量。
 * 连接/响应超时短（2s/3s），避免 admin 慢拖住网关资源；WebClient 连接池自带并发上限。
 *
 * @author leoli
 */
@Slf4j
@Component
public class TokenUsageReporter {

    private static final String TOKEN_HEADER = InternalApiConstants.TOKEN_HEADER;
    private static final String RECORD_PATH = "/api/token-quota/internal/record-usage";

    @Value("${gateway.admin.url:http://127.0.0.1:9090}")
    private String adminUrl;

    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}")
    private String internalToken;

    private final WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(3))))
            .build();

    /**
     * Dropped-report counter. Fire-and-forget is still the right posture (metering must never
     * 502 the proxy), but silent loss left quota (Redis) and usage-detail (admin) books drifting
     * apart with no way to notice. Exposing the failure count lets ops alert on it and reconcile.
     */
    private final Counter reportFailures;
    private final AtomicLong failureCount = new AtomicLong();

    public TokenUsageReporter(MeterRegistry meterRegistry) {
        this.reportFailures = Counter.builder("token.usage.report.failures")
                .description("Token usage reports to admin that failed and were dropped")
                .register(meterRegistry);
    }

    /**
     * 异步上报一条用量记录；任何失败都吞掉（计量是旁路，不允许反噬代理），
     * 但失败会计入 {@code token.usage.report.failures} 指标并按 1/100 采样打 warn，
     * 避免 admin 不可用期间用量静默蒸发而无人察觉。
     *
     * @param usageData 与 admin TokenQuotaService.recordTokenUsage 的字段契约一致
     */
    public void report(Map<String, Object> usageData) {
        if (internalToken == null || internalToken.isBlank()) {
            return;
        }
        try {
            webClient.post()
                    .uri(adminUrl.replaceAll("/+$", "") + RECORD_PATH)
                    .header(TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(usageData)
                    .retrieve()
                    .toBodilessEntity()
                    .subscribe(
                            resp -> log.debug("Token usage reported: tenant={}", usageData.get("tenantId")),
                            err -> recordFailure("report failed", err.getMessage(), usageData));
        } catch (Exception e) {
            recordFailure("report rejected", e.getMessage(), usageData);
        }
    }

    private void recordFailure(String what, String message, Map<String, Object> usageData) {
        reportFailures.increment();
        long n = failureCount.incrementAndGet();
        // Sample the warn (1st, then every 100th) so an admin outage doesn't flood the log,
        // while the metric still counts every single drop.
        if (n == 1 || n % 100 == 0) {
            log.warn("Token usage {} (ignored, total dropped={}): tenant={}, err={}",
                    what, n, usageData.get("tenantId"), message);
        } else {
            log.debug("Token usage {} (ignored): {}", what, message);
        }
    }

    /** 供测试断言用的开关态：是否配置了 internal-token。 */
    boolean enabled() {
        return internalToken != null && !internalToken.isBlank();
    }
}
