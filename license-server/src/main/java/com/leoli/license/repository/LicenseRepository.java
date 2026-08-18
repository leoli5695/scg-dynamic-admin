package com.leoli.license.repository;

import com.leoli.license.model.LicenseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * License Repository - Data Access Layer
 * <p>
 * Follows the same pattern as gateway-admin's RouteRepository:
 * - Standard JPA findById, save, delete
 * - Custom query methods for filtering
 * - Pageable support for pagination
 */
@Repository
public interface LicenseRepository extends JpaRepository<LicenseEntity, String> {

    /**
     * Find by customer email
     */
    List<LicenseEntity> findByCustomerEmail(String customerEmail);

    /**
     * Find by status
     */
    List<LicenseEntity> findByStatus(String status);

    /**
     * Find by edition
     */
    List<LicenseEntity> findByEdition(String edition);

    /**
     * Find by status and edition (for filtering)
     */
    Page<LicenseEntity> findByStatusAndEdition(String status, String edition, Pageable pageable);

    /**
     * Find by status (paginated)
     */
    Page<LicenseEntity> findByStatus(String status, Pageable pageable);

    /**
     * Find by edition (paginated)
     */
    Page<LicenseEntity> findByEdition(String edition, Pageable pageable);

    /**
     * Find all active licenses
     */
    @Query("SELECT l FROM LicenseEntity l WHERE l.status = 'ACTIVE'")
    List<LicenseEntity> findAllActive();

    /**
     * Find licenses expiring soon (within given days)
     */
    @Query("SELECT l FROM LicenseEntity l WHERE l.status = 'ACTIVE' AND l.expiresAt IS NOT NULL AND l.expiresAt BETWEEN :now AND :expiryThreshold")
    List<LicenseEntity> findExpiringSoon(LocalDateTime now, LocalDateTime expiryThreshold);

    /**
     * Find expired licenses (for auto-status update)
     */
    @Query("SELECT l FROM LicenseEntity l WHERE l.status = 'ACTIVE' AND l.expiresAt IS NOT NULL AND l.expiresAt < :now")
    List<LicenseEntity> findExpired(LocalDateTime now);

    /**
     * Find by customer email (paginated with search)
     */
    @Query("SELECT l FROM LicenseEntity l WHERE LOWER(l.customerEmail) LIKE LOWER(CONCAT('%', :email, '%')) OR LOWER(l.customerName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<LicenseEntity> searchByCustomer(String email, String keyword, Pageable pageable);

    /**
     * Check if license exists for customer email
     */
    boolean existsByCustomerEmail(String customerEmail);

    /**
     * Count by edition
     */
    long countByEdition(String edition);

    /**
     * Count by status
     */
    long countByStatus(String status);

    /**
     * Count active licenses
     */
    @Query("SELECT COUNT(l) FROM LicenseEntity l WHERE l.status = 'ACTIVE'")
    long countActive();

    /**
     * Find by machine binding
     */
    Optional<LicenseEntity> findByMachineId(String machineId);
}