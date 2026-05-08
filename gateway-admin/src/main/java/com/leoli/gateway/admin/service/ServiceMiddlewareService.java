package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.model.ServiceMiddlewareEntity;
import com.leoli.gateway.admin.repository.DistributedTraceRepository;
import com.leoli.gateway.admin.repository.ServiceMiddlewareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务中间件管理Service
 * 
 * 负责存储和查询服务中间件映射表
 * 
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceMiddlewareService {

    private final ServiceMiddlewareRepository middlewareRepository;
    private final ObjectMapper objectMapper;

    /**
     * 保存中间件元数据
     * 
     * @param serviceName 服务名称
     * @param instanceAddress 实例地址
     * @param middlewareType 中间件类型
     * @param middlewareHost 中间件主机
     * @param middlewarePort 中间件端口
     * @param exporterUrl Exporter地址
     */
    @Transactional
    public void saveMiddleware(String serviceName, String instanceAddress,
                               String middlewareType, String middlewareHost,
                               Integer middlewarePort, String exporterUrl) {
        
        // 查找是否已存在
        Optional<ServiceMiddlewareEntity> existing = middlewareRepository
            .findByServiceNameAndMiddlewareType(serviceName, middlewareType);
        
        ServiceMiddlewareEntity entity;
        if (existing.isPresent()) {
            // 更新现有记录
            entity = existing.get();
            entity.setInstanceAddress(instanceAddress);
            entity.setMiddlewareHost(middlewareHost);
            entity.setMiddlewarePort(middlewarePort);
            entity.setExporterUrl(exporterUrl);
            entity.setLastReportTime(LocalDateTime.now());
            log.debug("Updating middleware mapping: {} -> {}", serviceName, middlewareType);
        } else {
            // 创建新记录
            entity = new ServiceMiddlewareEntity();
            entity.setServiceName(serviceName);
            entity.setInstanceAddress(instanceAddress);
            entity.setMiddlewareType(middlewareType);
            entity.setMiddlewareHost(middlewareHost);
            entity.setMiddlewarePort(middlewarePort);
            entity.setExporterUrl(exporterUrl);
            entity.setMonitoringEnabled(true);
            entity.setLastReportTime(LocalDateTime.now());
            log.info("Creating middleware mapping: {} -> {} ({})", serviceName, middlewareType, exporterUrl);
        }
        
        middlewareRepository.save(entity);
    }

    /**
     * 批量保存中间件元数据
     */
    @Transactional
    public void saveBatch(String serviceName, String instanceAddress, 
                          List<Map<String, Object>> middlewares) {
        for (Map<String, Object> mw : middlewares) {
            String type = (String) mw.get("type");
            String host = (String) mw.get("host");
            Integer port = (Integer) mw.get("port");
            String exporter = (String) mw.get("exporterUrl");
            
            saveMiddleware(serviceName, instanceAddress, type, host, port, exporter);
        }
    }

    /**
     * 获取服务的所有中间件
     */
    public List<ServiceMiddlewareEntity> getServiceMiddlewares(String serviceName) {
        return middlewareRepository.findByServiceName(serviceName);
    }

    /**
     * 获取服务的Exporter地址映射
     * 
     * @return Map<中间件类型, Exporter地址>
     */
    public Map<String, String> getExporterMapping(String serviceName) {
        List<ServiceMiddlewareEntity> middlewares = middlewareRepository
            .findByServiceNameAndMonitoringEnabledTrue(serviceName);
        
        Map<String, String> mapping = new HashMap<>();
        for (ServiceMiddlewareEntity mw : middlewares) {
            if (mw.getExporterUrl() != null) {
                mapping.put(mw.getMiddlewareType(), mw.getExporterUrl());
            }
        }
        return mapping;
    }

    /**
     * 获取Exporter地址
     */
    public Optional<String> getExporterUrl(String serviceName, String middlewareType) {
        return middlewareRepository.findExporterUrl(serviceName, middlewareType);
    }

    /**
     * 获取所有服务名称
     */
    public List<String> getAllServiceNames() {
        return middlewareRepository.findAllServiceNames();
    }

    /**
     * 统计数据
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalServices", middlewareRepository.countDistinctServices());
        stats.put("totalRedis", middlewareRepository.countByMiddlewareType("redis"));
        stats.put("totalRocketMQ", middlewareRepository.countByMiddlewareType("rocketmq"));
        stats.put("totalMySQL", middlewareRepository.countByMiddlewareType("mysql"));
        stats.put("totalES", middlewareRepository.countByMiddlewareType("elasticsearch"));
        return stats;
    }

    /**
     * 获取服务的中间件信息（格式化返回给AI）
     */
    public Map<String, Object> getServiceMiddleware(String serviceName) {
        List<ServiceMiddlewareEntity> middlewares = getServiceMiddlewares(serviceName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", serviceName);
        result.put("middlewareCount", middlewares.size());
        
        List<Map<String, Object>> mwList = new ArrayList<>();
        for (ServiceMiddlewareEntity mw : middlewares) {
            Map<String, Object> mwInfo = new HashMap<>();
            mwInfo.put("type", mw.getMiddlewareType());
            mwInfo.put("host", mw.getMiddlewareHost());
            mwInfo.put("port", mw.getMiddlewarePort());
            mwInfo.put("exporterUrl", mw.getExporterUrl());
            mwInfo.put("instanceAddress", mw.getInstanceAddress());
            mwInfo.put("lastReportTime", mw.getLastReportTime());
            mwList.add(mwInfo);
        }
        
        result.put("middlewares", mwList);
        return result;
    }

    /**
     * 获取所有服务列表（格式化返回给AI）
     */
    public List<Map<String, Object>> getAllServices() {
        List<String> serviceNames = getAllServiceNames();
        
        List<Map<String, Object>> services = new ArrayList<>();
        for (String name : serviceNames) {
            Map<String, Object> service = new HashMap<>();
            service.put("serviceName", name);
            service.put("middlewareCount", middlewareRepository.findByServiceName(name).size());
            services.add(service);
        }
        
        return services;
    }

    /**
     * 获取中间件统计信息（格式化返回给AI）
     */
    public Map<String, Object> getMiddlewareStatistics() {
        Map<String, Object> stats = getStatistics();
        
        // 添加更详细的分类统计
        Map<String, Long> byType = new HashMap<>();
        byType.put("redis", middlewareRepository.countByMiddlewareType("redis"));
        byType.put("rocketmq", middlewareRepository.countByMiddlewareType("rocketmq"));
        byType.put("mysql", middlewareRepository.countByMiddlewareType("mysql"));
        byType.put("elasticsearch", middlewareRepository.countByMiddlewareType("elasticsearch"));
        byType.put("kafka", middlewareRepository.countByMiddlewareType("kafka"));
        byType.put("mongodb", middlewareRepository.countByMiddlewareType("mongodb"));
        stats.put("byType", byType);
        
        return stats;
    }

    /**
     * 删除服务的中间件映射
     */
    @Transactional
    public int deleteByServiceName(String serviceName) {
        return middlewareRepository.deleteByServiceName(serviceName);
    }
}