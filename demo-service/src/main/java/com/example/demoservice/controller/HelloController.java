package com.example.demoservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Demo service REST controller for load balancing tests.
 *
 * @author leoli
 */
@RestController
public class HelloController {

    private static final Logger log = LoggerFactory.getLogger(HelloController.class);

    @Value("${server.port:8080}")
    private String port;

    @GetMapping("/hello")
    public Map<String, String> hello(HttpServletRequest request) {
        // Log request info for load balancing statistics
        String clientIp = request.getRemoteAddr();
        String localIP = getLocalIP();
        log.info("[Load Balance Test] Request from {} -> Instance IP: {}, Port: {}", 
                 clientIp, localIP, port);
        
        Map<String, String> result = new HashMap<>();
        result.put("message", "Hello from Demo Service!");
        result.put("service", "demo-service");
        result.put("port", port);
        try {
            result.put("ip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            result.put("ip", "unknown");
        }
        return result;
    }

    private String getLocalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * Returns all request headers – useful for testing custom Header plugins
     */
    @GetMapping("/headers")
    public Map<String, Object> headers(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String localIP = getLocalIP();
        log.info("[Load Balance Test] Headers request from {} -> Instance IP: {}, Port: {}", 
                 clientIp, localIP, port);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Request Headers");
        result.put("service", "demo-service");
        result.put("port", port);
        try {
            result.put("ip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            result.put("ip", "unknown");
        }
        
        // Collect all request headers
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        result.put("headers", headers);
        
        return result;
    }

    @GetMapping("/health")
    public Map<String, String> health(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String localIP = getLocalIP();
        log.info("[Load Balance Test] Health check from {} -> Instance IP: {}, Port: {}", 
                 clientIp, localIP, port);
        
        Map<String, String> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "demo-service");
        return result;
    }

    /**
     * Returns all request parameters – useful for testing AddRequestParameter plugin
     */
    @GetMapping("/params")
    public Map<String, Object> params(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String localIP = getLocalIP();
        log.info("[Load Balance Test] Params request from {} -> Instance IP: {}, Port: {}", 
                 clientIp, localIP, port);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Request Parameters");
        result.put("service", "demo-service");
        result.put("port", port);
        try {
            result.put("ip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            result.put("ip", "unknown");
        }
        
        // Collect all request parameters
        Map<String, String> params = new HashMap<>();
        Enumeration<String> paramNames = request.getParameterNames();
        while (paramNames.hasMoreElements()) {
            String paramName = paramNames.nextElement();
            params.put(paramName, request.getParameter(paramName));
        }
        result.put("params", params);
        
        // Also include headers
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        result.put("headers", headers);
        
        return result;
    }

    /**
     * Returns request path info – useful for testing SetPath/RewritePath plugins
     */
    @GetMapping("/path/**")
    public Map<String, Object> pathInfo(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String localIP = getLocalIP();
        log.info("[Load Balance Test] Path request from {} -> Instance IP: {}, Port: {}", 
                 clientIp, localIP, port);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Request Path Info");
        result.put("service", "demo-service");
        result.put("port", port);
        try {
            result.put("ip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            result.put("ip", "unknown");
        }
        
        result.put("requestURI", request.getRequestURI());
        result.put("contextPath", request.getContextPath());
        result.put("servletPath", request.getServletPath());
        result.put("queryString", request.getQueryString());
        
        return result;
    }

    /**
     * Delayed response – useful for testing timeout plugins
     */
    @GetMapping("/delay")
    public Map<String, Object> delay(HttpServletRequest request) throws InterruptedException {
        String delayMs = request.getParameter("ms");
        long delay = delayMs != null ? Long.parseLong(delayMs) : 5000;
        
        log.info("[Timeout Test] Delaying response for {} ms on port {}", delay, port);
        Thread.sleep(delay);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Delayed Response");
        result.put("service", "demo-service");
        result.put("port", port);
        result.put("delayMs", delay);
        try {
            result.put("ip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            result.put("ip", "unknown");
        }
        
        return result;
    }

}
