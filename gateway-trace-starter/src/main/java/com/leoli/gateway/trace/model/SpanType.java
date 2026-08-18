package com.leoli.gateway.trace.model;

/**
 * Span type enumeration
 * 
 * Identifies the category of a Span for visualization and analysis
 * 
 * @author leoli
 */
public enum SpanType {

    /**
     * Service method execution (business logic)
     */
    SERVICE,

    /**
     * Redis cache operation
     */
    REDIS,

    /**
     * Database operation (MySQL/PostgreSQL/etc.)
     */
    DB,

    /**
     * Message queue operation (RocketMQ/Kafka/etc.)
     */
    MQ,

    /**
     * HTTP/RPC call (Feign/OkHttp/etc.)
     */
    HTTP,

    /**
     * Internal operation (logging/utils/etc.)
     */
    INTERNAL,

    /**
     * Gateway Filter execution
     */
    FILTER
}