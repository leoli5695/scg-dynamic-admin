package com.leoli.gateway.admin.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The access-log file resolver is a path-traversal boundary, so it gets pinned directly.
 *
 * <p>The endpoints built the file name as {@code "access-" + date + ".log"} from a raw request
 * parameter and resolved it without normalising. The {@code access-} prefix constrains nothing:
 * {@code date=x/../../../etc/passwd} yields {@code access-x/../../../etc/passwd.log}, whose first
 * segment is an ordinary directory name and whose later {@code ..} segments walk out of the log
 * directory - an authenticated read of any {@code *.log} file on the host.
 *
 * <p>These call the private resolver reflectively rather than widening its visibility: the point is to
 * pin the boundary, not to reshape production code for the test's convenience.
 */
@ExtendWith(MockitoExtension.class)
class AccessLogPathSafetyTest {

    private AccessLogConfigController controller;
    private Path logDir;

    @BeforeEach
    void setUp() {
        controller = new AccessLogConfigController(null, null, null, null);
        logDir = Paths.get("/var/log/gateway/access").toAbsolutePath().normalize();
    }

    private Path resolve(String date) {
        return ReflectionTestUtils.invokeMethod(controller, "resolveLogFile", logDir, date);
    }

    private int clamp(int requested) {
        Integer clamped = ReflectionTestUtils.invokeMethod(controller, "clampTailLines", requested);
        return clamped == null ? -1 : clamped;
    }

    @Test
    @DisplayName("a normal date resolves inside the log directory")
    void normalDateResolvesInsideLogDirectory() {
        Path resolved = resolve("2026-10-25");

        assertEquals(logDir.resolve("access-2026-10-25.log"), resolved);
        assertTrue(resolved.startsWith(logDir));
    }

    @Test
    @DisplayName("the traversal that used to work is rejected")
    void traversalIsRejected() {
        // This is the exact payload that escaped the directory before the fix.
        assertThrows(IllegalArgumentException.class, () -> resolve("x/../../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> resolve("../../etc/shadow"));
        assertThrows(IllegalArgumentException.class, () -> resolve("2026-10-25/../../secret"));
        assertThrows(IllegalArgumentException.class, () -> resolve(".."));
        assertThrows(IllegalArgumentException.class, () -> resolve("."));
    }

    @Test
    @DisplayName("anything that is not yyyy-MM-dd is rejected, so the name cannot be steered at all")
    void malformedDatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> resolve("2026-1-5"));
        assertThrows(IllegalArgumentException.class, () -> resolve("access-2026-10-25"));
        assertThrows(IllegalArgumentException.class, () -> resolve("2026-10-25.log"));
        assertThrows(IllegalArgumentException.class, () -> resolve("20261025"));
        assertThrows(IllegalArgumentException.class, () -> resolve("2026-10-25\nX-Injected: 1"));
    }

    @Test
    @DisplayName("the error message does not echo the caller-controlled value back")
    void errorMessageDoesNotEchoInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> resolve("x/../../../etc/passwd"));

        assertTrue(!e.getMessage().contains("passwd") && !e.getMessage().contains(".."),
                "the message reaches the HTTP response and the logs; it must not reflect the payload: "
                        + e.getMessage());
    }

    @Test
    @DisplayName("tailLines is clamped so a caller cannot size an in-memory list by asking")
    void tailLinesIsClamped() {
        assertEquals(500, clamp(500));
        assertEquals(1, clamp(0));
        assertEquals(1, clamp(-100));
        assertEquals(10_000, clamp(10_000));
        assertEquals(10_000, clamp(100_000_000));
        assertEquals(10_000, clamp(Integer.MAX_VALUE));
    }
}
