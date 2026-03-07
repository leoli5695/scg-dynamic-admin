package com.example.demoservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HelloController {

    @Value("${server.port:8080}")
    private String port;

    @GetMapping("/hello")
    public Map<String, String> hello() {
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

    /**
     * 返回所有请求头的接口，方便测试自定义 Header 插件
     */
    @GetMapping("/headers")
    public Map<String, Object> headers(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Request Headers");
        result.put("service", "demo-service");
        result.put("port", port);
        try {
            result.put("ip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            result.put("ip", "unknown");
        }
        
        // 获取所有请求头
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
    public Map<String, String> health() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "demo-service");
        return result;
    }

}
