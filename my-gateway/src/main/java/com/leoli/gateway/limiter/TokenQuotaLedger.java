package com.leoli.gateway.limiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.config.RedisEnabledCondition;
import com.leoli.gateway.filter.token.TokenUsageReporter;
import com.leoli.gateway.model.TokenQuotaConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Durable per-request quota intent. Redis receipts protect both normal and fallback replay. */
@Component
@Conditional(RedisEnabledCondition.class)
@RequiredArgsConstructor
@Slf4j
public class TokenQuotaLedger {
    public static final String REQUEST_ATTR = "ai.quota.ledger.id";
    public static final String CONFIG_ATTR = "ai.quota.ledger.config";
    private static final long MAX_TOKENS = 9007199254740991L;
    private final StringRedisTemplate redis;
    private final RedisHealthChecker health;
    private final TokenUsageReporter reporter;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    @Value("${gateway.token-quota-ledger.directory:${user.dir}/data/token-quota/${gateway.instance-id:gateway-1}}")
    private String directory;
    @Value("${gateway.token-quota-ledger.max-pending:4096}") private int capacity = 4096;
    @Value("${gateway.token-quota-ledger.replay-hours:168}") private int replayHours = 168;
    @Value("${gateway.instance-id:gateway-1}") private String instanceId = "gateway-1";
    private Clock clock = Clock.systemDefaultZone();
    private TokenQuotaLedgerStore store;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger replayCursor = new java.util.concurrent.atomic.AtomicInteger();
    private final Object[] locks = java.util.stream.IntStream.range(0,256).mapToObj(i -> new Object()).toArray();
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message,cause); }
    }
    public record Admission(boolean allowed, long reserved, long monthlyRemaining, long dailyRemaining, boolean local) { }
    private record Baseline(long monthly, long daily, long observedAt) { }
    /** Public fields are the on-disk format; only immutable request context and intent are persisted. */
    public static class Entry {
        public String id,tenant,month,day,period,phase,backend,fingerprint;
        public boolean monthly,daily,pendingPublished;
        public long reserved,monthlyLimit,dailyLimit,monthExpiry,dayExpiry,deadline,createdAt,baselineMonthly,baselineDaily,baselineAt;
        public Long target;
        public Map<String,Object> payload = new LinkedHashMap<>();
    }

    @PostConstruct public void start() throws IOException {
        if (replayHours < 1 || replayHours > 720) throw new IllegalArgumentException("Replay hours must be 1..720");
        script.setLocation(new ClassPathResource("lua/token-quota-ledger.lua")); script.setResultType(List.class);
        store = new TokenQuotaLedgerStore(Path.of(directory),capacity,mapper);
        for (Entry entry : store.all()) {
            // PREPARING never returned admission to the proxy. ADMITTED may already have reached upstream.
            if ("PREPARING".equals(entry.phase)) { entry.phase = "FINAL"; entry.target = 0L; entry.payload = basePayload(entry,false); save(entry); }
            else if ("ADMITTED".equals(entry.phase)) { entry.phase = "REVIEW"; entry.target = entry.reserved; entry.payload = basePayload(entry,true); save(entry); }
        }
        meters.gauge("token.quota.ledger.pending",this,l -> l.store.size());
        meters.gauge("token.quota.ledger.local.pending",this,l -> l.store.localSize());
    }

    public Admission begin(String id, String tenant, long reserved, TokenQuotaConfig config,
                           TokenQuotaManager.QuotaPeriodStamp stamp, Map<String,Object> context) {
        checkThread();
        synchronized (lock(tenant)) {
            if (closed) throw new Unavailable("Quota ledger closed");
            if (!reporter.isConfigured()) throw new Unavailable("Durable quota accounting requires the internal usage reporting token");
            Entry prior = store.get(id);
            if (prior != null) {
                if (!prior.tenant.equals(tenant) || prior.reserved != reserved) throw new Unavailable("Quota request context changed");
                if (!"ADMITTED".equals(prior.phase)) throw new Unavailable("Quota request already completed or awaiting recovery");
                return admission(prior,true,0,0);
            }
            Entry entry = new Entry();
            entry.id = UUID.fromString(id).toString(); entry.tenant = tenant; entry.reserved = reserved;
            entry.month = stamp.monthId(); entry.day = stamp.dayId();
            var quota = config.getTenantQuota(tenant);
            entry.period = Objects.requireNonNullElse(quota.getQuotaPeriod(),config.getQuotaPeriod()).toUpperCase(Locale.ROOT);
            entry.monthly = Set.of("MONTHLY","BOTH").contains(entry.period);
            entry.daily = Set.of("DAILY","BOTH").contains(entry.period);
            entry.monthlyLimit = quota.getMonthlyQuota(); entry.dailyLimit = quota.getDailyQuota();
            entry.createdAt = clock.instant().getEpochSecond(); entry.deadline = entry.createdAt + replayHours * 3600L;
            LocalDate day = LocalDate.parse(entry.day,DateTimeFormatter.BASIC_ISO_DATE);
            entry.dayExpiry = day.plusDays(1).atStartOfDay(clock.getZone()).toEpochSecond() + replayHours * 3600L;
            entry.monthExpiry = day.withDayOfMonth(1).plusMonths(1).atStartOfDay(clock.getZone()).toEpochSecond() + replayHours * 3600L;
            if (entry.monthly) entry.deadline = Math.min(entry.deadline,entry.monthExpiry);
            if (entry.daily) entry.deadline = Math.min(entry.deadline,entry.dayExpiry);
            entry.backend = available() ? "REDIS" : "LOCAL";
            entry.phase = "PREPARING"; entry.payload = new LinkedHashMap<>(context);
            entry.fingerprint = fingerprint(entry);
            validateEntry(entry);
            if ("LOCAL".equals(entry.backend)) return admitLocal(entry,config);
            save(entry);
            try {
                List<Long> result = execute(entry,"RESERVE",reserved);
                if (result.get(0) == 0) { remove(entry); return admission(entry,false,result.get(2),result.get(3)); }
                if (result.get(0) != 1) throw new Unavailable("Redis could not admit quota request: " + result.get(0));
                recordBaselines(entry,result);
                entry.phase = "ADMITTED"; save(entry);
                return admission(entry,true,result.get(2),result.get(3));
            } catch (Exception error) {
                // Never turn an uncertain Redis write into a second local grant.
                entry.phase = "FINAL"; entry.target = 0L; entry.payload = basePayload(entry,false); save(entry);
                meters.counter("token.quota.ledger.uncertain.admission").increment();
                throw new Unavailable("Quota admission uncertain; durable cancellation queued",error);
            }
        }
    }

    private Admission admitLocal(Entry entry, TokenQuotaConfig config) {
        if (!config.isLocalCacheFallback()) throw new Unavailable("Local quota fallback disabled");
        Baseline baseline = baseline(entry);
        if (baseline == null) throw new Unavailable("No recoverable quota baseline; refusing a new local grant");
        var pending = store.pending(entry.tenant,entry.month,entry.day);
        if (pending.uncertain()) throw new Unavailable("Uncertain Redis quota operation must be reconciled before local admission");
        long monthly = Math.addExact(baseline.monthly,pending.monthly()), daily = Math.addExact(baseline.daily,pending.daily());
        int percent = Math.max(0,Math.min(100,config.getFallbackQuotaPercent()));
        long monthlyLimit = (long)(entry.monthlyLimit * (percent / 100.0));
        long dailyLimit = (long)(entry.dailyLimit * (percent / 100.0));
        if ((entry.monthly && (monthly + entry.reserved > monthlyLimit || monthly >= monthlyLimit))
                || (entry.daily && (daily + entry.reserved > dailyLimit || daily >= dailyLimit)))
            return admission(entry,false,monthly,daily);
        entry.phase = "ADMITTED"; entry.baselineMonthly = baseline.monthly; entry.baselineDaily = baseline.daily;
        entry.baselineAt = baseline.observedAt; save(entry);
        meters.counter("token.quota.ledger.local.grants").increment();
        return admission(entry,true,monthly + entry.reserved,daily + entry.reserved);
    }

    /** A null charge is unknown usage: retain the reservation and stop at an auditable review state. */
    public String complete(String id, Long charged, Map<String,Object> payload) {
        checkThread();
        Entry found = store.get(id);
        if (found == null) return "APPLIED"; // Already handed to the durable usage outbox.
        synchronized (lock(found.tenant)) {
            Entry entry = store.get(id);
            if (entry == null) return "APPLIED";
            long target = charged == null ? entry.reserved : charged;
            if (target < 0 || target > MAX_TOKENS) throw new Unavailable("Invalid final token charge");
            if (Set.of("FINAL","REVIEW","DELIVERING").contains(entry.phase)) {
                if (!Objects.equals(entry.target,target)) throw new Unavailable("A different final quota intent already exists");
            } else {
                entry.phase = charged == null ? "REVIEW" : "FINAL"; entry.target = target;
                entry.payload = new LinkedHashMap<>(payload); entry.pendingPublished = false; save(entry);
            }
            return process(entry);
        }
    }

    public void abort(String id) {
        Entry entry = store.get(id);
        if (entry != null) complete(id,0L,basePayload(entry,false));
    }

    private String process(Entry entry) {
        if ("DELIVERING".equals(entry.phase)) { deliver(entry); return Objects.toString(entry.payload.get("settlementStatus")); }
        try {
            if (!available()) { publishPending(entry); return "PENDING"; }
            List<Long> result = execute(entry,entry.phase,entry.target);
            long code = result.get(0);
            if (code == 1) {
                recordBaselines(entry,result);
                entry.payload = outcome(entry,true,"REVIEW".equals(entry.phase) ? "PENDING" : "APPLIED");
                entry.phase = "DELIVERING"; save(entry);
                deliver(entry); return Objects.toString(entry.payload.get("settlementStatus"));
            }
            if (code <= -2 && code >= -6) {
                // Stop late/uncertain RPCs before handing this request to manual compensation.
                if (code != -6 && execute(entry,"FENCE",entry.target).get(0) != -6)
                    throw new Unavailable("Quota receipt could not be fenced for manual review");
                entry.payload = outcome(entry,true,"FAILED"); entry.payload.put("ledgerFailure", "Redis ledger result " + code);
                entry.phase = "DELIVERING"; save(entry); deliver(entry);
                meters.counter("token.quota.ledger.review.required").increment(); return "FAILED";
            }
            throw new Unavailable("Unexpected quota ledger result: " + code);
        } catch (Exception error) {
            meters.counter("token.quota.ledger.retries").increment();
            publishPending(store.get(entry.id));
            log.warn("Quota request {} remains pending: {}",entry.id,error.toString());
            return "PENDING";
        }
    }

    private void publishPending(Entry entry) {
        if (entry == null || entry.pendingPublished) return;
        if (reporter.reportDurably(outcome(entry,false,"PENDING"))) { entry.pendingPublished = true; save(entry); }
    }
    private void deliver(Entry entry) {
        if (reporter.reportDurably(entry.payload)) remove(entry);
    }
    private Map<String,Object> outcome(Entry entry, boolean ready, String settlement) {
        Map<String,Object> payload = new LinkedHashMap<>(entry.payload);
        payload.put("eventId",entry.id); payload.put("quotaLedgerId",entry.id); payload.put("quotaLedgerState",ready ? "READY" : "PENDING");
        payload.put("tenantId",entry.tenant); payload.put("reservedTokens",entry.reserved);
        payload.put("quotaMonth",entry.month); payload.put("quotaDay",entry.day); payload.put("quotaPeriod",entry.period);
        payload.put("settlementStatus",settlement); payload.putIfAbsent("instanceId",instanceId);
        return payload;
    }
    private Map<String,Object> basePayload(Entry entry, boolean unknown) {
        Map<String,Object> payload = new LinkedHashMap<>(entry.payload);
        payload.put("usageStatus",unknown ? "UNKNOWN" : "NOT_BILLED");
        payload.put("promptTokens",unknown ? null : 0); payload.put("completionTokens",unknown ? null : 0);
        payload.put("totalTokens",unknown ? null : 0); payload.put("chargedTokens",unknown ? null : 0);
        payload.putIfAbsent("requestTime",LocalDateTime.ofInstant(Instant.ofEpochSecond(entry.createdAt),clock.getZone()).toString());
        return payload;
    }
    @Scheduled(fixedDelayString = "${gateway.token-quota-ledger.retry-ms:5000}")
    public void retryPending() {
        if (closed || store == null) return;
        List<Entry> snapshots = store.all();
        if (snapshots.isEmpty()) return;
        int start = Math.floorMod(replayCursor.getAndAdd(64),snapshots.size());
        for (int index=0;index<Math.min(64,snapshots.size());index++) {
            Entry snapshot = snapshots.get((start+index)%snapshots.size());
            synchronized (lock(snapshot.tenant)) {
                Entry entry = store.get(snapshot.id);
                if (entry == null) continue;
                try {
                    if ("PREPARING".equals(entry.phase)) {
                        entry.phase = "FINAL"; entry.target = 0L; entry.payload = basePayload(entry,false); save(entry);
                    }
                    if ("ADMITTED".equals(entry.phase)) {
                        if ("LOCAL".equals(entry.backend) && available()) {
                            List<Long> result = execute(entry,"SYNC",entry.reserved);
                            if (result.get(0) != 1) continue; // Live request remains owned by its collector.
                            recordBaselines(entry,result);
                            entry.backend = "REDIS"; save(entry);
                        }
                        continue;
                    }
                    process(entry);
                }
                catch (Exception error) { log.warn("Quota ledger replay {} failed: {}",entry.id,error.toString()); }
            }
        }
    }

    @SuppressWarnings("unchecked") private List<Long> execute(Entry entry,String mode,long target) {
        List<?> values = redis.execute(script,List.of("token_quota:request:" + entry.id,
                key(entry,"monthly","used"),key(entry,"monthly","total"),key(entry,"daily","used"),key(entry,"daily","total")),
                mode,Long.toString(target),Long.toString(entry.monthlyLimit),Long.toString(entry.dailyLimit),
                entry.monthly ? "1" : "0",entry.daily ? "1" : "0",Long.toString(entry.monthExpiry),Long.toString(entry.dayExpiry),
                Long.toString(entry.deadline),entry.fingerprint);
        if (values == null || values.size() != 4 || values.stream().anyMatch(v -> !(v instanceof Number)))
            throw new Unavailable("Invalid Redis quota receipt response");
        return values.stream().map(v -> ((Number)v).longValue()).toList();
    }
    private Admission admission(Entry entry,boolean allowed,long monthly,long daily) {
        return new Admission(allowed,entry.reserved,entry.monthly ? entry.monthlyLimit-monthly : -1,
                entry.daily ? entry.dailyLimit-daily : -1,"LOCAL".equals(entry.backend));
    }
    private boolean available() { return health.isRedisAvailableForRateLimiting(); }
    private Object lock(String tenant) { return locks[(tenant.hashCode() & Integer.MAX_VALUE) % locks.length]; }
    private Baseline baseline(Entry entry) {
        var monthly = store.baseline("monthly:"+entry.tenant+":"+entry.month,clock.instant().getEpochSecond());
        var daily = store.baseline("daily:"+entry.tenant+":"+entry.day,clock.instant().getEpochSecond());
        if ((entry.monthly && monthly == null) || (entry.daily && daily == null)) return null;
        return new Baseline(monthly != null ? monthly.used() : 0,daily != null ? daily.used() : 0,
                Math.min(monthly != null ? monthly.observedAt() : clock.millis(),daily != null ? daily.observedAt() : clock.millis()));
    }
    private void recordBaselines(Entry entry,List<Long> result) {
        try {
            if (entry.monthly) store.saveBaseline(new TokenQuotaLedgerStore.Baseline("monthly:"+entry.tenant+":"+entry.month,result.get(2),clock.millis(),entry.monthExpiry));
            if (entry.daily) store.saveBaseline(new TokenQuotaLedgerStore.Baseline("daily:"+entry.tenant+":"+entry.day,result.get(3),clock.millis(),entry.dayExpiry));
        } catch (IOException error) { meters.counter("token.quota.ledger.persistence.failures").increment(); throw new Unavailable("Cannot persist Redis quota baseline",error); }
    }
    private String key(Entry entry,String period,String field) { return "token_quota:" + period + ":" + entry.tenant + ":" + (period.equals("monthly") ? entry.month : entry.day) + ":" + field; }
    private static String fingerprint(Entry entry) {
        try {
            String text = entry.tenant + "|" + entry.month + "|" + entry.day + "|" + entry.period + "|" + entry.reserved
                    + "|" + entry.monthlyLimit + "|" + entry.dailyLimit + "|" + entry.deadline + "|" + entry.monthExpiry + "|" + entry.dayExpiry;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new Unavailable("Cannot fingerprint quota intent",error); }
    }
    static void validateEntry(Entry e) {
        if (e.tenant == null || e.tenant.isBlank() || e.tenant.length() > 64 || e.reserved < 0 || e.reserved > MAX_TOKENS
                || e.monthlyLimit < 0 || e.monthlyLimit > MAX_TOKENS || e.dailyLimit < 0 || e.dailyLimit > MAX_TOKENS
                || e.day == null || !e.day.matches("[0-9]{8}") || e.month == null || !e.day.startsWith(e.month)
                || e.month.length() != 6 || e.monthly != Set.of("MONTHLY","BOTH").contains(e.period)
                || !Set.of("MONTHLY","DAILY","BOTH").contains(e.period)
                || e.daily != Set.of("DAILY","BOTH").contains(e.period)
                || e.monthExpiry < e.deadline || e.dayExpiry < e.deadline
                || e.baselineMonthly < 0 || e.baselineDaily < 0
                || (Set.of("FINAL","REVIEW","DELIVERING").contains(e.phase) && (e.target == null || e.target < 0 || e.target > MAX_TOKENS))
                || e.deadline <= e.createdAt || !Set.of("PREPARING","ADMITTED","FINAL","REVIEW","DELIVERING").contains(e.phase)
                || !Set.of("REDIS","LOCAL").contains(e.backend) || !Objects.equals(fingerprint(e),e.fingerprint))
            throw new Unavailable("Invalid durable quota intent");
        LocalDate.parse(e.day,DateTimeFormatter.BASIC_ISO_DATE);
    }
    private static void checkThread() { if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) throw new Unavailable("Quota ledger requires a blocking-capable thread"); }
    private void save(Entry entry) { try { store.save(entry); } catch (IOException error) { meters.counter("token.quota.ledger.persistence.failures").increment(); throw new Unavailable("Cannot persist quota intent",error); } }
    private void remove(Entry entry) { try { store.remove(entry.id); } catch (IOException error) { throw new Unavailable("Cannot acknowledge quota intent",error); } }
    @PreDestroy public void close() throws IOException { closed = true; if (store != null) store.close(); }
}
