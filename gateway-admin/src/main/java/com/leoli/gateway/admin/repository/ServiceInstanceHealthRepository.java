package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.ServiceInstanceHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Service instance health status repository.
 *
 * @author leoli
 */
@Repository
public interface ServiceInstanceHealthRepository extends JpaRepository<ServiceInstanceHealth, Long> {

    /**
     * Find by service ID, IP and port.
     */
    @Query("SELECT h FROM ServiceInstanceHealth h WHERE h.serviceId = :serviceId AND h.ip = :ip AND h.port = :port")
    ServiceInstanceHealth findByServiceIdAndIpAndPort(
            @Param("serviceId") String serviceId,
            @Param("ip") String ip,
            @Param("port") Integer port
    );

    /**
     * Find by IP and port (unique key).
     */
    @Query("SELECT h FROM ServiceInstanceHealth h WHERE h.ip = :ip AND h.port = :port")
    ServiceInstanceHealth findByIpAndPort(
            @Param("ip") String ip,
            @Param("port") Integer port
    );

    /**
     * Find all instances by service ID.
     */
    List<ServiceInstanceHealth> findByServiceId(String serviceId);

    /**
     * Find all instances.
     */
    List<ServiceInstanceHealth> findAll();

    /**
     * Find by service ID and health status.
     */
    List<ServiceInstanceHealth> findByServiceIdAndHealthStatus(String serviceId, String healthStatus);
}