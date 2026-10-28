package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.entity.TokenQuotaEntity;
import com.leoli.gateway.admin.entity.TokenUsageHistoryEntity;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.TokenQuotaRepository;
import com.leoli.gateway.admin.repository.TokenUsageHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Token Quota Service.
 * <p>
 * Responsibilities:
 * - CRUD operations for tenant quotas
 * - Redis quota status queries
 * - Usage history recording and analysis
 * - Quota adjustment and reset
 * - Periodic quota reset tasks
 * - Alert notification when approaching limit
 *
 * @author leoli
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenQuotaService {

    private final TokenQuotaRepository tokenQuotaRepository;
    private final TokenUsageHistoryRepository tokenUsageHistoryRepository;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final AuditLogService auditLogService;
    private final ConfigCenterService configCenterService;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate usageJdbc;

    // Redis template (optional, may not be available in admin service)
    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_QUOTA_PREFIX = "token_quota:";
    private static final String STRATEGY_PREFIX = "config.gateway.strategy-";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ============== CRUD Operations ==============

    /**
     * Get all tenant quotas.
     */
    public List<TokenQuotaEntity> getAllQuotas() {
        return tokenQuotaRepository.findAll();
    }

    /**
     * Get quota by tenant ID.
     */
    public TokenQuotaEntity getQuotaByTenantId(String tenantId) {
        return tokenQuotaRepository.findByTenantId(tenantId).orElse(null);
    }

    /**
     * Create new tenant quota.
     */
    @Transactional
    public TokenQuotaEntity createQuota(TokenQuotaEntity quota, String operator) {
        // Validate
        if (quota.getTenantId() == null || quota.getTenantId().isEmpty()) {
            throw new IllegalArgumentException("Tenant ID is required");
        }

        if (tokenQuotaRepository.existsByTenantId(quota.getTenantId())) {
            throw new IllegalArgumentException("Quota already exists for tenant: " + quota.getTenantId());
        }

        // Set defaults
        if (quota.getMonthlyQuota() == null) {
            quota.setMonthlyQuota(1000000L);  // 1M tokens
        }
        if (quota.getDailyQuota() == null) {
            quota.setDailyQuota(50000L);  // 50K tokens
        }
        if (quota.getQuotaPeriod() == null) {
            quota.setQuotaPeriod("BOTH");
        }
        if (quota.getResponseFormat() == null) {
            quota.setResponseFormat("OPENAI");
        }
        if (quota.getAlertThreshold() == null) {
            quota.setAlertThreshold(80);
        }
        if (quota.getEnabled() == null) {
            quota.setEnabled(true);
        }

        TokenQuotaEntity saved = tokenQuotaRepository.save(quota);

        // Publish to Nacos as TOKEN_RATE_LIMITER strategy
        publishQuotaToNacos(saved);

        // Log audit
        auditLogService.recordAuditLog(operator, "CREATE", "TOKEN_QUOTA", saved.getTenantId(),
                "Created token quota: monthly=" + saved.getMonthlyQuota() + ", daily=" + saved.getDailyQuota(), null);

        log.info("Created token quota for tenant: {}", saved.getTenantId());
        return saved;
    }

    /**
     * Update tenant quota.
     */
    @Transactional
    public TokenQuotaEntity updateQuota(String tenantId, TokenQuotaEntity quota, String operator) {
        TokenQuotaEntity existing = tokenQuotaRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found for tenant: " + tenantId));

        // Update fields
        if (quota.getMonthlyQuota() != null) {
            existing.setMonthlyQuota(quota.getMonthlyQuota());
        }
        if (quota.getDailyQuota() != null) {
            existing.setDailyQuota(quota.getDailyQuota());
        }
        if (quota.getBurstQuota() != null) {
            existing.setBurstQuota(quota.getBurstQuota());
        }
        if (quota.getQuotaPeriod() != null) {
            existing.setQuotaPeriod(quota.getQuotaPeriod());
        }
        if (quota.getResponseFormat() != null) {
            existing.setResponseFormat(quota.getResponseFormat());
        }
        if (quota.getAlertThreshold() != null) {
            existing.setAlertThreshold(quota.getAlertThreshold());
        }
        if (quota.getTenantName() != null) {
            existing.setTenantName(quota.getTenantName());
        }
        if (quota.getContactEmail() != null) {
            existing.setContactEmail(quota.getContactEmail());
        }
        if (quota.getNotes() != null) {
            existing.setNotes(quota.getNotes());
        }

        TokenQuotaEntity saved = tokenQuotaRepository.save(existing);

        // Publish updated config to Nacos
        publishQuotaToNacos(saved);

        // Log audit
        auditLogService.recordAuditLog(operator, "UPDATE", "TOKEN_QUOTA", tenantId,
                "Updated token quota: monthly=" + saved.getMonthlyQuota() + ", daily=" + saved.getDailyQuota(), null);

        log.info("Updated token quota for tenant: {}", tenantId);
        return saved;
    }

    /**
     * Delete tenant quota.
     */
    @Transactional
    public void deleteQuota(String tenantId, String operator) {
        TokenQuotaEntity existing = tokenQuotaRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found for tenant: " + tenantId));

        tokenQuotaRepository.delete(existing);

        // Remove from Nacos
        removeQuotaFromNacos(tenantId);

        // Log audit
        auditLogService.recordAuditLog(operator, "DELETE", "TOKEN_QUOTA", tenantId,
                "Deleted token quota", null);

        log.info("Deleted token quota for tenant: {}", tenantId);
    }

    /**
     * Enable/disable quota.
     */
    @Transactional
    public void setQuotaEnabled(String tenantId, boolean enabled, String operator) {
        TokenQuotaEntity existing = tokenQuotaRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found for tenant: " + tenantId));

        existing.setEnabled(enabled);
        tokenQuotaRepository.save(existing);

        // Publish updated config to Nacos
        publishQuotaToNacos(existing);

        // Log audit
        auditLogService.recordAuditLog(operator, enabled ? "ENABLE" : "DISABLE", "TOKEN_QUOTA", tenantId,
                "Quota " + (enabled ? "enabled" : "disabled"), null);

        log.info("Quota {} for tenant: {}", enabled ? "enabled" : "disabled", tenantId);
    }

    // ============== Quota Status Operations ==============

    /**
     * Get real-time quota status from Redis.
     */
    public Map<String, Object> getQuotaStatus(String tenantId) {
        TokenQuotaEntity quota = getQuotaByTenantId(tenantId);
        if (quota == null) {
            throw new IllegalArgumentException("Quota not found for tenant: " + tenantId);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("tenantId", tenantId);
        status.put("enabled", quota.getEnabled());

        String currentMonth = LocalDate.now().format(MONTH_FORMATTER);
        String currentDay = LocalDate.now().format(DAY_FORMATTER);

        // Monthly status
        if ("MONTHLY".equalsIgnoreCase(quota.getQuotaPeriod()) || "BOTH".equalsIgnoreCase(quota.getQuotaPeriod())) {
            long monthlyUsed = getRedisValue(TOKEN_QUOTA_PREFIX + "monthly:" + tenantId + ":" + currentMonth + ":used", 0L);
            long monthlyQuota = quota.getMonthlyQuota();
            long monthlyRemaining = Math.max(0, monthlyQuota - monthlyUsed);
            double monthlyPercent = monthlyQuota > 0 ? (monthlyUsed * 100.0 / monthlyQuota) : 0;

            status.put("monthlyQuota", monthlyQuota);
            status.put("monthlyUsed", monthlyUsed);
            status.put("monthlyRemaining", monthlyRemaining);
            status.put("monthlyUsagePercent", Math.round(monthlyPercent * 100) / 100.0);
        }

        // Daily status
        if ("DAILY".equalsIgnoreCase(quota.getQuotaPeriod()) || "BOTH".equalsIgnoreCase(quota.getQuotaPeriod())) {
            long dailyUsed = getRedisValue(TOKEN_QUOTA_PREFIX + "daily:" + tenantId + ":" + currentDay + ":used", 0L);
            long dailyQuota = quota.getDailyQuota();
            long dailyRemaining = Math.max(0, dailyQuota - dailyUsed);
            double dailyPercent = dailyQuota > 0 ? (dailyUsed * 100.0 / dailyQuota) : 0;

            status.put("dailyQuota", dailyQuota);
            status.put("dailyUsed", dailyUsed);
            status.put("dailyRemaining", dailyRemaining);
            status.put("dailyUsagePercent", Math.round(dailyPercent * 100) / 100.0);
        }

        // Alert status
        status.put("alertThreshold", quota.getAlertThreshold());
        status.put("alertStatus", getAlertStatus(quota, status));

        status.put("periodStart", getPeriodStart(quota.getQuotaPeriod()));
        status.put("periodEnd", getPeriodEnd(quota.getQuotaPeriod()));

        return status;
    }

    // ============== Quota Adjustment Operations ==============

    /**
     * Adjust quota dynamically.
     */
    @Transactional
    public Map<String, Object> adjustQuota(String tenantId, long monthlyDelta, long dailyDelta, String operator) {
        TokenQuotaEntity quota = tokenQuotaRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found for tenant: " + tenantId));

        long newMonthly = quota.getMonthlyQuota() + monthlyDelta;
        long newDaily = quota.getDailyQuota() + dailyDelta;

        if (newMonthly < 0 || newDaily < 0) {
            throw new IllegalArgumentException("Quota cannot be negative after adjustment");
        }

        quota.setMonthlyQuota(newMonthly);
        quota.setDailyQuota(newDaily);
        tokenQuotaRepository.save(quota);

        // Update Redis quota values
        if (redisTemplate != null) {
            String currentMonth = LocalDate.now().format(MONTH_FORMATTER);
            String currentDay = LocalDate.now().format(DAY_FORMATTER);

            if (newMonthly > 0) {
                String monthlyTotalKey = TOKEN_QUOTA_PREFIX + "monthly:" + tenantId + ":" + currentMonth + ":total";
                redisTemplate.opsForValue().set(monthlyTotalKey, String.valueOf(newMonthly));
            }
            if (newDaily > 0) {
                String dailyTotalKey = TOKEN_QUOTA_PREFIX + "daily:" + tenantId + ":" + currentDay + ":total";
                redisTemplate.opsForValue().set(dailyTotalKey, String.valueOf(newDaily));
            }
        }

        // Publish to Nacos
        publishQuotaToNacos(quota);

        // Log audit
        auditLogService.recordAuditLog(operator, "ADJUST", "TOKEN_QUOTA", tenantId,
                "Adjusted quota: monthlyDelta=" + monthlyDelta + ", dailyDelta=" + dailyDelta +
                        ", newMonthly=" + newMonthly + ", newDaily=" + newDaily, null);

        Map<String, Object> result = new HashMap<>();
        result.put("tenantId", tenantId);
        result.put("monthlyDelta", monthlyDelta);
        result.put("dailyDelta", dailyDelta);
        result.put("newMonthlyQuota", newMonthly);
        result.put("newDailyQuota", newDaily);

        log.info("Adjusted quota for tenant {}: monthly={}, daily={}", tenantId, newMonthly, newDaily);
        return result;
    }

    /**
     * Reset quota (clear used tokens).
     */
    @Transactional
    public void resetQuota(String tenantId, String periodType, String operator) {
        TokenQuotaEntity quota = tokenQuotaRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found for tenant: " + tenantId));

        if (redisTemplate != null) {
            String currentMonth = LocalDate.now().format(MONTH_FORMATTER);
            String currentDay = LocalDate.now().format(DAY_FORMATTER);

            if ("MONTHLY".equalsIgnoreCase(periodType) || "BOTH".equalsIgnoreCase(periodType)) {
                String monthlyUsedKey = TOKEN_QUOTA_PREFIX + "monthly:" + tenantId + ":" + currentMonth + ":used";
                redisTemplate.delete(monthlyUsedKey);
            }

            if ("DAILY".equalsIgnoreCase(periodType) || "BOTH".equalsIgnoreCase(periodType)) {
                String dailyUsedKey = TOKEN_QUOTA_PREFIX + "daily:" + tenantId + ":" + currentDay + ":used";
                redisTemplate.delete(dailyUsedKey);
            }
        }

        // Log audit
        auditLogService.recordAuditLog(operator, "RESET", "TOKEN_QUOTA", tenantId,
                "Reset quota: periodType=" + periodType, null);

        log.info("Reset quota for tenant {}: periodType={}", tenantId, periodType);
    }

    // ============== Usage History Operations ==============

    /**
     * Get usage history for a tenant.
     */
    public List<TokenUsageHistoryEntity> getUsageHistory(String tenantId, LocalDateTime startTime, LocalDateTime endTime) {
        return tokenUsageHistoryRepository.findByTenantIdAndRequestTimeBetween(tenantId, startTime, endTime);
    }

    /**
     * Get usage statistics.
     */
    public Map<String, Object> getUsageStatistics(String tenantId, LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> stats = new LinkedHashMap<>();

        Long totalTokens = tokenUsageHistoryRepository.sumTotalTokensByTenantAndTimeRange(tenantId, startTime, endTime);
        if (totalTokens == null) {
            totalTokens = 0L;
        }

        List<TokenUsageHistoryEntity> history = getUsageHistory(tenantId, startTime, endTime);

        int totalRequests = history.size();
        int totalPromptTokens = history.stream().mapToInt(h -> h.getPromptTokens() != null ? h.getPromptTokens() : 0).sum();
        int totalCompletionTokens = history.stream().mapToInt(h -> h.getCompletionTokens() != null ? h.getCompletionTokens() : 0).sum();

        stats.put("tenantId", tenantId);
        stats.put("startTime", startTime);
        stats.put("endTime", endTime);
        stats.put("totalRequests", totalRequests);
        stats.put("totalTokens", totalTokens);
        stats.put("totalPromptTokens", totalPromptTokens);
        stats.put("totalCompletionTokens", totalCompletionTokens);
        stats.put("avgTokensPerRequest", totalRequests > 0 ? totalTokens / totalRequests : 0);

        return stats;
    }

    /**
     * Get daily usage summary.
     */
    public List<Map<String, Object>> getDailyUsageSummary(String tenantId, LocalDateTime startTime) {
        List<Object[]> results = tokenUsageHistoryRepository.getDailyUsageSummary(tenantId, startTime);

        return results.stream().map(row -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("date", row[0]);
            entry.put("totalTokens", row[1]);
            return entry;
        }).collect(Collectors.toList());
    }

    /**
     * Get model usage breakdown.
     */
    public List<Map<String, Object>> getModelUsageBreakdown(String tenantId, LocalDateTime startTime, LocalDateTime endTime) {
        List<Object[]> results = tokenUsageHistoryRepository.getModelUsageBreakdown(tenantId, startTime, endTime);

        return results.stream().map(row -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("model", row[0] != null ? row[0] : "unknown");
            entry.put("totalTokens", row[1]);
            entry.put("requestCount", row[2]);
            return entry;
        }).collect(Collectors.toList());
    }

    /**
     * Record token usage from Gateway.
     */
    @Transactional
    public void recordTokenUsage(Map<String, Object> usageData) {
        if (usageData.get("eventId") != null) {
            recordTokenUsageBatch(List.of(usageData));
            return;
        }
        String tenantId = (String) usageData.get("tenantId");
        if (tenantId == null || tenantId.isEmpty()) {
            log.warn("No tenantId in usage data, skipping recording");
            return;
        }

        TokenUsageHistoryEntity history = new TokenUsageHistoryEntity();
        history.setTenantId(tenantId);
        history.setTraceId((String) usageData.get("traceId"));
        history.setRouteId((String) usageData.get("routeId"));
        history.setModel((String) usageData.get("model"));
        history.setUsageStatus((String) usageData.getOrDefault("usageStatus", "LEGACY"));
        history.setSettlementStatus((String) usageData.getOrDefault("settlementStatus", "LEGACY"));
        history.setProvider((String) usageData.get("provider"));
        history.setServiceId((String) usageData.get("serviceId"));
        history.setChargedTokens(asLong(usageData.get("chargedTokens")));
        history.setReservedTokens(asLong(usageData.get("reservedTokens")));
        history.setQuotaMonth((String) usageData.get("quotaMonth"));
        history.setQuotaDay((String) usageData.get("quotaDay"));
        history.setQuotaPeriod((String) usageData.get("quotaPeriod"));
        history.setQuotaLedgerId((String) usageData.get("quotaLedgerId"));
        history.setQuotaLedgerState((String) usageData.get("quotaLedgerState"));

        Integer promptTokens = asInt(usageData.get("promptTokens"));
        Integer completionTokens = asInt(usageData.get("completionTokens"));
        Integer totalTokens = asInt(usageData.get("totalTokens"));

        history.setPromptTokens(promptTokens != null ? promptTokens : 0);
        history.setCompletionTokens(completionTokens != null ? completionTokens : 0);
        history.setTotalTokens(totalTokens != null ? totalTokens : 0);

        history.setResponseFormat((String) usageData.get("responseFormat"));
        history.setInstanceId((String) usageData.get("instanceId"));

        Long latencyMs = asLong(usageData.get("latencyMs"));
        history.setLatencyMs(latencyMs);

        Object requestTimeObj = usageData.get("requestTime");
        if (requestTimeObj instanceof String) {
            history.setRequestTime(LocalDateTime.parse((String) requestTimeObj));
        } else if (requestTimeObj instanceof LocalDateTime) {
            history.setRequestTime((LocalDateTime) requestTimeObj);
        } else {
            history.setRequestTime(LocalDateTime.now());
        }

        tokenUsageHistoryRepository.save(history);
        log.debug("Recorded token usage for tenant {}: total={}", tenantId, history.getTotalTokens());
    }

    /** Fast JDBC batch with event-ID deduplication on replay or concurrent delivery. */
    @Transactional
    public void recordTokenUsageBatch(List<Map<String, Object>> records) {
        if (records.isEmpty() || records.size() > 32) throw new IllegalArgumentException("Batch must contain 1..32 records");
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> row : records) {
            if (!(row.get("tenantId") instanceof String tenant) || tenant.isBlank())
                throw new IllegalArgumentException("tenantId required");
            Object eventId = row.get("eventId");
            if (eventId != null && (!(eventId instanceof String text) || text.isBlank() || text.length() > 64))
                throw new IllegalArgumentException("eventId must be a nonblank string of at most 64 characters");
            if (row.get("quotaLedgerId") != null) {
                if (!Objects.equals(eventId,row.get("quotaLedgerId"))
                        || !Set.of("PENDING","READY").contains(Objects.toString(row.get("quotaLedgerState"),"")))
                    throw new IllegalArgumentException("Managed quota events require matching ledger/event IDs and a valid ledger state");
                UUID.fromString(eventId.toString());
            }
            Object time = row.get("requestTime");
            LocalDateTime requestTime = time instanceof String text ? LocalDateTime.parse(text)
                    : time instanceof LocalDateTime date ? date : now;
            rows.add(new Object[]{tenant, row.get("traceId"), row.get("routeId"), row.get("model"),
                    Optional.ofNullable(asInt(row.get("promptTokens"))).orElse(0),
                    Optional.ofNullable(asInt(row.get("completionTokens"))).orElse(0),
                    Optional.ofNullable(asInt(row.get("totalTokens"))).orElse(0), row.get("instanceId"),
                    row.get("responseFormat"), asLong(row.get("latencyMs")), requestTime, now,
                    row.getOrDefault("usageStatus", "LEGACY"), row.getOrDefault("settlementStatus", "LEGACY"),
                    row.get("provider"), row.get("serviceId"), asLong(row.get("chargedTokens")), asLong(row.get("reservedTokens")), eventId,
                    row.get("quotaMonth"), row.get("quotaDay"), row.get("quotaPeriod"), row.get("quotaLedgerId"), row.get("quotaLedgerState")});
        }
        String insert = "INSERT INTO token_usage_history (tenant_id,trace_id,route_id,model,prompt_tokens,"
                + "completion_tokens,total_tokens,instance_id,response_format,latency_ms,request_time,created_at,"
                + "usage_status,settlement_status,provider,service_id,charged_tokens,reserved_tokens,event_id,quota_month,quota_day,quota_period,quota_ledger_id,quota_ledger_state)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        List<Object[]> events = rows.stream().filter(row -> row[18] != null).toList();
        List<Object[]> legacy = rows.stream().filter(row -> row[18] == null).toList();
        try {
            if (!events.isEmpty()) usageJdbc.batchUpdate(insert, events);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // A JDBC batch may have already inserted some rows in this transaction. Locking reads
            // also see concurrent commits under MySQL REPEATABLE READ, unlike snapshot reads.
            for (Object[] row : events) {
                if (alreadyRecorded(row)) continue;
                try { usageJdbc.update(insert, row); }
                catch (org.springframework.dao.DuplicateKeyException collision) {
                    if (!alreadyRecorded(row)) throw collision;
                }
            }
        }
        // Legacy records have no replay identity. Insert them once, after the replayable batch.
        if (!legacy.isEmpty()) usageJdbc.batchUpdate(insert, legacy);
    }

    private boolean alreadyRecorded(Object[] row) {
        if (row[18] == null) return false;
        List<Map<String,Object>> stored = usageJdbc.queryForList(
                "SELECT * FROM token_usage_history WHERE event_id = ? FOR UPDATE", row[18]);
        if (stored.isEmpty()) return false;
        Map<String,Object> existing = stored.getFirst();
        if (!Objects.equals(existing.get("tenant_id"), row[0])) throw new IllegalArgumentException("eventId belongs to another tenant");
        if (row[22] != null && "READY".equals(row[23])) {
            if ("PENDING".equals(existing.get("quota_ledger_state")) && Objects.equals(row[22],existing.get("quota_ledger_id"))
                    && (!Objects.equals(existing.get("usage_status"),row[12])
                    || !Objects.equals(asLong(existing.get("charged_tokens")),asLong(row[16]))
                    || !Objects.equals(asLong(existing.get("reserved_tokens")),asLong(row[17]))))
                throw new IllegalArgumentException("Final quota intent cannot change during replay");
            // Delivery can be reordered. Only advance the same managed event; never overwrite manual reconciliation.
            usageJdbc.update("UPDATE token_usage_history SET quota_ledger_state='READY',settlement_status=? "
                            + "WHERE event_id=? AND tenant_id=? AND quota_ledger_id=? AND quota_ledger_state='PENDING' "
                            + "AND (usage_status IS NULL OR usage_status <> 'RECONCILED')",
                    row[13],row[18],row[0],row[22]);
        }
        return true;
    }

    /** JSON 数字进 Map 后类型不定（Integer/Long），统一按 Number 取，避免强转 ClassCastException。 */
    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    // ============== Alert Operations ==============

    /**
     * Get tenants approaching quota limit.
     */
    public List<Map<String, Object>> getTenantsApproachingLimit(int thresholdPercent) {
        List<TokenQuotaEntity> quotas = tokenQuotaRepository.findAllEnabledWithAlertConfig();
        List<Map<String, Object>> result = new ArrayList<>();

        for (TokenQuotaEntity quota : quotas) {
            try {
                Map<String, Object> status = getQuotaStatus(quota.getTenantId());

                Double monthlyPercent = (Double) status.get("monthlyUsagePercent");
                Double dailyPercent = (Double) status.get("dailyUsagePercent");

                boolean approaching = false;
                if (monthlyPercent != null && monthlyPercent >= thresholdPercent) {
                    approaching = true;
                }
                if (dailyPercent != null && dailyPercent >= thresholdPercent) {
                    approaching = true;
                }

                if (approaching) {
                    status.put("tenantName", quota.getTenantName());
                    status.put("contactEmail", quota.getContactEmail());
                    result.add(status);
                }
            } catch (Exception e) {
                log.warn("Failed to get status for tenant {}: {}", quota.getTenantId(), e.getMessage());
            }
        }

        return result;
    }

    // ============== Scheduled Tasks ==============

    /**
     * Reset daily quotas at midnight.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void resetDailyQuotas() {
        log.info("Starting daily quota reset task");

        List<TokenQuotaEntity> quotas = tokenQuotaRepository.findByEnabledTrue();
        for (TokenQuotaEntity quota : quotas) {
            try {
                resetQuota(quota.getTenantId(), "DAILY", "SYSTEM");
            } catch (Exception e) {
                log.error("Failed to reset daily quota for tenant {}: {}", quota.getTenantId(), e.getMessage());
            }
        }

        log.info("Daily quota reset completed for {} tenants", quotas.size());
    }

    /**
     * Reset monthly quotas at start of month.
     */
    @Scheduled(cron = "0 0 0 1 * ?")
    public void resetMonthlyQuotas() {
        log.info("Starting monthly quota reset task");

        List<TokenQuotaEntity> quotas = tokenQuotaRepository.findByEnabledTrue();
        for (TokenQuotaEntity quota : quotas) {
            try {
                resetQuota(quota.getTenantId(), "MONTHLY", "SYSTEM");
            } catch (Exception e) {
                log.error("Failed to reset monthly quota for tenant {}: {}", quota.getTenantId(), e.getMessage());
            }
        }

        log.info("Monthly quota reset completed for {} tenants", quotas.size());
    }

    /**
     * Cleanup old usage history (older than 90 days).
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldHistory() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        tokenUsageHistoryRepository.deleteByRequestTimeBefore(cutoff);
        log.info("Cleaned up token usage history older than {}", cutoff);
    }

    // ============== Helper Methods ==============

    private long getRedisValue(String key, long defaultValue) {
        if (redisTemplate == null) {
            return defaultValue;
        }
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    private String getAlertStatus(TokenQuotaEntity quota, Map<String, Object> status) {
        Double monthlyPercent = (Double) status.get("monthlyUsagePercent");
        Double dailyPercent = (Double) status.get("dailyUsagePercent");

        int threshold = quota.getAlertThreshold();

        double maxPercent = 0;
        if (monthlyPercent != null) {
            maxPercent = Math.max(maxPercent, monthlyPercent);
        }
        if (dailyPercent != null) {
            maxPercent = Math.max(maxPercent, dailyPercent);
        }

        if (maxPercent >= 95) {
            return "CRITICAL";
        } else if (maxPercent >= threshold) {
            return "WARNING";
        } else if (maxPercent >= threshold * 0.8) {
            return "APPROACHING";
        } else {
            return "NORMAL";
        }
    }

    private LocalDateTime getPeriodStart(String quotaPeriod) {
        if ("MONTHLY".equalsIgnoreCase(quotaPeriod) || "BOTH".equalsIgnoreCase(quotaPeriod)) {
            return LocalDate.now().withDayOfMonth(1).atStartOfDay();
        }
        return LocalDate.now().atStartOfDay();
    }

    private LocalDateTime getPeriodEnd(String quotaPeriod) {
        if ("MONTHLY".equalsIgnoreCase(quotaPeriod) || "BOTH".equalsIgnoreCase(quotaPeriod)) {
            LocalDate today = LocalDate.now();
            return today.withDayOfMonth(today.lengthOfMonth()).atTime(23, 59, 59);
        }
        return LocalDate.now().atTime(23, 59, 59);
    }

    /**
     * Publish quota config to Nacos as TOKEN_RATE_LIMITER strategy.
     */
    private void publishQuotaToNacos(TokenQuotaEntity quota) {
        try {
            String strategyId = "token-quota-" + quota.getTenantId();
            String dataId = STRATEGY_PREFIX + strategyId;

            // Build strategy config
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("enabled", quota.getEnabled());
            config.put("tenantQuotas", Map.of(quota.getTenantId(), Map.of(
                    "monthlyQuota", quota.getMonthlyQuota(),
                    "dailyQuota", quota.getDailyQuota(),
                    "burstQuota", quota.getBurstQuota() != null ? quota.getBurstQuota() : 0,
                    "quotaPeriod", quota.getQuotaPeriod()
            )));
            config.put("defaultMonthlyQuota", 1000000L);
            config.put("defaultDailyQuota", 50000L);
            config.put("quotaPeriod", quota.getQuotaPeriod());
            config.put("responseFormat", quota.getResponseFormat());
            config.put("alertThresholdPercent", quota.getAlertThreshold());

            Map<String, Object> strategy = new LinkedHashMap<>();
            strategy.put("strategyId", strategyId);
            strategy.put("strategyName", "Token Quota for " + quota.getTenantId());
            strategy.put("strategyType", "TOKEN_RATE_LIMITER");
            strategy.put("scope", "GLOBAL");
            strategy.put("enabled", quota.getEnabled());
            strategy.put("config", config);

            // Broadcast to all gateway namespaces
            List<GatewayInstanceEntity> instances = gatewayInstanceRepository.findAll();
            for (GatewayInstanceEntity instance : instances) {
                String namespace = instance.getNacosNamespace();
                if (namespace != null && !namespace.isEmpty()) {
                    configCenterService.publishConfig(dataId, namespace, strategy);
                    log.debug("Published token quota strategy to namespace: {}", namespace);
                }
            }

            log.info("Published token quota config for tenant {} to Nacos", quota.getTenantId());

        } catch (Exception e) {
            log.error("Failed to publish quota to Nacos: {}", e.getMessage());
        }
    }

    /**
     * Remove quota config from Nacos.
     */
    private void removeQuotaFromNacos(String tenantId) {
        try {
            String strategyId = "token-quota-" + tenantId;
            String dataId = STRATEGY_PREFIX + strategyId;

            List<GatewayInstanceEntity> instances = gatewayInstanceRepository.findAll();
            for (GatewayInstanceEntity instance : instances) {
                String namespace = instance.getNacosNamespace();
                if (namespace != null && !namespace.isEmpty()) {
                    configCenterService.removeConfig(dataId, namespace);
                }
            }

            log.info("Removed token quota config for tenant {} from Nacos", tenantId);

        } catch (Exception e) {
            log.error("Failed to remove quota from Nacos: {}", e.getMessage());
        }
    }
}
