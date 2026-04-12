package com.leoli.gateway.autoconfig;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.ExceptionHandlingWebHandler;
import org.springframework.web.server.handler.FilteringWebHandler;

import java.util.List;

/**
 * AutoConfiguration that fixes HttpHandler binding for separated management port.
 * 
 * Problem: Spring Boot creates two Web contexts when management.server.port is set.
 * Due to initialization order issues, port 80's HttpHandler may bind to management 
 * context's DispatcherHandler instead of main context's DispatcherHandler.
 * 
 * Solution: This AutoConfiguration runs before HttpHandlerAutoConfiguration and
 * validates the binding at startup.
 * 
 * @author leoli
 */
@Slf4j
@AutoConfiguration
@AutoConfigureBefore(HttpHandlerAutoConfiguration.class)
@ConditionalOnProperty(name = "management.server.port")
public class HttpHandlerBindingAutoConfiguration {

    /**
     * Listener that validates HttpHandler binding after server initialization.
     * Logs detailed information about which DispatcherHandler each port's HttpHandler is bound to.
     */
    @Bean
    public ApplicationListener<ReactiveWebServerInitializedEvent> httpHandlerBindingValidator(
            List<HttpHandler> httpHandlers,
            List<DispatcherHandler> dispatcherHandlers) {
        
        return event -> {
            int port = event.getWebServer().getPort();
            String contextName = event.getApplicationContext().getDisplayName();
            
            log.info("=== HttpHandler Binding Check for Port {} ===", port);
            log.info("Context: {}", contextName);
            
            // Find the HttpHandler for this port
            for (HttpHandler handler : httpHandlers) {
                if (handler instanceof HttpWebHandlerAdapter adapter) {
                    DispatcherHandler boundDispatcher = extractDispatcherHandler(adapter);
                    if (boundDispatcher != null) {
                        log.info("HttpHandler@{} bound to DispatcherHandler@{}",
                                Integer.toHexString(handler.hashCode()),
                                Integer.toHexString(boundDispatcher.hashCode()));
                        
                        // Check if it has RoutePredicateHandlerMapping (main context)
                        boolean hasRoutePredicate = hasRoutePredicateHandlerMapping(boundDispatcher);
                        boolean hasWebFluxEndpoint = hasWebFluxEndpointHandlerMapping(boundDispatcher);
                        
                        log.info("  - RoutePredicateHandlerMapping: {}", hasRoutePredicate);
                        log.info("  - WebFluxEndpointHandlerMapping: {}", hasWebFluxEndpoint);
                        
                        if (port == 80 && !hasRoutePredicate) {
                            log.error("❌ CRITICAL: Port 80 HttpHandler is NOT bound to main context! " +
                                    "Gateway routes will return 404!");
                        } else if (port == 8081 && !hasWebFluxEndpoint) {
                            log.error("❌ CRITICAL: Port 8081 HttpHandler is NOT bound to management context!");
                        } else if (port == 80 && hasRoutePredicate) {
                            log.info("✅ Port 80 HttpHandler correctly bound to main context (Gateway)");
                        } else if (port == 8081 && hasWebFluxEndpoint) {
                            log.info("✅ Port 8081 HttpHandler correctly bound to management context (Actuator)");
                        }
                    }
                }
            }
            
            // Log all DispatcherHandlers
            log.info("Total DispatcherHandlers: {}", dispatcherHandlers.size());
            for (DispatcherHandler dh : dispatcherHandlers) {
                log.info("DispatcherHandler@{}: {} handlerMappings",
                        Integer.toHexString(dh.hashCode()),
                        dh.getHandlerMappings() != null ? dh.getHandlerMappings().size() : 0);
            }
        };
    }

    private DispatcherHandler extractDispatcherHandler(HttpWebHandlerAdapter adapter) {
        try {
            var delegate = adapter.getDelegate();
            if (delegate instanceof ExceptionHandlingWebHandler exceptionHandler) {
                var innerDelegate = exceptionHandler.getDelegate();
                if (innerDelegate instanceof FilteringWebHandler filteringHandler) {
                    var handler = filteringHandler.getDelegate();
                    if (handler instanceof DispatcherHandler dispatcherHandler) {
                        return dispatcherHandler;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract DispatcherHandler: {}", e.getMessage());
        }
        return null;
    }

    private boolean hasRoutePredicateHandlerMapping(DispatcherHandler dispatcher) {
        if (dispatcher.getHandlerMappings() == null) return false;
        return dispatcher.getHandlerMappings().stream()
                .anyMatch(hm -> hm.getClass().getSimpleName().contains("RoutePredicate"));
    }

    private boolean hasWebFluxEndpointHandlerMapping(DispatcherHandler dispatcher) {
        if (dispatcher.getHandlerMappings() == null) return false;
        return dispatcher.getHandlerMappings().stream()
                .anyMatch(hm -> hm.getClass().getSimpleName().contains("WebFluxEndpoint"));
    }
}