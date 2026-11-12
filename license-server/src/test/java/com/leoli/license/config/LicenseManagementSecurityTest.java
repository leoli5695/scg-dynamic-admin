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
import org.springframework.test.context.TestPropertySource;
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
 * The management API used to be unreachable: the chain required authentication but no
 * authentication mechanism existed, so every operator endpoint answered 403 forever.
 */
@WebMvcTest(controllers = LicenseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "license.server.management.username=operator",
        "license.server.management.password=correct-horse-battery"
})
class LicenseManagementSecurityTest {

    @MockBean
    private LicenseService licenseService;
    @MockBean
    private AuditLogService auditLogService;
    @MockBean
    private RsaSignatureService rsaSignatureService;

    private final WebApplicationContext context;
    private final Filter securityFilter;
    private MockMvc mvc;

    LicenseManagementSecurityTest(WebApplicationContext context,
                                  @Qualifier("springSecurityFilterChain") Filter securityFilter) {
        this.context = context;
        this.securityFilter = securityFilter;
    }

    @BeforeEach
    void setUp() {
        // Registered explicitly: without spring-security-test on the classpath Boot does not
        // attach the security chain to MockMvc on its own, and every request would look public.
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilter).build();
        when(licenseService.getStatistics()).thenReturn(Map.of("total", 1L));
        when(licenseService.getFeatureDefinitions()).thenReturn(Map.of());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the configured operator can reach a management endpoint")
    void managementAcceptsConfiguredCredentials() throws Exception {
        mvc.perform(get("/api/licenses/statistics")
                        .header(HttpHeaders.AUTHORIZATION, basic("operator", "correct-horse-battery")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a management endpoint without credentials is challenged, not silently served")
    void managementRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/licenses/statistics")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong password is rejected")
    void managementRejectsWrongPassword() throws Exception {
        mvc.perform(get("/api/licenses/statistics")
                        .header(HttpHeaders.AUTHORIZATION, basic("operator", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the client-facing endpoints stay public")
    void clientEndpointsRemainPublic() throws Exception {
        mvc.perform(get("/api/licenses/features")).andExpect(status().isOk());
    }
}