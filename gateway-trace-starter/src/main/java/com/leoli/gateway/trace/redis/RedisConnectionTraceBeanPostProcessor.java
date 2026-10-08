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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Redis 命令级追踪（连接层实现）。
 * <p>
 * 为什么不用 AOP 切面：opsForValue()/opsForHash() 返回的 Operations 对象不是
 * Spring Bean，Spring AOP（代理模式）永远拦不到它们，导致最常见的
 * redisTemplate.opsForValue().get(...) 完全不被追踪。
 * <p>
 * 本实现改为包装 {@link RedisConnectionFactory}：getConnection() 返回的
 * RedisConnection 用 JDK 动态代理装饰，所有最终落到连接上的命令都会生成 span。
 * DefaultedRedisConnection 的 stringCommands()/hashCommands() 等访问器返回 this，
 * 代理会把自引用替换为代理本身，保证命令方法仍被拦截。
 * <p>
 * 开关：gateway.trace.enabled + gateway.trace.trace-redis（默认开）。
 *
 * @author leoli
 */
public class RedisConnectionTraceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionTraceBeanPostProcessor.class);

    private static final int MAX_KEY_LENGTH = 100;

    /**
     * 连接生命周期/事务/访问器方法，不生成 span。
     */
    private static final Set<String> SKIP_METHODS = Set.of(
            "close", "isOpen", "isQueueing", "isPipelined", "pipeline",
            "multi", "exec", "discard", "watch", "unwatch",
            "getNativeConnection", "commands",
            "toString", "hashCode", "equals", "getClass",
            "notify", "notifyAll", "wait"
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
        Class<?>[] interfaces = bean.getClass().getInterfaces();
        if (interfaces.length == 0) {
            return bean;
        }
        log.debug("Wrapping RedisConnectionFactory bean '{}' for command-level tracing", beanName);
        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces,
                new FactoryHandler(bean));
    }

    private boolean tracingConfigured() {
        return environment.getProperty("gateway.trace.enabled", Boolean.class, true)
                && environment.getProperty("gateway.trace.trace-redis", Boolean.class, true);
    }

    private static boolean shouldSkip(Method method) {
        String name = method.getName();
        if (SKIP_METHODS.contains(name)) {
            return true;
        }
        // stringCommands()/hashCommands()/... accessors (no-arg, self-returning)
        return name.endsWith("Commands") && method.getParameterCount() == 0;
    }

    /**
     * Factory proxy: wraps every RedisConnection handed out by the factory.
     */
    private class FactoryHandler implements java.lang.reflect.InvocationHandler {

        private final Object target;

        FactoryHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
            if (result instanceof RedisConnection connection) {
                return wrapConnection(connection);
            }
            return result == target ? proxy : result;
        }
    }

    private Object wrapConnection(RedisConnection connection) {
        Class<?>[] interfaces = connection.getClass().getInterfaces();
        if (interfaces.length == 0) {
            return connection;
        }
        ConnectionHandler handler = new ConnectionHandler(connection);
        Object proxy = Proxy.newProxyInstance(connection.getClass().getClassLoader(), interfaces, handler);
        handler.proxy = proxy;
        return proxy;
    }

    /**
     * Connection proxy: creates a span per Redis command.
     */
    private class ConnectionHandler implements java.lang.reflect.InvocationHandler {

        private final RedisConnection target;
        volatile Object proxy;

        ConnectionHandler(RedisConnection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(name)) {
                    return args[0] == p || target.equals(args[0]);
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(p);
                }
                return method.invoke(target, args);
            }

            if (shouldSkip(method) || !tracingActive()) {
                Object result;
                try {
                    result = method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
                // Self-returning accessors (stringCommands() etc.) must hand back the
                // proxy, otherwise subsequent command calls bypass tracing.
                return result == target ? p : result;
            }

            String spanId = TraceContextHolder.enterSpan("redis-" + name, SpanType.REDIS);
            if (spanId == null) {
                Object result;
                try {
                    result = method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
                return result == target ? p : result;
            }

            TraceContextHolder.addSpanTag(spanId, SpanTags.REDIS_COMMAND, name);
            String key = extractKey(args);
            if (key != null) {
                if (key.length() > MAX_KEY_LENGTH) {
                    key = key.substring(0, MAX_KEY_LENGTH) + "...";
                }
                TraceContextHolder.addSpanTag(spanId, SpanTags.REDIS_KEY, key);
            }

            try {
                Object result = method.invoke(target, args);
                if ("get".equals(name)) {
                    TraceContextHolder.addSpanTag(spanId, SpanTags.REDIS_HIT, result != null);
                }
                TraceContextHolder.exitSpan(spanId, true);
                return result == target ? p : result;
            } catch (InvocationTargetException e) {
                TraceContextHolder.exitSpanWithException(spanId, e.getTargetException());
                throw e.getTargetException();
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
}
