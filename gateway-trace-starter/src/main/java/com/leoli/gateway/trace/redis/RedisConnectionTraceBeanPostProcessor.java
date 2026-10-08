package com.leoli.gateway.trace.redis;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.SpanTags;
import com.leoli.gateway.trace.model.SpanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 命令级追踪（连接层实现）。
 * <p>
 * 为什么不用 AOP 切面：opsForValue()/opsForHash() 返回的 Operations 对象不是
 * Spring Bean，Spring AOP（代理模式）永远拦不到它们，导致最常见的
 * redisTemplate.opsForValue().get(...) 完全不被追踪。
 * <p>
 * 实现分三层代理：
 * 1. {@link RedisConnectionFactory}：getConnection() 返回的连接被包装；
 * 2. {@link RedisConnection}：命令方法直接生成 span；stringCommands() 等
 *    访问器在 Spring Data Redis 3.x 返回**内部命令对象**（如 LettuceStringCommands，
 *    不是 this），因此访问器结果若实现 Redis*Commands 接口也需包装（带缓存）——
 *    这是 3.x 下 RedisTemplate 的实际调用路径；
 * 3. 命令对象（RedisStringCommands 等）：每个方法生成 span。
 * <p>
 * 开关：gateway.trace.enabled + gateway.trace.trace-redis（默认开）。
 *
 * @author leoli
 */
