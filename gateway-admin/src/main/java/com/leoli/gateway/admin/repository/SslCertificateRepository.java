package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.SslCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SslCertificateRepository extends JpaRepository<SslCertificate, Long> {

    /**
     * Find certificate by domain
     */
    Optional<SslCertificate> findByDomain(String domain);

    /**
     * Find certificate by instanceId and domain
     */
    Optional<SslCertificate> findByInstanceIdAndDomain(String instanceId, String domain);

    /**
     * Find all certificates by instanceId
     */
    List<SslCertificate> findByInstanceId(String instanceId);

    /**
     * Find all enabled certificates by instanceId
     */
    List<SslCertificate> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Find all enabled certificates
     */
    List<SslCertificate> findByEnabled(Boolean enabled);

    /**
     * Find certificates by status
     */
    List<SslCertificate> findByStatus(String status);

    /**
     * Find certificates by instanceId and status
     */
    List<SslCertificate> findByInstanceIdAndStatus(String instanceId, String status);

    /**
     * Find certificates expiring within given days
     */
    @Query("SELECT c FROM SslCertificate c WHERE c.enabled = true AND c.validTo BETWEEN :now AND :endDate")
    List<SslCertificate> findExpiringSoon(LocalDateTime now, LocalDateTime endDate);

    /**
     * Find certificates expiring within given days for a specific instance
     */
    @Query("SELECT c FROM SslCertificate c WHERE c.instanceId = :instanceId AND c.enabled = true AND c.validTo BETWEEN :now AND :endDate")
    List<SslCertificate> findExpiringSoonByInstanceId(String instanceId, LocalDateTime now, LocalDateTime endDate);

    /**
     * Find all valid certificates for a route
     */
    @Query("SELECT c FROM SslCertificate c WHERE c.enabled = true AND c.status = 'VALID'")
    List<SslCertificate> findAllValidCertificates();

    /**
     * Find all valid certificates by instanceId
     */
    @Query("SELECT c FROM SslCertificate c WHERE c.instanceId = :instanceId AND c.enabled = true AND c.status = 'VALID'")
    List<SslCertificate> findAllValidCertificatesByInstanceId(String instanceId);

    /**
     * Find expired certificates
     */
    @Query("SELECT c FROM SslCertificate c WHERE c.status = 'EXPIRED'")
    List<SslCertificate> findExpiredCertificates();

    /**
     * Count certificates by status
     */
    long countByStatus(String status);

    /**
     * Count certificates by instanceId and status
     */
    long countByInstanceIdAndStatus(String instanceId, String status);

    /**
     * Delete all certificates by instance ID.
     */
    int deleteByInstanceId(String instanceId);
}