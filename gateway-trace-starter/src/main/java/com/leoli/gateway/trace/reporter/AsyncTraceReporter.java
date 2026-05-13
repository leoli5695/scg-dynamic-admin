package com.leoli.gateway.trace.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async Trace reporter (production-grade enhanced version)
 * <p>
 * Features:
 * 1. Async reporting, doesn't block business threads
 * 2. Batch sending, improves performance
 * 3. Queue buffering, prevents data loss
 * 4. Retry count limit, prevents retry storms
 * 5. Writes to local disk log when exceeding retry count
 * 6. High-performance WebClient connection pool
 * 7. Graceful shutdown @PreDestroy
 *
 * @author leoli
 */
@Slf4j
public class AsyncTraceReporter {

    private final WebClient webClient;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;
    private final ExecutorService fallbackExecutor;  // 【新增】独立的磁盘 IO 线程池
    private final GatewayTraceProperties properties;
    private final BlockingQueue<DistributedTrace> queue;
    private final ScheduledExecutorService retryScheduler;  // 【新增】延迟重试调度器（指数退避）
    private final ConnectionProvider connectionProvider;  // FIX #3: 保持引用以便 shutdown 时 dispose

    /**
     * FIX #1: Shutdown idempotency guard - prevents double-invocation of doShutdown()
     */
    private final AtomicBoolean shutdownComplete = new AtomicBoolean(false);

    /**
     * Local disk log directory
     */
    private static final String FALLBACK_LOG_DIR = "./trace-fallback";

    /**
     * Retry backoff configuration
     * Base delay: 1 second, max delay: 60 seconds
     * Formula: delay = min(baseDelay * 2^retryCount, maxDelay)
     */
    private static final long RETRY_BASE_DELAY_MS = 1000;
    private static final long RETRY_MAX_DELAY_MS = 60000;

    /**
     * Statistics: dropped Trace count
     */
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * Statistics: local disk written Trace count
     */
    private final AtomicLong fallbackCount = new AtomicLong(0);

    /**
     * Statistics: successfully reported Trace count
     */
    private final AtomicLong reportedCount = new AtomicLong(0);

