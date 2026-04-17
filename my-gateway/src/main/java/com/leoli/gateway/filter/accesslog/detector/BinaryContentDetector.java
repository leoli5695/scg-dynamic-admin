package com.leoli.gateway.filter.accesslog.detector;

import com.leoli.gateway.filter.accesslog.config.AccessLogConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

import static com.leoli.gateway.constants.BinaryContentConstants.*;

/**
 * Binary content detector for access log body caching decisions.
 * <p>
 * Detects binary content (files, streams, etc.) that should not be
 * logged as text to avoid log pollution and encoding issues.
 * <p>
 * Detection methods:
 * - Content-Type header analysis
 * - File extension matching
 * - WebSocket/SSE detection
 * - Custom binary types from config
 *
 * @author leoli
 */
@Slf4j
@Component
public class BinaryContentDetector {

    /**
     * Check if request body should be cached for logging.
     * Skips caching for file uploads and binary content.
     *
     * @param request ServerHttpRequest
     * @param config Access log configuration
     * @return true if body should be cached
     */
    public boolean shouldCacheRequestBody(ServerHttpRequest request, AccessLogConfig config) {
        // Skip body caching for file uploads
        if (isFileUpload(request, config)) {
            log.debug("Skipping request body cache - file upload detected");
            return false;
        }

        // Check content type
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType != null && isBinaryContentType(contentType, config)) {
            log.debug("Skipping request body cache - binary content type: {}", contentType);
            return false;
        }

        return true;
    }

    /**
     * Check if response body should be cached for logging.
     * Skips caching for file downloads, SSE, WebSocket, and binary content.
     *
     * @param exchange ServerWebExchange
     * @param response ServerHttpResponse
     * @param config Access log configuration
     * @return true if body should be cached
     */
    public boolean shouldCacheResponseBody(ServerWebExchange exchange, 
                                             ServerHttpResponse response, 
                                             AccessLogConfig config) {
        // Skip WebSocket
        if (isWebSocketUpgrade(exchange)) {
            log.debug("Skipping response body cache - WebSocket upgrade");
            return false;
        }

        // Skip SSE responses
        MediaType contentType = response.getHeaders().getContentType();
        if (isSseResponse(contentType)) {
            log.debug("Skipping response body cache - SSE response");
            return false;
        }

        // Skip file downloads
        if (isFileDownload(response, config)) {
            log.debug("Skipping response body cache - file download detected");
            return false;
        }

        // Check binary content type
        if (contentType != null && isBinaryContentType(contentType, config)) {
            log.debug("Skipping response body cache - binary content type: {}", contentType);
            return false;
        }

        return true;
    }

    /**
     * Check if request is a file upload.
     *
     * @param request ServerHttpRequest
     * @param config Access log configuration
     * @return true if file upload detected
     */
    public boolean isFileUpload(ServerHttpRequest request, AccessLogConfig config) {
        MediaType contentType = request.getHeaders().getContentType();
        
        // multipart/form-data indicates file upload
        if (contentType != null && contentType.includes(MediaType.MULTIPART_FORM_DATA)) {
            return true;
        }

        // Check for binary content types
        if (contentType != null && isBinaryContentType(contentType, config)) {
            return true;
        }

        // Check URL extension for binary file uploads
        String path = request.getURI().getPath();
        if (hasBinaryExtension(path, config)) {
            return true;
        }

        return false;
    }

    /**
     * Check if response is a file download.
     *
     * @param response ServerHttpResponse
     * @param config Access log configuration
     * @return true if file download detected
     */
    public boolean isFileDownload(ServerHttpResponse response, AccessLogConfig config) {
        // Check Content-Disposition header for attachment
        String contentDisposition = response.getHeaders().getFirst("Content-Disposition");
        if (contentDisposition != null && contentDisposition.contains("attachment")) {
            return true;
        }

        // Check content type for binary types
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && isBinaryContentType(contentType, config)) {
            return true;
        }

        // Check for application/octet-stream (generic binary)
        if (contentType != null && contentType.includes(MediaType.APPLICATION_OCTET_STREAM)) {
            return true;
        }

        return false;
    }

    /**
     * Check if exchange is WebSocket upgrade request.
     *
     * @param exchange ServerWebExchange
     * @return true if WebSocket upgrade
     */
    public boolean isWebSocketUpgrade(ServerWebExchange exchange) {
        String upgrade = exchange.getRequest().getHeaders().getFirst("Upgrade");
        return "websocket".equalsIgnoreCase(upgrade);
    }

    /**
     * Check if response is SSE (Server-Sent Events).
     *
     * @param contentType Response content type
     * @return true if SSE response
     */
    public boolean isSseResponse(MediaType contentType) {
        if (contentType == null) {
            return false;
        }
        return contentType.toString().toLowerCase().contains("text/event-stream");
    }

    /**
     * Check if content type is binary.
     *
     * @param contentType Media type to check
     * @param config Access log configuration
     * @return true if binary content type
     */
    public boolean isBinaryContentType(MediaType contentType, AccessLogConfig config) {
        if (contentType == null) {
            return false;
        }

        String mimeType = contentType.toString().toLowerCase();

        // Check standard binary content types
        for (String type : BINARY_CONTENT_TYPES) {
            if (mimeType.contains(type.toLowerCase())) {
                return true;
            }
        }

        // Check binary type prefixes (image/, video/, audio/, etc.)
        for (String prefix : BINARY_TYPE_PREFIXES) {
            if (mimeType.startsWith(prefix.toLowerCase())) {
                return true;
            }
        }

        // Check custom binary content types from config
        List<String> customTypes = config.getCustomBinaryContentTypes();
        if (customTypes != null) {
            for (String customType : customTypes) {
                if (mimeType.contains(customType.toLowerCase())) {
                    return true;
                }
            }
        }

        // Check for binary subtype keywords
        String subtype = contentType.getSubtype();
        if (subtype != null && containsBinaryKeyword(subtype)) {
            return true;
        }

        return false;
    }

    /**
     * Check if path has binary file extension.
     *
     * @param path URL path
     * @param config Access log configuration
     * @return true if binary extension detected
     */
    public boolean hasBinaryExtension(String path, AccessLogConfig config) {
        if (path == null) {
            return false;
        }

        String lowerPath = path.toLowerCase();

        // Check standard binary extensions
        for (String ext : BINARY_EXTENSIONS) {
            if (lowerPath.endsWith(ext.toLowerCase())) {
                return true;
            }
        }

        // Check custom binary extensions from config
        List<String> customExtensions = config.getCustomBinaryExtensions();
        if (customExtensions != null) {
            for (String ext : customExtensions) {
                if (lowerPath.endsWith(ext.toLowerCase())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if subtype contains binary-related keywords.
     *
     * @param subtype Media type subtype
     * @return true if contains binary keyword
     */
    private boolean containsBinaryKeyword(String subtype) {
        if (subtype == null) {
            return false;
        }

        String lowerSubtype = subtype.toLowerCase();
        for (String keyword : BINARY_SUBTYPE_KEYWORDS) {
            if (lowerSubtype.contains(keyword.toLowerCase())) {
                return true;
            }
        }

        return false;
    }
}