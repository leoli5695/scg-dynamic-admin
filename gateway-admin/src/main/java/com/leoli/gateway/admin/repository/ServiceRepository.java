package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Service repository interface.
 *
 * @author leoli
 */
@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, Long> {

    /**
     * Find service by business service name.
     */
    ServiceEntity findByServiceName(String serviceName);

    List<ServiceEntity> findByEnabledTrue();

    /**
     * Find all services by instance ID.
     */
    List<ServiceEntity> findByInstanceId(String instanceId);

    /**
     * Find enabled services by instance ID.
     */
    List<ServiceEntity> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Find service by service name and instance ID.
     */
    ServiceEntity findByServiceNameAndInstanceId(String serviceName, String instanceId);

    /**
     * Check if service name exists within an instance.
     */
    boolean existsByServiceNameAndInstanceId(String serviceName, String instanceId);
}