    public AsyncTraceReporter(GatewayTraceProperties properties) {
        this.properties = properties;
        this.connectionProvider = createConnectionProvider();
        this.webClient = createHighPerformanceWebClient(this.connectionProvider);
        this.queue = new LinkedBlockingQueue<>(properties.getAsyncQueueSize());

        // 主上报线程池
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trace-reporter");
            t.setDaemon(false);
            return t;
        });

        // 【新增】独立的磁盘 IO 线程池 - 防止磁盘写入阻塞上报线程
        this.fallbackExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trace-fallback-writer");
            t.setDaemon(true);  // daemon thread，不阻塞关闭
            return t;
        });

        // 【新增】延迟重试调度器 - 指数退避防止重试风暴
        this.retryScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "trace-retry-scheduler");
            t.setDaemon(true);
            return t;
        });

        this.objectMapper = new ObjectMapper();

        // Initialize local log directory
        initFallbackLogDir();

        // Start async reporting thread
        executor.submit(this::reportLoop);

        log.info("AsyncTraceReporter initialized: queueSize={}, batchSize={}, interval={}ms, maxRetry={}",
                properties.getAsyncQueueSize(),
                properties.getReportBatchSize(),
                properties.getReportIntervalMs(),
                DistributedTrace.MAX_RETRY_COUNT);
    }

    /**
     * Create high-performance connection pool
     */
    private ConnectionProvider createConnectionProvider() {
        return ConnectionProvider.builder("trace-reporter-pool")
                .maxConnections(50)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .pendingAcquireMaxCount(100)
                .maxIdleTime(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Create high-performance WebClient (with connection pool configuration)
     * <p>
     * Resolves potential blocking issues with default connection pool under high concurrency
     */
    private WebClient createHighPerformanceWebClient(ConnectionProvider connectionProvider) {
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(Duration.ofMillis(properties.getReportTimeoutMs()))
                .compress(true);  // Enable compression

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * Initialize local log directory
     */
    private void initFallbackLogDir() {
        try {
            Path logDir = Paths.get(FALLBACK_LOG_DIR);
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
                log.info("Created trace fallback log directory: {}", FALLBACK_LOG_DIR);
            }
        } catch (IOException e) {
            log.warn("Failed to create fallback log directory: {}", e.getMessage());
        }
    }

    /**
     * Business thread call: just put in queue
     *
     * @param trace Trace data
     */
    public void report(DistributedTrace trace) {
        if (!queue.offer(trace)) {
            // Queue full, record drop
            droppedCount.incrementAndGet();
            log.warn("Trace queue full, dropping trace: {}, totalDropped={}",
                    trace.getTraceId(), droppedCount.get());

            // Try writing to local disk (degraded handling)
            writeToFallbackLog(trace, "queue_full");
        } else {
            log.debug("Trace queued: {}", trace.getTraceId());
        }
    }

    /**
     * Reporting thread: batch sending
     */
    private void reportLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Batch retrieve
                List<DistributedTrace> traces = new ArrayList<>();
                queue.drainTo(traces, properties.getReportBatchSize());

                if (!traces.isEmpty()) {
                    // Batch report
                    doBatchReport(traces);
                }

                // Wait for next batch
                Thread.sleep(properties.getReportIntervalMs());

            } catch (InterruptedException e) {
                log.info("Trace reporter thread interrupted, shutting down");
                break;

            } catch (Exception e) {
                log.error("Error in trace reporter loop: {}", e.getMessage());
                try {
                    Thread.sleep(1000); // Wait 1 second after error
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }

        // FIX #1: Do NOT call doShutdown() here.
        // The @PreDestroy shutdown() method is the sole owner of shutdown logic.
        log.debug("Trace reporter loop exited");
    }

    /**
     * Execute batch reporting (with retry count limit)
     */
    private void doBatchReport(List<DistributedTrace> traces) {
        if (properties.getAdminUrl() == null || properties.getAdminUrl().isEmpty()) {
            log.warn("adminUrl not configured, cannot report traces, writing to fallback log");
            traces.forEach(t -> writeToFallbackLog(t, "no_admin_url"));
            return;
        }

        String url = properties.getAdminUrl() + "/api/services/traces/batch";

        webClient.post()
                .uri(url)
                .bodyValue(traces)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(java.time.Duration.ofMillis(properties.getReportTimeoutMs()))
                .subscribe(
                        v -> {
                            reportedCount.addAndGet(traces.size());
                            log.debug("Batch reported: {} traces, totalReported={}", traces.size(), reportedCount.get());
                        },
                        e -> {
                            log.warn("Failed to report batch: {}, error: {}", traces.size(), e.getMessage());
                            // Handle retry logic on failure (with retry count limit)
                            handleFailedTraces(traces, e);
                        }
                );
    }

    /**
     * Handle failed Traces (with exponential backoff retry)
     * <p>
     * FIX: Uses exponential backoff to prevent retry storms
     * - retry count < MAX_RETRY_COUNT: schedule delayed retry with exponential backoff
     * - retry count >= MAX_RETRY_COUNT: write to local disk log
     * <p>
     * Backoff formula: delay = min(baseDelay * 2^retryCount, maxDelay)
     * Example: retry 1 -> 1s, retry 2 -> 2s, retry 3 -> 4s, ... max 60s
     */
    private void handleFailedTraces(List<DistributedTrace> traces, Throwable error) {
        for (DistributedTrace trace : traces) {
            trace.incrementRetry();

            if (trace.isMaxRetryExceeded()) {
                // Exceeded max retry count, write to local disk
                writeToFallbackLog(trace, "max_retry_exceeded");
                log.warn("Trace max retry exceeded, written to fallback: traceId={}, retryCount={}",
                        trace.getTraceId(), trace.getRetryCount());
            } else {
                // FIX: Use exponential backoff instead of immediate requeue
                long delayMs = calculateExponentialBackoff(trace.getRetryCount());
                scheduleDelayedRetry(trace, delayMs);
                log.debug("Scheduled trace retry with backoff: traceId={}, retryCount={}, delayMs={}",
                        trace.getTraceId(), trace.getRetryCount(), delayMs);
            }
        }
    }

    /**
     * Calculate exponential backoff delay.
     * Formula: min(baseDelay * 2^retryCount, maxDelay)
     */
    private long calculateExponentialBackoff(int retryCount) {
        long delay = RETRY_BASE_DELAY_MS * (1L << retryCount);  // 2^retryCount
        return Math.min(delay, RETRY_MAX_DELAY_MS);
    }

    /**
     * Schedule delayed retry using retryScheduler.
     * Prevents retry storms during network failures.
     */
    private void scheduleDelayedRetry(DistributedTrace trace, long delayMs) {
        retryScheduler.schedule(() -> {
            if (!queue.offer(trace)) {
                // Queue full after waiting, degrade to disk write
                droppedCount.incrementAndGet();
                writeToFallbackLog(trace, "queue_full_after_backoff");
                log.warn("Cannot requeue trace after backoff (queue full): traceId={}", trace.getTraceId());
            } else {
                log.debug("Requeued trace after backoff: traceId={}, retryCount={}",
                        trace.getTraceId(), trace.getRetryCount());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Write to local disk log (degraded handling)
     * <p>
     * 【背压修复】：使用独立的异步 IO 线程池写入磁盘
     * - 防止磁盘 IO 阻塞上报线程
     * - 磁盘抖动时 Trace 队列不会积压
     */
    private void writeToFallbackLog(DistributedTrace trace, String reason) {
        // 提交到独立的磁盘 IO 线程池，不阻塞上报线程
        fallbackExecutor.submit(() -> writeToFallbackLogSync(trace, reason));
    }

    /**
     * Synchronous fallback log write (used during shutdown when executor may be unavailable)
     */
    private void writeToFallbackLogSync(DistributedTrace trace, String reason) {
        try {
            fallbackCount.incrementAndGet();

            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String fileName = "trace-fallback-" + date + ".log";
            Path logFile = Paths.get(FALLBACK_LOG_DIR, fileName);

            String json = objectMapper.writeValueAsString(trace);
            String logLine = String.format("%s|%s|%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    reason,
                    json);

            Files.writeString(logFile, logLine,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            log.debug("Trace written to fallback log: traceId={}, reason={}, file={}",
                    trace.getTraceId(), reason, fileName);

        } catch (IOException e) {
            log.error("Failed to write trace to fallback log: traceId={}, error={}",
                    trace.getTraceId(), e.getMessage());
        }
    }

    /**
     * Graceful shutdown (@PreDestroy registered)
     * <p>
     * Ensures remaining data is reported on service shutdown
     */
    @PreDestroy
    public void shutdown() {
        log.info("AsyncTraceReporter shutting down...");
        doShutdown();
    }

    /**
     * Execute shutdown logic
     * <p>
     * FIX #1: Uses AtomicBoolean CAS guard to ensure this method executes only once,
     * preventing race condition between reportLoop exit and @PreDestroy.
     */
    private void doShutdown() {
        // FIX #1: Idempotency guard - only the first caller executes shutdown
        if (!shutdownComplete.compareAndSet(false, true)) {
            log.debug("doShutdown() already executed, skipping");
            return;
        }

        // Report remaining data
        List<DistributedTrace> remaining = new ArrayList<>();
        queue.drainTo(remaining);

        if (!remaining.isEmpty()) {
            log.info("Shutdown: attempting to report {} remaining traces", remaining.size());

            // FIX #2: Check adminUrl before attempting HTTP report
            String adminUrl = properties.getAdminUrl();
            if (adminUrl == null || adminUrl.isEmpty()) {
                log.warn("Shutdown: adminUrl not configured, writing {} traces to fallback log", remaining.size());
                for (DistributedTrace t : remaining) {
                    writeToFallbackLogSync(t, "shutdown_no_admin_url");
                }
            } else {
                try {
                    String url = adminUrl + "/api/services/traces/batch";
                    webClient.post()
                            .uri(url)
                            .bodyValue(remaining)
                            .retrieve()
                            .bodyToMono(Void.class)
                            .timeout(Duration.ofSeconds(5))
                            .block();  // Synchronous block on shutdown

                    reportedCount.addAndGet(remaining.size());
                    log.info("Shutdown: reported {} remaining traces successfully", remaining.size());
                } catch (Exception e) {
                    log.error("Failed to report remaining traces on shutdown: {}", e.getMessage());
                    for (DistributedTrace t : remaining) {
                        writeToFallbackLogSync(t, "shutdown_failed");
                    }
                }
            }
        }

        // Shutdown thread pools
        executor.shutdown();
        fallbackExecutor.shutdown();
        retryScheduler.shutdown();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!fallbackExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                fallbackExecutor.shutdownNow();
            }
            if (!retryScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Shutdown interrupted, forcing termination");
            executor.shutdownNow();
            fallbackExecutor.shutdownNow();
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // FIX #3: Dispose connection provider to release native resources
        try {
            connectionProvider.dispose();
            log.debug("ConnectionProvider disposed");
        } catch (Exception e) {
            log.warn("Error disposing ConnectionProvider: {}", e.getMessage());
        }

        // Output statistics
        log.info("AsyncTraceReporter shutdown complete. Stats: reported={}, dropped={}, fallback={}",
                reportedCount.get(), droppedCount.get(), fallbackCount.get());
    }

    // ==================== Statistics Interface ====================

    /**
     * Get queue size
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Get dropped count
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /**
     * Get local disk write count
     */
    public long getFallbackCount() {
        return fallbackCount.get();
    }

    /**
     * Get successfully reported count
     */
    public long getReportedCount() {
        return reportedCount.get();
    }
}