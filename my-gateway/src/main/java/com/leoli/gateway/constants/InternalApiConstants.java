package com.leoli.gateway.constants;

/**
 * Contract constants for the internal HTTP API between gateway instances and the control
 * plane. gateway-admin and gateway-trace-starter each declare their own copy: the three
 * modules share no common dependency, so the literal is repeated once per module by design.
 *
 * @author leoli
 */
public final class InternalApiConstants {

    /** Header carrying the shared static token that protects {@code /internal/**} endpoints. */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private InternalApiConstants() {
    }
}