public class RedisConnectionTraceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionTraceBeanPostProcessor.class);

    private static final int MAX_KEY_LENGTH = 100;

    /**
     * 连接生命周期/事务方法，不生成 span。
     */
    private static final Set<String> CONNECTION_SKIP_METHODS = Set.of(
            "close", "isOpen", "isQueueing", "isPipelined", "pipeline",
            "multi", "exec", "discard", "watch", "unwatch",
            "getNativeConnection", "commands"
    );

    /**
     * 命令对象上不追踪的方法（生命周期类）。
     */
    private static final Set<String> COMMAND_SKIP_METHODS = Set.of(
            "close", "isOpen"
    );

    private final Environment environment;

    public RedisConnectionTraceBeanPostProcessor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RedisConnectionFactory)) {
            return bean;
        }
        // 接口可能声明在父类上（如 LettuceConnection extends AbstractRedisConnection），
        // getClass().getInterfaces() 会漏 → 必须遍历整个继承链收集
        Class<?>[] interfaces = interfaceArrayOf(bean.getClass());
        if (interfaces.length == 0) {
            return bean;
        }
        log.debug("Wrapping RedisConnectionFactory bean '{}' for command-level tracing", beanName);
        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces,
                new FactoryHandler(bean));
    }

    private Class<?>[] interfaceArrayOf(Class<?> clazz) {
        Set<Class<?>> interfaces = collectInterfaces(clazz);
        return interfaces.toArray(new Class<?>[0]);
    }

    private boolean tracingConfigured() {
        return environment.getProperty("gateway.trace.enabled", Boolean.class, true)
                && environment.getProperty("gateway.trace.trace-redis", Boolean.class, true);
    }

    // ==================== Layer 1: factory ====================

    private class FactoryHandler implements InvocationHandler {

        private final Object target;

        FactoryHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = invokeRaw(target, method, args);
            if (result instanceof RedisConnection connection) {
                return wrapConnection(connection);
            }
            return result == target ? proxy : result;
        }
    }

    private Object wrapConnection(RedisConnection connection) {
        Class<?>[] interfaces = interfaceArrayOf(connection.getClass());
        if (interfaces.length == 0) {
            return connection;
        }
        ConnectionHandler handler = new ConnectionHandler(connection);
        Object proxy = Proxy.newProxyInstance(connection.getClass().getClassLoader(), interfaces, handler);
        handler.self = proxy;
        return proxy;
    }

    // ==================== Layer 2: connection ====================

    private class ConnectionHandler implements InvocationHandler {

        private final RedisConnection target;
        /**
         * 访问器结果包装缓存：stringCommands() 等每次调用都返回同一内部对象，
         * 避免重复创建代理。
         */
        private final ConcurrentHashMap<Method, Object> accessorWrappers = new ConcurrentHashMap<>();
        volatile Object self;

        ConnectionHandler(RedisConnection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }

            boolean isAccessor = name.endsWith("Commands") && method.getParameterCount() == 0;
            if (CONNECTION_SKIP_METHODS.contains(name) || isAccessor) {
                Object result = invokeRaw(target, method, args);
                if (result == target) {
                    // 2.x 风格：访问器返回 this → 必须交回代理
                    return proxy;
                }
                if (result != null && method.getParameterCount() == 0 && isRedisCommands(result)) {
                    // 3.x 风格：访问器返回内部命令对象（如 LettuceStringCommands）→ 再包一层
                    return accessorWrappers.computeIfAbsent(method, m -> wrapCommands(result));
                }
                if (log.isDebugEnabled() && isAccessor && result != null) {
                    log.debug("Redis accessor {}() returned unwrappable {} — commands through it will not be traced",
                            name, result.getClass().getName());
                }
                return result;
            }

            return invokeTraced(target, method, args, proxy, name);
        }
    }

    private Object wrapCommands(Object commands) {
        Class<?>[] interfaces = interfaceArrayOf(commands.getClass());
        if (interfaces.length == 0) {
            return commands;
        }
        CommandHandler handler = new CommandHandler(commands);
        Object proxy = Proxy.newProxyInstance(commands.getClass().getClassLoader(), interfaces, handler);
        handler.self = proxy;
        return proxy;
    }

    // ==================== Layer 3: command objects ====================

    private class CommandHandler implements InvocationHandler {

        private final Object target;
        volatile Object self;

        CommandHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            if (COMMAND_SKIP_METHODS.contains(name)) {
                Object result = invokeRaw(target, method, args);
                return result == target ? proxy : result;
            }
            return invokeTraced(target, method, args, proxy, name);
        }
    }

    // ==================== shared tracing logic ====================

    private Object invokeTraced(Object target, Method method, Object[] args, Object proxy, String name)
            throws Throwable {
        if (!tracingActive()) {
            Object result = invokeRaw(target, method, args);
            return result == target ? proxy : result;
        }

        String spanId = TraceContextHolder.enterSpan("redis-" + name, SpanType.REDIS);
        if (spanId == null) {
            Object result = invokeRaw(target, method, args);
            return result == target ? proxy : result;
        }

        TraceContextHolder.addSpanTag(spanId, SpanTags.REDIS_COMMAND, name);
        String key = extractKey(args);
        if (key != null) {
            if (key.length() > MAX_KEY_LENGTH) {
                key = key.substring(0, MAX_KEY_LENGTH) + "...";
            }
            TraceContextHolder.addSpanTag(spanId, SpanTags.REDIS_KEY, key);
        }
        log.debug("Redis command traced: {} (spanId={})", name, spanId);

        try {
            Object result = invokeRaw(target, method, args);
            if ("get".equals(name) || "hGet".equals(name)) {
                TraceContextHolder.addSpanTag(spanId, SpanTags.REDIS_HIT, result != null);
            }
            TraceContextHolder.exitSpan(spanId, true);
            return result == target ? proxy : result;
        } catch (Throwable t) {
            TraceContextHolder.exitSpanWithException(spanId, t);
            throw t;
        }
    }

    private boolean tracingActive() {
        return tracingConfigured()
                && TraceContextHolder.hasTraceId()
                && TraceContextHolder.isSampled();
    }

    private Object invokeRaw(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    private Object handleObjectMethod(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("equals".equals(name)) {
            return args[0] == proxy;
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "TracedRedisProxy[" + proxy.getClass().getInterfaces()[0].getSimpleName() + "]";
        }
        return method.invoke(proxy, args);
    }

    /**
     * 判断对象是否实现任意 org.springframework.data.redis.connection.Redis*Commands 接口
     */
    private boolean isRedisCommands(Object obj) {
        for (Class<?> iface : collectInterfaces(obj.getClass())) {
            String n = iface.getName();
            if (n.startsWith("org.springframework.data.redis.connection.Redis") && n.endsWith("Commands")) {
                return true;
            }
        }
        return false;
    }

    private Set<Class<?>> collectInterfaces(Class<?> clazz) {
        Set<Class<?>> result = ConcurrentHashMap.newKeySet();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                collectInterfacesRecursive(iface, result);
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private void collectInterfacesRecursive(Class<?> iface, Set<Class<?>> result) {
        if (result.add(iface)) {
            for (Class<?> parent : iface.getInterfaces()) {
                collectInterfacesRecursive(parent, result);
            }
        }
    }

    private String extractKey(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (arg instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
