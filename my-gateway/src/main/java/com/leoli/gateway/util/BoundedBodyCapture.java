package com.leoli.gateway.util;

import org.springframework.core.io.buffer.DataBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Copies only the observed prefix; never consumes or retains a pooled network buffer. */
public final class BoundedBodyCapture {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
    private boolean truncated;
    public BoundedBodyCapture(int limit) { this.limit = Math.max(0, limit); }
    public void append(DataBuffer buffer) {
        int readable = buffer.readableByteCount();
        int count = Math.min(readable, limit - bytes.size());
        if (count < readable) truncated = true;
        if (count <= 0) return;
        byte[] prefix = new byte[count];
        int position = buffer.readPosition();
        try { buffer.read(prefix); bytes.write(prefix, 0, count); }
        finally { buffer.readPosition(position); }
    }
    public int length() { return bytes.size(); }
    @Override public String toString() {
        return bytes.toString(StandardCharsets.UTF_8) + (truncated ? "...[TRUNCATED]" : "");
    }
}
