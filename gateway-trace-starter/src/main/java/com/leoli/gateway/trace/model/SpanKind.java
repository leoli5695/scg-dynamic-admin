package com.leoli.gateway.trace.model;

/**
 * Span kind enumeration (OpenTelemetry standard)
 * 
 * Identifies the role of a Span in a trace
 * 
 * @author leoli
 */
public enum SpanKind {

    /**
     * Client-side span (outgoing call)
     * Example: Feign call to downstream service
     */
    CLIENT,

    /**
     * Server-side span (incoming request)
     * Example: HTTP request handler
     */
    SERVER,

    /**
     * Producer span (message publishing)
     * Example: RocketMQ message send
     */
    PRODUCER,

    /**
     * Consumer span (message consumption)
     * Example: RocketMQ message receive
     */
    CONSUMER,

    /**
     * Internal span (in-process operation)
     * Example: Service method call, Redis/DB operation
     */
    INTERNAL
}