package com.leoli.gateway.admin.exception;

import com.leoli.license.client.exception.LicenseFeatureNotAllowedException;
import com.leoli.license.client.exception.LicenseNotLoadedException;
import com.leoli.license.client.exception.QuotaExceededException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for GlobalExceptionHandler.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleIllegalArgument_shouldReturnBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid parameter value");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("code"));
        assertEquals("Invalid parameter value", response.getBody().get("message"));
        assertNull(response.getBody().get("data"));
    }

    @Test
    void handleNoSuchElement_shouldReturnNotFound() {
        NoSuchElementException ex = new NoSuchElementException("Route not found");

        ResponseEntity<Map<String, Object>> response = handler.handleNoSuchElement(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().get("code"));
        assertEquals("Route not found", response.getBody().get("message"));
    }

    @Test
    void handleIllegalState_shouldReturnConflict() {
        IllegalStateException ex = new IllegalStateException("Resource already exists");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalState(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getBody().get("code"));
        assertEquals("Resource already exists", response.getBody().get("message"));
    }

    @Test
    void handleSecurity_shouldReturnForbidden() {
        SecurityException ex = new SecurityException("Access denied");

        ResponseEntity<Map<String, Object>> response = handler.handleSecurity(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().get("code"));
        assertEquals("Access denied", response.getBody().get("message"));
    }

    @Test
    void handleLicenseFeatureNotAllowed_shouldReturnForbiddenWithEditionDetail() {
        LicenseFeatureNotAllowedException ex =
                new LicenseFeatureNotAllowedException("ADVANCED_AUDIT", "ADVANCED", "BASIC");

        ResponseEntity<Map<String, Object>> response = handler.handleLicenseFeatureNotAllowed(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().get("code"));
        assertNull(response.getBody().get("data"));
        // The whole point of the handler: the message must carry the actionable detail instead
        // of the catch-all's "An unexpected error occurred".
        String message = (String) response.getBody().get("message");
        assertTrue(message.contains("ADVANCED_AUDIT"), message);
        assertTrue(message.contains("ADVANCED"), message);
        assertTrue(message.contains("BASIC"), message);
    }

    @Test
    void handleLicenseNotLoaded_shouldReturnServiceUnavailable() {
        LicenseNotLoadedException ex = new LicenseNotLoadedException();

        ResponseEntity<Map<String, Object>> response = handler.handleLicenseNotLoaded(ex);

        // 503, not 403: the caller cannot fix a missing license by re-authenticating.
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().get("code"));
        assertNull(response.getBody().get("data"));
        assertEquals("License is not loaded or invalid", response.getBody().get("message"));
    }

    @Test
    void handleQuotaExceeded_shouldReturnForbiddenWithQuotaDetail() {
        QuotaExceededException ex = new QuotaExceededException("maxInstances", 5, 6);

        ResponseEntity<Map<String, Object>> response = handler.handleQuotaExceeded(ex);

        // 403, not the catch-all 500: the caller is authenticated and the request is
        // well-formed, the active edition just does not entitle them to more of this resource.
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().get("code"));
        assertNull(response.getBody().get("data"));
        String message = (String) response.getBody().get("message");
        assertTrue(message.contains("maxInstances"), message);
        assertTrue(message.contains("limit=5"), message);
        assertTrue(message.contains("current=6"), message);
    }

    @Test
    void handleGeneric_shouldReturnInternalServerError() {
        Exception ex = new RuntimeException("Unexpected error");

        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().get("code"));
        assertEquals("An unexpected error occurred", response.getBody().get("message"));
    }

    @Test
    void handleValidationErrors_singleError_shouldReturnBadRequest() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("object", "routeName", "must not be blank");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ResponseEntity<Map<String, Object>> response = handler.handleValidationErrors(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("code"));
        assertTrue(((String) response.getBody().get("message")).contains("routeName"));
        assertTrue(((String) response.getBody().get("message")).contains("must not be blank"));
    }

    @Test
    void handleValidationErrors_multipleErrors_shouldCombineMessages() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError error1 = new FieldError("object", "routeName", "must not be blank");
        FieldError error2 = new FieldError("object", "uri", "must be a valid URL");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(error1, error2));

        ResponseEntity<Map<String, Object>> response = handler.handleValidationErrors(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String message = (String) response.getBody().get("message");
        assertTrue(message.contains("routeName"));
        assertTrue(message.contains("uri"));
    }

    @Test
    void allResponses_shouldHaveConsistentStructure() {
        // Test that all responses have code, data, and message fields
        ResponseEntity<Map<String, Object>> response1 = handler.handleIllegalArgument(new IllegalArgumentException("test"));
        ResponseEntity<Map<String, Object>> response2 = handler.handleNoSuchElement(new NoSuchElementException("test"));
        ResponseEntity<Map<String, Object>> response3 = handler.handleGeneric(new RuntimeException("test"));

        for (ResponseEntity<Map<String, Object>> response : List.of(response1, response2, response3)) {
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertTrue(body.containsKey("code"));
            assertTrue(body.containsKey("data"));
            assertTrue(body.containsKey("message"));
        }
    }

    @Test
    void handleGeneric_frameworkFault_shouldKeepItsStatusInsteadOf500() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "api/instances/instance/abc123");

        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(ex);

        // The point of the ErrorResponse branch: an unmapped path is a 404, not a 500.
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().get("code"));
        String message = (String) response.getBody().get("message");
        assertTrue(message.contains("api/instances/instance/abc123"), message);
    }

    /**
     * Dispatch-level coverage. Declaring the handlers is not enough - Spring has to resolve these
     * exceptions to them instead of the catch-all, and the pre-existing specific mappings
     * (field-level validation) have to keep winning over the broader ErrorResponse one.
     * Standalone MockMvc, so this needs no database.
     */
    @Nested
    class DispatchResolution {

        private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        @Test
        void unmatchedPath_shouldBeNotFoundNotServerError() throws Exception {
            mockMvc.perform(get("/api/instances/instance/abc123"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void nonNumericPathVariable_shouldBeBadRequestWithTheOffendingValue() throws Exception {
            MvcResult result = mockMvc.perform(get("/probe/tj9j963zq2x1"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertTrue(body.contains("Invalid value for 'id'"), body);
            assertTrue(body.contains("tj9j963zq2x1"), body);
            assertFalse(body.contains("An unexpected error occurred"), body);
        }

        @Test
        void malformedJsonBody_shouldBeBadRequestWithoutEchoingThePayload() throws Exception {
            MvcResult result = mockMvc.perform(post("/probe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            // Jackson would quote the broken payload back; the caller gets a fixed text instead.
            String body = result.getResponse().getContentAsString();
            assertEquals("Malformed request body", readMessage(body));
            assertFalse(body.contains("not json"), body);
        }

        @Test
        void missingRequiredParam_shouldBeBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(get("/probe-required"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertTrue(readMessage(result.getResponse().getContentAsString()).contains("instanceId"),
                    result.getResponse().getContentAsString());
        }

        @Test
        void invalidBean_shouldKeepTheFieldLevelValidationMessage() throws Exception {
            MvcResult result = mockMvc.perform(post("/probe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            // handleValidationErrors must still win over the ErrorResponse branch of
            // handleGeneric, otherwise the caller loses the field name and only sees the
            // generic problem detail.
            String body = result.getResponse().getContentAsString();
            assertTrue(body.contains("name: must not be blank"), body);
        }

        private String readMessage(String body) {
            return body.replaceAll("(?s).*\"message\":\"(.*?)\".*", "$1");
        }
    }

    static class ProbeBody {
        @NotBlank(message = "must not be blank")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/{id}")
        String byId(@PathVariable Long id) {
            return "ok:" + id;
        }

        @GetMapping("/probe-required")
        String required(@RequestParam String instanceId) {
            return "ok:" + instanceId;
        }

        @PostMapping("/probe")
        String create(@Valid @RequestBody ProbeBody body) {
            return "ok:" + body.getName();
        }
    }
}