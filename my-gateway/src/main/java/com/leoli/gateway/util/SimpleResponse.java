package com.leoli.gateway.util;

import org.springframework.cloud.client.loadbalancer.Response;

/**
 * Simple implementation of Response for load balancer.
 *
 * @author leoli
 */
public class SimpleResponse<T> implements Response<T> {

    private final T server;

    public SimpleResponse(T server) {
        this.server = server;
    }

    @Override
    public boolean hasServer() {
        return server != null;
    }

    @Override
    public T getServer() {
        return server;
    }
}