package com.example.mygateway.filter;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.springframework.cloud.client.ServiceInstance;

import java.net.URI;
import java.util.Map;

/**
 * Nacos Instance 到 Spring Cloud ServiceInstance 的适配器
 */
public class NacosServiceInstance implements ServiceInstance {

    private final Instance instance;
    private final String scheme;

    public NacosServiceInstance(Instance instance) {
        this(instance, "http");
    }

    public NacosServiceInstance(Instance instance, String scheme) {
        this.instance = instance;
        this.scheme = scheme;
    }

    @Override
    public String getServiceId() {
        return instance.getServiceName();
    }

    @Override
    public String getHost() {
        return instance.getIp();
    }

    @Override
    public int getPort() {
        return instance.getPort();
    }

    @Override
    public boolean isSecure() {
        return "https".equals(scheme);
    }

    @Override
    public URI getUri() {
        try {
            return new URI(scheme + "://" + instance.getIp() + ":" + instance.getPort());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create URI", e);
        }
    }

    @Override
    public Map<String, String> getMetadata() {
        return instance.getMetadata();
    }

    @Override
    public String getInstanceId() {
        return instance.getInstanceId();
    }
}
