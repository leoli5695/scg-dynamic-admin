package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import com.leoli.gateway.admin.service.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(SessionExpirySecurityTest.Config.class)
class SessionExpirySecurityTest {
    private static final String SECRET = "test-session-expiry-secret-with-at-least-sixty-four-bytes-0123456789";
    @Configuration @EnableWebMvc @Import(SecurityConfig.class)
    static class Config {
        @Bean JwtTokenProvider jwt() { return new JwtTokenProvider(SECRET, 60_000, "BASIC"); }
        @Bean TokenRevocationService revocations() { return mock(TokenRevocationService.class); }
        @Bean GatewayAdminProperties properties() {
            var properties = new GatewayAdminProperties();
            properties.getSecurity().setInternalToken("test-internal-report-token");
            return properties;
        }
        @Bean ProbeController controller() { return new ProbeController(); }
    }
    @RestController
    static class ProbeController {
        @GetMapping("/api/token-usage/overview") String overview() { return "ok"; }
        @PostMapping("/api/routes") String write() { return "ok"; }
        @PostMapping("/api/auth/login") String login() { return "public"; }
    }
    @Autowired WebApplicationContext context;
    @Autowired JwtTokenProvider jwt;
    @Autowired TokenRevocationService revocations;
    MockMvc mvc;
    @BeforeEach void setup() {
        reset(revocations);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void missingMalformedAndExpiredTokensReturn401() throws Exception {
        mvc.perform(get("/api/token-usage/overview")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/token-usage/overview").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        String expired = new JwtTokenProvider(SECRET, -60_000, "BASIC").generateToken("admin", List.of("ADMIN"));
        mvc.perform(get("/api/token-usage/overview").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }
    @Test void revokedTokenReturns401ButValidViewerWriteReturns403() throws Exception {
        String token = jwt.generateToken("viewer", List.of("VIEWER"));
        when(revocations.isRevoked(token)).thenReturn(true);
        mvc.perform(get("/api/token-usage/overview").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        when(revocations.isRevoked(token)).thenReturn(false);
        mvc.perform(get("/api/token-usage/overview").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/routes").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void publicLoginStillWorksWithAnExpiredStoredToken() throws Exception {
        String expired = new JwtTokenProvider(SECRET, -60_000, "BASIC").generateToken("admin", List.of("ADMIN"));
        mvc.perform(post("/api/auth/login").header("Authorization", "Bearer " + expired)).andExpect(status().isOk());
    }
}
