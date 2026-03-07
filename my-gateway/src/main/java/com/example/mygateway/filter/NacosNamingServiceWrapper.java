package com.example.mygateway.filter;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Properties;

/**
 * Nacos NamingService Wrapper Class
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
public class NacosNamingServiceWrapper {

    private final NamingService namingService;

    public NacosNamingServiceWrapper(Properties properties) throws Exception {
        this.namingService = NacosFactory.createNamingService(properties);
        log.info("NacosNamingServiceWrapper initialized with server: {}", 
            properties.getProperty("serverAddr"));
    }

    /**
     * Get all instances of a service
     */
    public List<Instance> getAllInstances(String serviceName) throws Exception {
        return namingService.getAllInstances(serviceName);
    }

    /**
     * Get healthy instances of a service
     */
    public List<Instance> getHealthyInstances(String serviceName) throws Exception {
        return namingService.selectInstances(serviceName, true);
    }

    /**
     * Shutdown service
     */
    public void shutDown() throws Exception {
        namingService.shutDown();
    }
}
