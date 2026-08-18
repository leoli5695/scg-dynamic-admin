package com.leoli.gateway.trace.model;

/**
 * Standardized Span tags constants
 * 
 * Following OpenTelemetry Semantic Conventions for consistent analysis
 * 
 * @author leoli
 */
public final class SpanTags {

    // ==================== Database Tags ====================

    /**
     * Database system name (mysql/postgresql/etc.)
     */
    public static final String DB_SYSTEM = "db.system";

    /**
     * Database operation type (select/insert/update/delete)
     */
    public static final String DB_TYPE = "db.type";

    /**
     * Database table name
     */
    public static final String DB_TABLE = "db.table";

    /**
     * SQL statement (truncated for security)
     */
    public static final String DB_STATEMENT = "db.statement";

    /**
     * Affected rows count
     */
    public static final String DB_ROWS = "db.rows";

    // ==================== Redis Tags ====================

    /**
     * Redis command (GET/SET/INCR/etc.)
     */
    public static final String REDIS_COMMAND = "redis.command";

    /**
     * Redis key pattern (with placeholders for security)
     */
    public static final String REDIS_KEY = "redis.key";

    /**
     * Cache hit flag (true/false)
     */
    public static final String REDIS_HIT = "redis.hit";

    /**
     * Redis instance address
     */
    public static final String REDIS_INSTANCE = "redis.instance";

    // ==================== MQ Tags ====================

    /**
     * Message queue system (rocketmq/kafka/rabbitmq)
     */
    public static final String MQ_SYSTEM = "mq.system";

    /**
     * Message topic name
     */
    public static final String MQ_TOPIC = "mq.topic";

    /**
     * Message partition number
     */
    public static final String MQ_PARTITION = "mq.partition";

    /**
     * Message key (for tracing)
     */
    public static final String MQ_MESSAGE_KEY = "mq.messageKey";

    /**
     * Message tag (RocketMQ specific)
     */
    public static final String MQ_TAG = "mq.tag";

    // ==================== HTTP Tags ====================

    /**
     * HTTP method (GET/POST/PUT/DELETE)
     */
    public static final String HTTP_METHOD = "http.method";

    /**
     * HTTP URL (sanitized)
     */
    public static final String HTTP_URL = "http.url";

    /**
     * HTTP response status code
     */
    public static final String HTTP_STATUS_CODE = "http.statusCode";

    /**
     * HTTP target service name (for Feign calls)
     */
    public static final String HTTP_TARGET_SERVICE = "http.targetService";

    // ==================== Service Tags ====================

    /**
     * Target service name (for RPC/Feign calls)
     */
    public static final String SERVICE_TARGET = "service.target";

    /**
     * Service instance address (IP:Port)
     */
    public static final String SERVICE_INSTANCE = "service.instance";

    /**
     * Service version
     */
    public static final String SERVICE_VERSION = "service.version";

    // ==================== Error Tags ====================

    /**
     * Error type (exception class name)
     */
    public static final String ERROR_TYPE = "error.type";

    /**
     * Error message (truncated)
     */
    public static final String ERROR_MESSAGE = "error.message";

    /**
     * Error stack trace (truncated)
     */
    public static final String ERROR_STACK = "error.stack";

    // ==================== Gateway Tags ====================

    /**
     * Gateway filter name
     */
    public static final String GATEWAY_FILTER = "gateway.filter";

    /**
     * Gateway route ID
     */
    public static final String GATEWAY_ROUTE = "gateway.route";

    /**
     * Gateway instance ID
     */
    public static final String GATEWAY_INSTANCE = "gateway.instance";

    // Private constructor to prevent instantiation
    private SpanTags() {}
}