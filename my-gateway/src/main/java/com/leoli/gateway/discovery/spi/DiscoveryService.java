package com.leoli.gateway.discovery.spi;

import com.leoli.gateway.enums.CenterType;

import java.util.List;

/**
 * Service discovery interface.
 */
public interface DiscoveryService {

    /**
     * Get all instances of a service.
     */
    List<ServiceInstance> getInstances(String serviceName);

    /**
     * Get only healthy instances of a service.
     */
    List<ServiceInstance> getHealthyInstances(String serviceName);

    /**
     * Get center type.
     */
    CenterType getCenterType();

    /**
     * Service instance representation.
     */
    class ServiceInstance {
        private String serviceId;
        private String host;
        private int port;
        private boolean healthy;
        private double weight;

        public ServiceInstance() {
            this.weight = 1.0;
        }

        public ServiceInstance(String serviceId, String host, int port) {
            this.serviceId = serviceId;
            this.host = host;
            this.port = port;
            this.healthy = true;
            this.weight = 1.0;
        }

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }
    }
}
