package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for revoked JWT tokens.
 *
 * @author leoli
 */
@Repository
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {

    Optional<RevokedToken> findByTokenHash(String tokenHash);

    boolean existsByTokenHash(String tokenHash);

    /**
     * Purge rows whose original JWT has expired - they can no longer be
     * presented, so keeping them only grows the table.
     */
    @Modifying
    @Query("DELETE FROM RevokedToken rt WHERE rt.expiresAt < :now")
    int deleteByExpiresAtBefore(@Param("now") Instant now);
}
