package com.leoli.gateway.admin.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TraceIngestionLimitFilterTest {
    @Test void unknownLengthOversizedBodyIsRejectedBeforeJsonDecoding() throws Exception {
        var filter = new TraceIngestionLimitFilter(); var request = new MockHttpServletRequest("POST", "/api/services/traces/batch") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[2 * 1024 * 1024 + 1]); var response = new MockHttpServletResponse(); AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertEquals(413, response.getStatus()); assertFalse(called.get());
    }
    @Test void normalBodyIsAvailableAndPermitIsReleasedAfterHandlerFailure() throws Exception {
        var filter = new TraceIngestionLimitFilter();
        for (int i = 0; i < 8; i++) {
            var request = new MockHttpServletRequest("POST", "/api/services/traces/batch"); request.setContent("[]".getBytes());
            assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertEquals("[]", new String(req.getInputStream().readAllBytes())); throw new jakarta.servlet.ServletException("failed");
            }));
        }
    }
}
