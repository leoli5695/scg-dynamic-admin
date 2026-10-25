package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.config.JwtTokenProvider;
import com.leoli.gateway.admin.model.User;
import com.leoli.gateway.admin.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The failed-login lockout, and specifically that it cannot be used as a denial of service.
 *
 * <p>{@code /api/auth/login} is permitAll, and the counter used to be keyed on the username alone.
 * Five wrong passwords from anywhere on the internet therefore locked the account for 15 minutes,
 * renewable indefinitely by repeating every 15 minutes. On a console whose only user is its sole
 * operator, that is a complete lockout with no recovery path short of editing the database - and the
 * second test below is the one that would have caught it.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceLockoutTest {

    private static final String ATTACKER_IP = "203.0.113.9";
    private static final String OPERATOR_IP = "192.168.1.50";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider);
    }

    private User admin() {
        User user = new User();
        user.setUsername("admin");
        user.setPassword("bcrypt-hash");
        user.setRole("ADMIN");
        return user;
    }

    @Test
    @DisplayName("an attacker exhausting the attempt budget locks out only their own address")
    void lockoutIsScopedToTheAttackerAddress() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.authenticate("admin", "wrong", ATTACKER_IP));
        }

        // The sixth attempt from the attacker is refused before the user store is even consulted.
        IllegalArgumentException locked = assertThrows(IllegalArgumentException.class,
                () -> authService.authenticate("admin", "wrong", ATTACKER_IP));
        assertTrue(locked.getMessage().contains("Too many failed login attempts"), locked.getMessage());
        verify(userRepository, times(5)).findByUsername("admin");

        // The regression that matters: the operator, from a different address, still gets in.
        when(passwordEncoder.matches(eq("correct"), anyString())).thenReturn(true);
        when(jwtTokenProvider.generateToken(eq("admin"), anyList())).thenReturn("jwt-token");
        assertEquals("jwt-token", authService.authenticate("admin", "correct", OPERATOR_IP));
    }

    @Test
    @DisplayName("a successful login resets that pair's budget rather than leaving it part-spent")
    void successClearsTheCounterForThatPair() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);
        when(passwordEncoder.matches(eq("correct"), anyString())).thenReturn(true);
        when(jwtTokenProvider.generateToken(eq("admin"), anyList())).thenReturn("jwt-token");

        for (int i = 0; i < 4; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.authenticate("admin", "wrong", ATTACKER_IP));
        }
        assertEquals("jwt-token", authService.authenticate("admin", "correct", ATTACKER_IP));

        // A full fresh budget is tolerated before the lock, proving the counter really was cleared.
        // MAX_FAILED_ATTEMPTS is 5 and the lock is checked BEFORE the password attempt, so it takes
        // five failures to fill the counter and a sixth call to be refused as locked.
        for (int i = 0; i < 5; i++) {
            IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                    () -> authService.authenticate("admin", "wrong", ATTACKER_IP));
            assertEquals("Invalid username or password", wrong.getMessage(),
                    "attempt " + (i + 1) + " should still be a normal credential failure, not a lock");
        }
        IllegalArgumentException locked = assertThrows(IllegalArgumentException.class,
                () -> authService.authenticate("admin", "wrong", ATTACKER_IP));
        assertTrue(locked.getMessage().contains("Too many failed login attempts"), locked.getMessage());
    }

    @Test
    @DisplayName("an unknown username and a wrong password are indistinguishable")
    void unknownUserDoesNotLeak() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> authService.authenticate("ghost", "whatever", ATTACKER_IP));

        assertEquals("Invalid username or password", e.getMessage());
    }

    @Test
    @DisplayName("a missing client IP falls into one shared bucket instead of throwing")
    void missingClientIpIsHandled() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> authService.authenticate("admin", "wrong", null));
        assertThrows(IllegalArgumentException.class, () -> authService.authenticate("admin", "wrong", "  "));
    }
}
