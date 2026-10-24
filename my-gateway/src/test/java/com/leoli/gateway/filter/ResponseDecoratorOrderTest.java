package com.leoli.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the invariant that three separate filters have now violated:
 * <p>
 * <b>A GlobalFilter that installs a response decorator must be ordered before
 * {@link NettyWriteResponseFilter} (-1).</b>
 * <p>
 * Why: NettyWriteResponseFilter's pre-phase captures {@code exchange.getResponse()} and its
 * post-phase writes the upstream body to it. Filters ordered before -1 run their pre-phase
 * first, so the exchange Netty sees already carries their decorator and the body flows through
 * it. Filters ordered after -1 run inside Netty's {@code chain.filter(...)}, decorate an exchange
 * nobody reads back, and their post-phase fires before the body is written. Their decorator is
 * dead code - silently, with no error and no test failure.
 * <p>
 * Verified casualties: {@code TokenUsageCollectorFilter} (was 101, usage always parsed 0),
 * {@code CacheGlobalFilter} (was 50, cache never populated - two identical GETs both hit origin),
 * {@code TraceCaptureGlobalFilter} (was 100, captured response body always null).
 * <p>
 * The decorator classes are discovered from the compiled output rather than listed by hand, so a
 * new filter that decorates the response is checked without anyone remembering to update a table.
 */
class ResponseDecoratorOrderTest {

    private static final int NETTY_WRITE_ORDER = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER;

    @Test
    @DisplayName("no response-decorating GlobalFilter is ordered at or after NettyWriteResponseFilter")
    void decoratorFiltersRunBeforeNettyWriteResponseFilter() throws Exception {
        Path classesRoot = classesRoot();
        ClassLoader loader = ResponseDecoratorOrderTest.class.getClassLoader();

        Set<String> decoratorOwners = new TreeSet<>();
        List<String> unattributed = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(classesRoot)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                String fileName = p.getFileName().toString();
                if (!fileName.endsWith(".class")) {
                    continue;
                }
                String binaryName = toBinaryName(classesRoot, p);
                if (!binaryName.startsWith("com.leoli.gateway.")) {
                    continue;
                }
                // Only nested/anonymous classes can be an inline decorator; a top-level one has no
                // enclosing filter to attribute an order to.
                int dollar = binaryName.indexOf('$');
                if (dollar < 0) {
                    continue;
                }
                Class<?> candidate = loadWithoutInit(loader, binaryName);
                if (candidate == null || !ServerHttpResponse.class.isAssignableFrom(candidate)
                        || candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) {
                    continue;
                }
                decoratorOwners.add(binaryName.substring(0, dollar));
            }
        }

        assertTrue(decoratorOwners.size() >= 8,
                "discovery found only " + decoratorOwners + " - the scan is not seeing the compiled "
                        + "filter classes, so this test would pass vacuously");

        List<String> violations = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (String ownerName : decoratorOwners) {
            Class<?> owner = loadWithoutInit(loader, ownerName);
            if (owner == null || !org.springframework.cloud.gateway.filter.GlobalFilter.class
                    .isAssignableFrom(owner)) {
                // Decorator lives in a service/helper, not a filter: nothing to order.
                unattributed.add(ownerName);
                continue;
            }
            int order;
            try {
                order = orderOf(owner);
            } catch (Exception e) {
                fail("could not read getOrder() of " + ownerName + " - add it to this test explicitly: " + e);
                return;
            }
            checked.add(owner.getSimpleName() + "=" + order);
            if (order >= NETTY_WRITE_ORDER) {
                violations.add(owner.getSimpleName() + " (order " + order + ")");
            }
        }

        assertTrue(violations.isEmpty(),
                "these GlobalFilters install a response decorator but run at/after "
                        + "NettyWriteResponseFilter (" + NETTY_WRITE_ORDER + "), so their decorator is never "
                        + "on the client-write path: " + violations + ". Give them an order < "
                        + NETTY_WRITE_ORDER + " in FilterOrderConstants. Checked: " + checked);
    }

    /**
     * Reads {@code getOrder()} without a Spring context: these filters only assign their
     * constructor arguments, so a bare instance with nulls is enough to ask for the order.
     */
    private static int orderOf(Class<?> filterClass) throws Exception {
        Constructor<?> chosen = null;
        for (Constructor<?> c : filterClass.getDeclaredConstructors()) {
            if (chosen == null || c.getParameterCount() < chosen.getParameterCount()) {
                chosen = c;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("no constructor on " + filterClass.getName());
        }
        chosen.setAccessible(true);
        Class<?>[] types = chosen.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultValue(types[i]);
        }
        return ((Ordered) chosen.newInstance(args)).getOrder();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return (char) 0;
    }

    private static Class<?> loadWithoutInit(ClassLoader loader, String binaryName) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String toBinaryName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace(File.separatorChar, '.');
    }

    private static Path classesRoot() throws Exception {
        URL location = ResponseDecoratorOrderTest.class.getProtectionDomain().getCodeSource().getLocation();
        return Paths.get(location.toURI()).resolveSibling("classes");
    }
}
