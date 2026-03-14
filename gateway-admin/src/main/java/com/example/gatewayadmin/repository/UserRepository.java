package com.example.gatewayadmin.repository;

import com.example.gatewayadmin.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * User repository interface.
 * Provides database operations for user authentication.
 *
 * @author leoli
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * Find user by username.
     * 
     * @param username the username to search
     * @return Optional containing the user if found
     */
    Optional<User> findByUsername(String username);
    
    /**
     * Check if username exists.
     * 
     * @param username the username to check
     * @return true if username exists
     */
    boolean existsByUsername(String username);
}
