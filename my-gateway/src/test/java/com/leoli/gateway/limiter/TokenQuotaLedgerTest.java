package com.leoli.gateway.limiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.filter.token.TokenUsageReporter;
import com.leoli.gateway.model.TokenQuotaConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real files and restarts, with a fault-injectable atomic Redis receipt contract. */
class TokenQuotaLedgerTest {
    @TempDir Path directory;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"),ZoneId.of("Asia/Shanghai"));
    final TokenQuotaManager.QuotaPeriodStamp stamp = new TokenQuotaManager.QuotaPeriodStamp("202610","20261028");
    final AtomicBoolean healthy = new AtomicBoolean(true);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final RedisHealthChecker health = mock(RedisHealthChecker.class);
    final TokenUsageReporter reporter = mock(TokenUsageReporter.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<Map<String,Object>> reports = new ArrayList<>();
    final Engine engine = new Engine();
    TokenQuotaLedger ledger;
    TokenQuotaConfig config;

    @BeforeEach void setup() throws Exception {
        config = new TokenQuotaConfig(); config.setDefaultMonthlyQuota(1000); config.setDefaultDailyQuota(1000);
        when(health.isRedisAvailableForRateLimiting()).thenAnswer(i -> healthy.get());
        when(reporter.isConfigured()).thenReturn(true);
        when(reporter.reportDurably(anyMap())).thenAnswer(i -> { reports.add(new LinkedHashMap<>(i.getArgument(0))); return true; });
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i ->
                engine.call(i.getArgument(1),(Object[])i.getRawArguments()[2]));
        open(4096);
    }
    @AfterEach void close() throws Exception { if (ledger != null) ledger.close(); }
    void open(int capacity) throws Exception {
        ledger = new TokenQuotaLedger(redis,health,reporter,meters,mapper);
        ReflectionTestUtils.setField(ledger,"directory",directory.toString());
        ReflectionTestUtils.setField(ledger,"capacity",capacity);
        ReflectionTestUtils.setField(ledger,"clock",clock);
        ledger.start();
    }
    String begin(long reserved) {
        String id = UUID.randomUUID().toString();
        assertTrue(ledger.begin(id,"tenant",reserved,config,stamp,Map.of("traceId","trace")).allowed());
        return id;
    }
    Map<String,Object> usage(long tokens) {
        return Map.of("usageStatus","CONFIRMED","promptTokens",10,"completionTokens",tokens-10,"totalTokens",tokens,"chargedTokens",tokens);
    }
    void warmBaseline() { String id = begin(0); ledger.complete(id,0L,Map.of("usageStatus","CONFIRMED","chargedTokens",0)); reports.clear(); }
    long month() { return engine.counters.getOrDefault("token_quota:monthly:tenant:202610:used",0L); }
    long day() { return engine.counters.getOrDefault("token_quota:daily:tenant:20261028:used",0L); }
    long pending() throws Exception { try (var files = Files.list(directory)) { return files.filter(p -> p.toString().endsWith(".json")).count(); } }

