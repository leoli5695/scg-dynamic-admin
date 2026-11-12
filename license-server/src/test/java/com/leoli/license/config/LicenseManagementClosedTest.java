package com.leoli.license.config;

import com.leoli.license.controller.LicenseController;
import com.leoli.license.service.AuditLogService;
import com.leoli.license.service.LicenseService;
import com.leoli.license.service.RsaSignatureService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With no operator credentials configured the management API stays closed. There is no default
 * account to guess, and supplying a Basic header must not talk the server into serving it.
 */
@WebMvcTest(controllers = LicenseController.class)
@Import(SecurityConfig.class)
class LicenseManagementClosedTest {

    @MockBean
    private LicenseService licenseService;
    @MockBean
    private AuditLogService auditLogService;
    @MockBean
    private RsaSignatureService rsaSignatureService;

    private final WebApplicationContext context;
    private final Filter securityFilter;
    private MockMvc mvc;

    LicenseManagementClosedTest(WebApplicationContext context,
                                @Qualifier("springSecurityFilterChain") Filter securityFilter) {
        this.context = context;
        this.securityFilter = securityFilter;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilter).build();
        when(licenseService.getStatistics()).thenReturn(Map.of("total", 1L));
        when(licenseService.getFeatureDefinitions()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("management is denied when no credentials are configured")
    void managementIsDeniedWithoutConfiguration() throws Exception {
        mvc.perform(get("/api/licenses/statistics")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an invented Basic credential does not open management")
    void inventedCredentialsDoNotOpenManagement() throws Exception {
        String header = "Basic " + Base64.getEncoder()
                .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/licenses/statistics").header(HttpHeaders.AUTHORIZATION, header))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the client-facing endpoints stay public even when management is closed")
    void clientEndpointsRemainPublic() throws Exception {
        mvc.perform(get("/api/licenses/features")).andExpect(status().isOk());
    }
}