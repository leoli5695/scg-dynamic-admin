package com.leoli.gateway.exception;

import org.springframework.http.HttpStatus;

/** Client-facing explanations; never interpolate raw upstream exception details. */
public final class GatewayErrorMessages {
    private GatewayErrorMessages() {}

    public static String forStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The gateway could not process this request. Check the parameters, request body and Content-Type.";
            case UNAUTHORIZED -> "Authentication is required or the supplied credentials are invalid or expired. Check your token or API key.";
            case FORBIDDEN -> "The request was rejected by an access policy. Check permissions, IP restrictions and security rules.";
            case NOT_FOUND -> "No gateway route matches this request. Check the path, HTTP method, host and whether the route is enabled.";
            case METHOD_NOT_ALLOWED -> "This HTTP method is not allowed for the requested resource. Check the allowed methods.";
            case REQUEST_TIMEOUT -> "The request did not complete within the allowed time. Check the connection and retry when appropriate.";
            case PAYLOAD_TOO_LARGE -> "The request body exceeds the gateway's configured size limit. Reduce the payload or contact the administrator.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request Content-Type is not supported. Use a format accepted by this endpoint.";
            case UNPROCESSABLE_ENTITY -> "The request failed validation. Check the required fields, data types and validation rules.";
            case TOO_MANY_REQUESTS -> "The request rate exceeds the configured limit. Wait before retrying and follow Retry-After when provided.";
            case BAD_GATEWAY -> "The gateway could not obtain a valid response from the backend. Check backend availability and connection settings.";
            case SERVICE_UNAVAILABLE -> "The gateway cannot currently serve this request. Check backend registration, instance enablement and circuit-breaker state.";
            case GATEWAY_TIMEOUT -> "The backend connection or response exceeded the gateway timeout. Check backend latency, network connectivity and timeout settings.";
            case INTERNAL_SERVER_ERROR -> "The gateway encountered an internal processing error. Contact the administrator with the traceId to inspect the logs.";
            default -> status.getReasonPhrase() + ". Contact the administrator with the traceId if the problem persists.";
        };
    }

    public static String forUnavailable(String reason) {
        if (reason == null) return forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        if (reason.startsWith("No enabled services")) {
            return "The route has no enabled backend service binding. Enable a binding and confirm the route configuration has reached the gateway.";
        }
        if (reason.startsWith("No enabled instances") || reason.startsWith("All service instances are disabled")) {
            return "The service has no enabled backend instance. Enable at least one instance and confirm the gateway has applied the configuration.";
        }
        if (reason.startsWith("No available instances") || reason.startsWith("Unable to find instance")) {
            return "No backend instance is available for the selected service. Check service registration, namespace/group, instance enablement and configuration synchronization.";
        }
        if (reason.startsWith("Failed to connect to service instance")) {
            return "The gateway could not connect to the selected backend and no further instance retry is available. Check backend availability and network connectivity.";
        }
        return forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static String forCode(ErrorCode code) {
        return switch (code) {
            case TOKEN_EXPIRED -> "The authentication token has expired. Obtain a new token and retry the request.";
            case INVALID_TOKEN -> "The authentication token is invalid. Check its format, signature and issuer.";
            case API_KEY_INVALID -> "The API key is invalid or unavailable. Check the configured key and its permissions.";
            case CIRCUIT_BREAKER_OPEN -> "The circuit breaker is open after backend failures. Requests are temporarily blocked; wait for recovery and check backend health.";
            case CONNECTION_REFUSED -> "The backend refused the connection. Check that the service is running and its listening port is reachable from the gateway.";
            case NO_HEALTHY_INSTANCES -> "No eligible backend instance could be selected. Check instance enablement, health and service discovery configuration.";
            case SERVICE_NOT_FOUND -> "The requested backend service is not configured or registered. Check the route binding and service discovery configuration.";
            case NOT_FOUND -> "The requested resource was not found. Check the requested address and resource identifier.";
            case CONFIG_ERROR -> "The gateway configuration could not be processed. Contact the administrator to check the route and service configuration.";
            case SERIALIZATION_ERROR -> "The gateway could not encode or decode the response. Contact the administrator with the traceId.";
            case REQUEST_TRANSFORM_ERROR -> "The gateway could not transform the request. Check the request format and configured transformation rules.";
            case RESPONSE_TRANSFORM_ERROR -> "The gateway could not transform the backend response. Check the response format and configured transformation rules.";
            default -> code.getMessage() + ". " + forStatus(code.getStatus());
        };
    }

    public static String withDefault(HttpStatus status, String message) {
        return message == null || message.isBlank() || message.equalsIgnoreCase(status.getReasonPhrase())
                ? forStatus(status) : message;
    }
}
