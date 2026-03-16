package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.ServiceInstanceHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 服务实例健康状态 Repository
 */
@Repository
public interface ServiceInstanceHealthRepository extends JpaRepository<ServiceInstanceHealth, Long> {
    
    /**
     * 根据服务 ID、IP 和端口查询
     */
    @Query("SELECT h FROM ServiceInstanceHealth h WHERE h.serviceId = :serviceId AND h.ip = :ip AND h.port = :port")
    ServiceInstanceHealth findByServiceIdAndIpAndPort(
        @Param("serviceId") String serviceId,
        @Param("ip") String ip,
        @Param("port") Integer port
    );
    
    /**
     * ✅ 根据 IP 和端口查询（唯一键）
     */
    @Query("SELECT h FROM ServiceInstanceHealth h WHERE h.ip = :ip AND h.port = :port")
    ServiceInstanceHealth findByIpAndPort(
        @Param("ip") String ip,
        @Param("port") Integer port
    );
    
    /**
     * 根据服务 ID 查询所有实例
     */
    List<ServiceInstanceHealth> findByServiceId(String serviceId);
    
    /**
     * 查询所有实例
     */
    List<ServiceInstanceHealth> findAll();
    
    /**
     * 根据服务 ID 和健康状态查询
     */
    List<ServiceInstanceHealth> findByServiceIdAndHealthStatus(String serviceId, String healthStatus);
}
