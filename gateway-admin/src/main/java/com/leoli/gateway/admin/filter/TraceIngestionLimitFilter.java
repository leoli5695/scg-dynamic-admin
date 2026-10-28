package com.leoli.gateway.admin.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.concurrent.Semaphore;

/** Bound decoding before Jackson allocates a batch; also covers unknown-length uploads. Runs after security. */
@Component
@Order(0)
public class TraceIngestionLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Semaphore readers = new Semaphore(4);
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = org.springframework.web.util.UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll(";[^/]*", "").replaceAll("/+$", "");
        return !"POST".equals(request.getMethod()) || !(path.equals("/api/services/traces") || path.equals("/api/services/traces/batch"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) { response.setStatus(413); return; }
        if (!readers.tryAcquire()) { response.setStatus(503); response.setHeader("Retry-After", "1"); return; }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) { response.setStatus(413); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous trace ingestion"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
            }, response);
        } finally { readers.release(); }
    }
}
