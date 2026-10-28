package com.leoli.gateway.trace.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.trace.model.DistributedTrace;
import java.io.*;

/** Bounded encoding used before retaining mutable application objects in an export queue. */
final class BoundedTraceJson {
    static byte[] encode(ObjectMapper mapper, DistributedTrace trace, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(int value) { check(1); super.write(value); }
            @Override public synchronized void write(byte[] bytes, int offset, int length) { check(length); super.write(bytes, offset, length); }
            private void check(int length) { if (length > limit - count) throw new IllegalArgumentException("Trace exceeds encoding limit"); }
        };
        mapper.writeValue(output, trace);
        return output.toByteArray();
    }
}