    @Test void reservationAndFinalChargeUseOneReceiptForBothPeriods() {
        String id = begin(100);
        assertEquals("APPLIED",ledger.complete(id,30L,usage(30)));
        assertEquals("APPLIED",ledger.complete(id,30L,usage(30)));
        assertEquals(30,month()); assertEquals(30,day()); assertEquals(2,engine.writes);
        assertEquals(id,reports.getLast().get("eventId")); assertEquals("READY",reports.getLast().get("quotaLedgerState"));
    }
    @Test void quotaRefusalDoesNotBecomeAReadOnlyPass() throws Exception {
        var result = ledger.begin(UUID.randomUUID().toString(),"tenant",1001,config,stamp,Map.of());
        assertFalse(result.allowed()); assertEquals(0,month()); assertEquals(0,pending());
    }
    @Test void uncertainAdmissionIsCancelledWithSameReceiptRatherThanLocallyGranted() {
        engine.failAfterWrite = true;
        assertThrows(TokenQuotaLedger.Unavailable.class,() -> begin(100));
        assertEquals(100,month());
        ledger.retryPending();
        assertEquals(0,month()); assertEquals(0,day());
        assertEquals("NOT_BILLED",reports.getLast().get("usageStatus"));
    }
    @Test void acknowledgedRedisWriteWithLostResponseSurvivesRestart() throws Exception {
        String id = begin(100); engine.failAfterWrite = true;
        assertEquals("PENDING",ledger.complete(id,30L,usage(30)));
        assertEquals(30,month()); assertEquals(1,pending());
        ledger.close(); open(4096); ledger.retryPending();
        assertEquals(30,month()); assertEquals(30,day()); assertEquals(2,engine.writes); assertEquals(0,pending());
        assertTrue(reports.stream().allMatch(r -> id.equals(r.get("eventId"))));
        assertEquals("PENDING",reports.getFirst().get("quotaLedgerState"));
        assertEquals("READY",reports.getLast().get("quotaLedgerState"));
    }
    @Test void localGrantAndItsBaselineSurviveRestartWithoutResettingAllowance() throws Exception {
        warmBaseline(); healthy.set(false); String local = begin(100);
        ledger.close(); open(4096);
        assertFalse(ledger.begin(UUID.randomUUID().toString(),"tenant",450,config,stamp,Map.of()).allowed());
        healthy.set(true); ledger.retryPending(); ledger.retryPending();
        assertEquals(100,month()); assertEquals(100,day()); assertEquals(0,pending());
        assertEquals(local,reports.getLast().get("eventId")); assertEquals("UNKNOWN",reports.getLast().get("usageStatus"));
        assertNull(reports.getLast().get("chargedTokens"));
    }
    @Test void localFinalChargeReplaysActualRatherThanReservationPlusActual() throws Exception {
        warmBaseline(); healthy.set(false); String id = begin(100);
        assertEquals("PENDING",ledger.complete(id,30L,usage(30)));
        ledger.close(); open(4096); healthy.set(true); ledger.retryPending(); ledger.retryPending();
        assertEquals(30,month()); assertEquals(30,day()); assertEquals(0,pending());
    }
    @Test void liveLocalReservationCanBeBackDeductedBeforeTheResponseCompletes() {
        warmBaseline(); healthy.set(false); String id = begin(100);
        healthy.set(true); ledger.retryPending(); ledger.retryPending();
        assertEquals(100,month());
        ledger.complete(id,30L,usage(30));
        assertEquals(30,month()); assertEquals(30,day());
    }
    @Test void orphanedAdmittedRequestRetainsReservationForReview() throws Exception {
        begin(100); ledger.close(); open(4096); ledger.retryPending();
        assertEquals(100,month()); assertEquals("UNKNOWN",reports.getLast().get("usageStatus"));
        assertEquals("PENDING",reports.getLast().get("settlementStatus")); assertEquals("READY",reports.getLast().get("quotaLedgerState"));
    }
    @Test void earlyCancellationAndExplicitZeroAreIdempotentFullRefunds() {
        String id = begin(100); ledger.abort(id); ledger.abort(id);
        assertEquals(0,month()); assertEquals(0,day());
        String second = begin(100); ledger.complete(second,0L,Map.of("usageStatus","CONFIRMED","chargedTokens",0));
        assertEquals(0,month()); assertEquals(0,day());
    }
    @Test void capacityFailureOccursBeforeRedisGrant() throws Exception {
        ledger.close(); open(1); begin(100);
        assertThrows(TokenQuotaLedger.Unavailable.class,() -> begin(100));
        assertEquals(100,month()); assertEquals(1,engine.writes);
    }
    @Test void failedFinalIntentWriteCannotAdjustRedisAndRecoversAsUnknown() throws Exception {
        String id = begin(100); Files.createDirectory(directory.resolve(id + ".tmp"));
        assertThrows(TokenQuotaLedger.Unavailable.class,() -> ledger.complete(id,30L,usage(30)));
        assertEquals(100,month()); ledger.close(); open(4096); ledger.retryPending();
        assertEquals(100,month()); assertEquals("UNKNOWN",reports.getLast().get("usageStatus"));
    }
    @Test void missingBaselineOrDisabledFallbackRefusesNewLocalGrants() {
        healthy.set(false);
        assertThrows(TokenQuotaLedger.Unavailable.class,() -> begin(100));
        healthy.set(true); warmBaseline(); healthy.set(false); config.setLocalCacheFallback(false);
        assertThrows(TokenQuotaLedger.Unavailable.class,() -> begin(100));
    }
    @Test void finalIntentIsImmutableEvenWhileDeliveryIsPending() {
        String id = begin(100); healthy.set(false); ledger.complete(id,30L,usage(30));
        assertThrows(TokenQuotaLedger.Unavailable.class,() -> ledger.complete(id,40L,usage(40)));
        healthy.set(true); ledger.retryPending(); assertEquals(30,month());
    }
    @Test void concurrentCompletionChargesOnce() throws Exception {
        String id = begin(100);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> ledger.complete(id,30L,usage(30)));
            var second = workers.submit(() -> ledger.complete(id,30L,usage(30)));
            assertEquals("APPLIED",first.get()); assertEquals("APPLIED",second.get());
        }
        assertEquals(30,month()); assertEquals(2,engine.writes);
    }
    @Test void usageOutboxFailureRetainsDeliveringEntryWithoutRepeatingQuotaAdjustment() throws Exception {
        String id = begin(100); when(reporter.reportDurably(anyMap())).thenReturn(false);
        ledger.complete(id,30L,usage(30)); assertEquals(1,pending());
        ledger.close(); open(4096); when(reporter.reportDurably(anyMap())).thenReturn(true);
        ledger.retryPending(); assertEquals(30,month()); assertEquals(2,engine.writes); assertEquals(0,pending());
    }
    @Test void completedUsageBaselineSurvivesRestartAlongsideOtherPendingLocalGrants() throws Exception {
        warmBaseline(); healthy.set(false); String first = begin(100); begin(100);
        healthy.set(true); ledger.complete(first,150L,usage(150));
        healthy.set(false); ledger.close(); open(4096);
        assertFalse(ledger.begin(UUID.randomUUID().toString(),"tenant",300,config,stamp,Map.of()).allowed());
        healthy.set(true); ledger.retryPending(); assertEquals(250,month());
    }
    @Test void baselineAlsoSurvivesWhenAllPriorRequestEntriesHaveBeenDelivered() throws Exception {
        String first = begin(100); ledger.complete(first,150L,usage(150)); assertEquals(0,pending());
        healthy.set(false); ledger.close(); open(4096);
        assertFalse(ledger.begin(UUID.randomUUID().toString(),"tenant",400,config,stamp,Map.of()).allowed());
        assertTrue(ledger.begin(UUID.randomUUID().toString(),"tenant",300,config,stamp,Map.of()).allowed());
    }
    @Test void failedAutomaticAdjustmentIsFencedBeforeManualRecoveryIsAllowed() {
        String id = begin(100);
        engine.counters.replaceAll((key,value) -> 0L); // An external reset prevents the requested refund.
        assertEquals("FAILED",ledger.complete(id,30L,usage(30)));
        assertEquals("BLOCKED",engine.receipts.get("token_quota:request:"+id).phase());
        assertEquals("READY",reports.getLast().get("quotaLedgerState"));
        assertEquals(0,month());
    }

    /** Atomic remote contract; injects a network failure after a successful server-side write. */
    static class Engine {
        record Receipt(String phase,long charged,String context) { }
        final Map<String,Long> counters = new HashMap<>();
        final Map<String,Receipt> receipts = new HashMap<>();
        boolean failAfterWrite; int writes;
        List<Long> call(List<String> keys,Object[] args) {
            String mode=args[0].toString(); long target=Long.parseLong(args[1].toString());
            boolean monthly="1".equals(args[4]),daily="1".equals(args[5]);
            long m=counters.getOrDefault(keys.get(1),0L),d=counters.getOrDefault(keys.get(3),0L);
            Receipt prior=receipts.get(keys.getFirst());
            if (prior != null && !prior.context.equals(args[9])) return List.of(-4L,prior.charged,m,d);
            if (prior != null && prior.phase.equals("BLOCKED")) return List.of(-6L,prior.charged,m,d);
            if (mode.equals("FENCE")) { receipts.put(keys.getFirst(),new Receipt("BLOCKED",prior==null ? 0 : prior.charged,args[9].toString())); return List.of(-6L,0L,m,d); }
            if (prior != null && Set.of("FINAL","REVIEW").contains(prior.phase))
                return List.of(prior.phase.equals(mode) && prior.charged==target ? 1L : -4L,prior.charged,m,d);
            if (prior != null && "OPEN".equals(prior.phase) && Set.of("RESERVE","SYNC").contains(mode)) return List.of(1L,prior.charged,m,d);
            if (mode.equals("RESERVE") && ((monthly && (m+target>Long.parseLong(args[2].toString()) || (target==0 && m>=Long.parseLong(args[2].toString()))))
                    || (daily && (d+target>Long.parseLong(args[3].toString()) || (target==0 && d>=Long.parseLong(args[3].toString())))))) return List.of(0L,0L,m,d);
            long delta=target-(prior==null ? 0 : prior.charged);
            if ((monthly && m+delta<0) || (daily && d+delta<0)) return List.of(-5L,prior==null ? 0L : prior.charged,m,d);
            if (monthly) { m+=delta; counters.put(keys.get(1),m); }
            if (daily) { d+=delta; counters.put(keys.get(3),d); }
            receipts.put(keys.getFirst(),new Receipt(Set.of("RESERVE","SYNC").contains(mode) ? "OPEN" : mode,target,args[9].toString())); writes++;
            if (failAfterWrite) { failAfterWrite=false; throw new IllegalStateException("Redis response lost after applying operation"); }
            return List.of(1L,target,m,d);
        }
    }
}
