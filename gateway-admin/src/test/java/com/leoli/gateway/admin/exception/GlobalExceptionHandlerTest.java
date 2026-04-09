package com.leoli.gateway.admin.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
}