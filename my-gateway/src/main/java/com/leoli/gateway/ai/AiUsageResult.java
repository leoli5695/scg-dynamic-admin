package com.leoli.gateway.ai;

/** Immutable metering decision. Unknown usage must never masquerade as a confirmed zero. */
public record AiUsageResult(Status status, Long inputTokens, Long outputTokens, Long totalTokens,
                            Long chargedTokens, long reservedTokens, boolean interrupted) {
    public enum Status { CONFIRMED, ESTIMATED, UNKNOWN, NOT_BILLED }

    public static final String RESULT_ATTR = "ai.usage.result";
    public static final String COMPLETION_ATTR = "ai.usage.completion";

    public static java.util.concurrent.CompletableFuture<AiUsageResult> completion(
            org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getAttribute(COMPLETION_ATTR);
    }

    public void publish(org.springframework.web.server.ServerWebExchange exchange) {
        Object previous = exchange.getAttributes().putIfAbsent(RESULT_ATTR, this);
        var completion = completion(exchange);
        if (completion != null) completion.complete(previous instanceof AiUsageResult result ? result : this);
    }
}
