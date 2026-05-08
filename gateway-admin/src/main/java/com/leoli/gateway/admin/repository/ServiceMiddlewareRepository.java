package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.ServiceMiddlewareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 服务中间件映射Repository
 * 
 * @author leoli
 */
@Repository
public interface ServiceMiddlewareRepository extends JpaRepository<ServiceMiddlewareEntity, Long> {

    /**
     * 根据服务名称查询所有中间件
     */
    List<ServiceMiddlewareEntity> findByServiceName(String serviceName);

    /**
     * 根据服务名称和中间件类型查询
     */
    Optional<ServiceMiddlewareEntity> findByServiceNameAndMiddlewareType(
        String serviceName, String middlewareType);

    /**
     * 查询所有不同的服务名称
     */
    @Query("SELECT DISTINCT m.serviceName FROM ServiceMiddlewareEntity m")
    List<String> findAllServiceNames();

    /**
     * 查询指定类型的中间件
     */
    List<ServiceMiddlewareEntity> findByMiddlewareType(String middlewareType);

    /**
     * 查询最近更新的中间件
     */
    @Query("SELECT m FROM ServiceMiddlewareEntity m WHERE m.updatedAt > :since ORDER BY m.updatedAt DESC")
    List<ServiceMiddlewareEntity> findRecentlyUpdated(LocalDateTime since);

    /**
     * 查询启用监控的中间件
     */
    List<ServiceMiddlewareEntity> findByMonitoringEnabledTrue();

    /**
     * 根据服务名称查询启用监控的中间件
     */
    List<ServiceMiddlewareEntity> findByServiceNameAndMonitoringEnabledTrue(String serviceName);

    /**
     * 删除指定服务的中间件映射
     */
    int deleteByServiceName(String serviceName);

    /**
     * 统计服务数量
     */
    @Query("SELECT COUNT(DISTINCT m.serviceName) FROM ServiceMiddlewareEntity m")
    long countDistinctServices();

    /**
     * 统计中间件数量
     */
    long countByMiddlewareType(String middlewareType);

    /**
     * 查询Exporter地址
     */
    @Query("SELECT m.exporterUrl FROM ServiceMiddlewareEntity m WHERE m.serviceName = :serviceName AND m.middlewareType = :type")
    Optional<String> findExporterUrl(String serviceName, String type);
}