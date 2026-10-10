package com.leoli.gateway.filter.token;

import com.leoli.gateway.constants.FilterOrderConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the token-usage capture ordering bug.
 * <p>
 * SCG's NettyWriteResponseFilter runs at order -1 and only writes the client-bound
 * response body through decorators installed by filters ordered BEFORE it. When this
 * collector sat at 101 its response decorator was never invoked on the real proxy path,
 * so usage always parsed as 0. Keep it < -1.
 */
class TokenUsageCollectorFilterOrderTest {

    @Test
    void orderIsBeforeNettyWriteResponseFilter() {
        assertTrue(FilterOrderConstants.TOKEN_USAGE_COLLECTOR < -1,
                "TOKEN_USAGE_COLLECTOR must be < -1 (NettyWriteResponseFilter) to be in the response-write path");
        assertEquals(FilterOrderConstants.TOKEN_USAGE_COLLECTOR, new TokenUsageCollectorFilter().getOrder());
    }

    @Test
    void orderIsAfterTokenQuotaPreCheckSoTenantIsResolved() {
        // Pre-check (-241) sets the tenant attribute the collector settles against.
        assertTrue(FilterOrderConstants.TOKEN_USAGE_COLLECTOR > FilterOrderConstants.TOKEN_QUOTA_PRE_CHECK,
                "collector must run after TokenQuotaPreCheckFilter so the tenant attribute is set");
    }
}
