package com.leoli.gateway.util;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class BoundedBodyCaptureTest {
    @Test void preservesTheOriginalReaderPositionAndLimitsThePrefix() {
        var buffer=DefaultDataBufferFactory.sharedInstance.wrap("skip-body-content".getBytes(StandardCharsets.UTF_8));
        buffer.readPosition(5);
        var capture=new BoundedBodyCapture(4); capture.append(buffer);
        assertEquals(5,buffer.readPosition()); assertEquals(4,capture.length());
        assertEquals("body...[TRUNCATED]",capture.toString());
        capture.append(buffer); assertEquals(4,capture.length()); assertEquals(5,buffer.readPosition());
    }
    @Test void decodesUtf8AfterAllChunksRatherThanBreakingMultibyteCharacters() {
        byte[] utf8="中文".getBytes(StandardCharsets.UTF_8); var capture=new BoundedBodyCapture(6);
        capture.append(DefaultDataBufferFactory.sharedInstance.wrap(java.util.Arrays.copyOfRange(utf8,0,1)));
        capture.append(DefaultDataBufferFactory.sharedInstance.wrap(java.util.Arrays.copyOfRange(utf8,1,6)));
        assertEquals("中文",capture.toString());
    }
}
