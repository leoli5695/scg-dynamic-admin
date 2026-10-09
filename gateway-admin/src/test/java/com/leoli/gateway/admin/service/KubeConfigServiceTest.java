package com.leoli.gateway.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression: createApiClient(null) used to throw NullPointerException from
 * the StringReader constructor ("String.length() because s is null"), which
 * escaped the IOException handler. Callers rely on a null return instead.
 */
class KubeConfigServiceTest {

    private final KubeConfigService service = new KubeConfigService();

    @Test
    void nullKubeconfigReturnsNullClient() {
        assertNull(service.createApiClient(null));
    }

    @Test
    void blankKubeconfigReturnsNullClient() {
        assertNull(service.createApiClient("   "));
    }
}
