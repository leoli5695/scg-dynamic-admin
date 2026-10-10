package com.leoli.gateway.filter.token;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.time.Duration;
import java.util.Map;

/**
 * 把已结算的 token 用量异步上报给 admin（POST /api/token-quota/internal/record-usage），
 * 供控制台的 Token 计量可观测页按 路由/租户/模型/实例/时间 维度统计。
 * <p>
 * 设计约束：计量上报绝不能影响代理路径——fire-and-forget、失败静默（仅 debug 日志）、
 * 未配 internal-token 时整体关闭（与 AdminProviderKeyResolver 同一 posture）。
 * 连接/响应超时短（2s/3s），避免 admin 慢拖住网关资源；WebClient 连接池自带并发上限。
 *
 * @author leoli
 */
@Slf4j
@Component
public class TokenUsageReporter {

    private static final String TOKEN_HEADER = "X-Internal-Token";
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
     * 异步上报一条用量记录；任何失败都吞掉（计量是旁路，不允许反噬代理）。
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
                            err -> log.debug("Token usage report failed (ignored): {}", err.getMessage()));
        } catch (Exception e) {
            log.debug("Token usage report rejected (ignored): {}", e.getMessage());
        }
    }

    /** 供测试断言用的开关态：是否配置了 internal-token。 */
    boolean enabled() {
        return internalToken != null && !internalToken.isBlank();
    }
}
