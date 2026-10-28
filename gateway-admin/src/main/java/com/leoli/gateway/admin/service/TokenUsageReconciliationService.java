package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicitly verified per-bucket corrections, persisted before any Redis adjustment. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenUsageReconciliationService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper mapper;

    public record Correction(
            @NotNull @Min(0) @Max(2147483647) Integer promptTokens,
            @NotNull @Min(0) @Max(2147483647) Integer completionTokens,
            @NotNull @Min(0) @Max(2147483647) Integer totalTokens,
            @NotNull @Min(0) @Max(2147483647) Long chargedTokens,
            @NotNull @Min(-2147483647) @Max(2147483647) Long monthlyDelta,
            @NotNull @Min(-2147483647) @Max(2147483647) Long dailyDelta,
            @NotBlank @Size(max = 1000) String reason) { }

    // Validate both buckets before writes: Redis Lua does not roll back runtime errors.
    // Receipts deliberately have no TTL: an unacknowledged SQL commit may be retried indefinitely.
    static final DefaultRedisScript<Long> APPLY = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) then return 0 end
            local deltas = {tonumber(ARGV[1]), tonumber(ARGV[2])}
            for i=1,2 do
              if deltas[i] ~= 0 then
                local raw = redis.call('GET', KEYS[i+1])
                if not raw then return -2 end
                if raw ~= '0' and not string.match(raw, '^[1-9]%d*$') then return -2 end
                local value = tonumber(raw)
                if not value or value + deltas[i] < 0 or value + deltas[i] > 9007199254740991 then return -2 end
              end
            end
            redis.call('SET', KEYS[1], '1')
            for i=1,2 do
              if deltas[i] ~= 0 then redis.call('INCRBY', KEYS[i+1], deltas[i]) end
            end
            return 1
            """, Long.class);

    @Transactional
    public Map<String, Object> create(long usageId, Correction correction, String operator) {
        validate(correction);
        Map<String, Object> usage = usage(usageId, true);
        if (!text(usage,"quota_ledger_id").isEmpty() && !"READY".equals(text(usage,"quota_ledger_state")))
            throw new IllegalStateException("Request ledger is still replaying; wait for a confirmed or stopped settlement before manual correction");
        var existing = jdbc.queryForList("SELECT * FROM token_usage_reconciliation WHERE usage_id=?", usageId);
        if (!existing.isEmpty()) {
            Map<String, Object> command = existing.getFirst();
            if (!sameCorrection(command, correction)) throw new IllegalStateException("A different correction already exists for this usage event");
            return view(command);
        }
        if (!(Set.of("UNKNOWN", "ESTIMATED").contains(text(usage, "usage_status"))
                || Set.of("PENDING", "FAILED").contains(text(usage, "settlement_status"))))
            throw new IllegalStateException("Only unresolved usage or failed settlement can be reconciled");
        if (correction.monthlyDelta() != 0 || correction.dailyDelta() != 0) {
            String month = text(usage, "quota_month"), day = text(usage, "quota_day"), period = text(usage, "quota_period");
            try {
                if (!month.matches("[0-9]{6}") || !day.matches("[0-9]{8}") || !day.startsWith(month)) throw new IllegalArgumentException();
                LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE);
            } catch (RuntimeException error) { throw new IllegalArgumentException("Missing or invalid original quota period; quota adjustment requires offline verification"); }
            if (correction.monthlyDelta() != 0 && !Set.of("MONTHLY", "BOTH").contains(period))
                throw new IllegalArgumentException("Monthly quota was not enabled for this request");
            if (correction.dailyDelta() != 0 && !Set.of("DAILY", "BOTH").contains(period))
                throw new IllegalArgumentException("Daily quota was not enabled for this request");
        }
        String id = UUID.randomUUID().toString();
        String original;
        try { original = mapper.writeValueAsString(usage); }
        catch (Exception error) { throw new IllegalStateException("Cannot preserve original usage", error); }
        jdbc.update("""
                INSERT INTO token_usage_reconciliation (id,usage_id,tenant_id,quota_month,quota_day,
                prompt_tokens,completion_tokens,total_tokens,charged_tokens,monthly_delta,daily_delta,
                status,operator,reason,original_usage,attempts,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,0,?)
                """, id, usageId, usage.get("tenant_id"), usage.get("quota_month"), usage.get("quota_day"),
                correction.promptTokens(), correction.completionTokens(), correction.totalTokens(), correction.chargedTokens(),
                correction.monthlyDelta(), correction.dailyDelta(), operator, correction.reason().trim(), original, LocalDateTime.now());
        return get(usageId);
    }

    public Map<String, Object> get(long usageId) {
        usage(usageId, false);
        var rows = jdbc.queryForList("SELECT * FROM token_usage_reconciliation WHERE usage_id=?", usageId);
        return rows.isEmpty() ? null : view(rows.getFirst());
    }

    public Map<String, Object> retry(long usageId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            // Always lock usage before its command, matching create() and avoiding lock inversion.
            usage(usageId, true);
            var rows = jdbc.queryForList("SELECT * FROM token_usage_reconciliation WHERE usage_id=? FOR UPDATE", usageId);
            if (rows.isEmpty()) throw new NoSuchElementException("No reconciliation exists for this usage event");
            Map<String, Object> command = rows.getFirst();
            if ("APPLIED".equals(command.get("status"))) return view(command);
            try {
                long monthly = number(command, "monthly_delta"), daily = number(command, "daily_delta");
                if (monthly != 0 || daily != 0) {
                    String tenant = text(command, "tenant_id");
                    Long applied = redis.execute(APPLY, List.of("token_quota:reconciliation:" + command.get("id"),
                                    "token_quota:monthly:" + tenant + ":" + command.get("quota_month") + ":used",
                                    "token_quota:daily:" + tenant + ":" + command.get("quota_day") + ":used"),
                            Long.toString(monthly), Long.toString(daily));
                    if (applied == null) throw new IllegalStateException("Redis returned no settlement acknowledgement");
                    if (applied == -2) {
                        jdbc.update("UPDATE token_usage_reconciliation SET status='REVIEW_REQUIRED',last_error=?,attempts=attempts+1 WHERE usage_id=?",
                                "Quota bucket is missing, invalid or cannot cover the verified refund; inspect before retry", usageId);
                        return get(usageId);
                    }
                    if (applied != 0 && applied != 1) throw new IllegalStateException("Unexpected Redis settlement acknowledgement");
                }
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                jdbc.update("UPDATE token_usage_reconciliation SET last_error=?,attempts=attempts+1 WHERE usage_id=?",
                        message.substring(0, Math.min(1000, message.length())), usageId);
                return get(usageId);
            }
            // SQL failures must escape and roll back. The Redis receipt makes the next attempt safe.
            jdbc.update("UPDATE token_usage_history SET prompt_tokens=?,completion_tokens=?,total_tokens=?,charged_tokens=?,"
                            + "usage_status='RECONCILED',settlement_status='APPLIED' WHERE id=?",
                    command.get("prompt_tokens"), command.get("completion_tokens"), command.get("total_tokens"), command.get("charged_tokens"), usageId);
            jdbc.update("UPDATE token_usage_reconciliation SET status='APPLIED',last_error=NULL,attempts=attempts+1,applied_at=? WHERE usage_id=?",
                    LocalDateTime.now(), usageId);
            return get(usageId);
        });
    }

    @Scheduled(fixedDelayString = "${gateway.token-usage.reconciliation-retry-ms:30000}")
    public void retryPending() {
        List<Long> ids = jdbc.queryForList("SELECT usage_id FROM token_usage_reconciliation WHERE status='PENDING' ORDER BY created_at LIMIT 32", Long.class);
        for (Long id : ids) {
            try { retry(id); }
            catch (Exception error) { log.warn("Usage reconciliation {} will be retried: {}", id, error.toString()); }
        }
    }

    private Map<String, Object> usage(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM token_usage_history WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new NoSuchElementException("Usage event not found");
        return rows.getFirst();
    }
    private static void validate(Correction c) {
        if (c == null || c.promptTokens() == null || c.completionTokens() == null || c.totalTokens() == null
                || c.chargedTokens() == null || c.monthlyDelta() == null || c.dailyDelta() == null
                || c.promptTokens() < 0 || c.completionTokens() < 0 || c.totalTokens() < 0 || c.chargedTokens() < 0
                || c.chargedTokens() > Integer.MAX_VALUE || c.monthlyDelta() < -Integer.MAX_VALUE || c.monthlyDelta() > Integer.MAX_VALUE
                || c.dailyDelta() < -Integer.MAX_VALUE || c.dailyDelta() > Integer.MAX_VALUE
                || c.reason() == null || c.reason().isBlank() || c.reason().length() > 1000)
            throw new IllegalArgumentException("Verified nonnegative usage, bounded adjustments and evidence are required");
        if ((long)c.promptTokens() + c.completionTokens() != c.totalTokens())
            throw new IllegalArgumentException("Total tokens must equal prompt plus completion tokens");
    }
    private static boolean sameCorrection(Map<String, Object> row, Correction c) {
        return number(row,"prompt_tokens") == c.promptTokens() && number(row,"completion_tokens") == c.completionTokens()
                && number(row,"total_tokens") == c.totalTokens() && number(row,"charged_tokens") == c.chargedTokens()
                && number(row,"monthly_delta") == c.monthlyDelta() && number(row,"daily_delta") == c.dailyDelta()
                && text(row,"reason").equals(c.reason().trim());
    }
    private static long number(Map<String, Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id","usage_id","tenant_id","quota_month","quota_day","prompt_tokens","completion_tokens",
                "total_tokens","charged_tokens","monthly_delta","daily_delta","status","operator","reason","original_usage","last_error","attempts","created_at","applied_at"))
            result.put(key, row.get(key));
        return result;
    }
}
