package com.leoli.gateway.constants;

import java.util.Set;

/**
 * Binary content constants for access log filtering.
 * <p>
 * Defines content types, type prefixes, and file extensions that indicate
 * binary content which should not be cached as text in access logs.
 * <p>
 * Used by AccessLogGlobalFilter to detect file upload/download scenarios.
 *
 * @author leoli
 */
public interface BinaryContentConstants {

    // ============================================================
    // Binary Content Types (application/*)
    // ============================================================

    /**
     * Binary content MIME types that should not be cached as text.
     * These are full MIME types like "application/octet-stream".
     */
    Set<String> BINARY_CONTENT_TYPES = Set.of(
            // Generic binary
            "application/octet-stream",
            "application/binary",

            // Documents
            "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-outlook",
            "application/vnd.ms-project",
            "application/vnd.ms-visio",
            "application/vnd.apple.pkpass",
            "application/epub+zip",

            // Archives
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-gzip",
            "application/x-bzip2",

            // Java/Executables
            "application/java-archive",
            "application/x-java-archive",
            "application/x-executable",
            "application/x-shockwave-flash",

            // Disk images/Packages
            "application/x-iso9660-image",
            "application/x-apple-diskimage",
            "application/x-debian-package",
            "application/x-redhat-package-manager",
            "application/x-rpm",

            // Base64 encoded binary
            "application/json+base64"
    );

    // ============================================================
    // Binary Type Prefixes
    // ============================================================

    /**
     * Content type prefixes that indicate binary content.
     * Any content type starting with these prefixes is considered binary.
     * E.g., "image/png", "video/mp4", "audio/mp3", "font/woff2", "model/obj"
     */
    Set<String> BINARY_TYPE_PREFIXES = Set.of(
            "image",
            "video",
            "audio",
            "font",
            "model"
    );

    // ============================================================
    // Binary File Extensions
    // ============================================================

    /**
     * File extensions that indicate binary files.
     * Used when Content-Type is missing but URL path contains extension.
     */
    Set<String> BINARY_EXTENSIONS = Set.of(
            // Documents
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",

            // Archives
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2",

            // Images
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".ico", ".svg", ".webp", ".tiff", ".tif",

            // Videos
            ".mp4", ".avi", ".mov", ".wmv", ".flv", ".mkv", ".webm", ".m4v",

            // Audio
            ".mp3", ".wav", ".flac", ".aac", ".ogg", ".wma", ".m4a",

            // Fonts
            ".woff", ".woff2", ".ttf", ".otf", ".eot",

            // Executables/Libraries
            ".exe", ".dll", ".so", ".jar", ".war", ".class", ".deb", ".rpm",

            // Disk images
            ".iso", ".dmg", ".img",

            // Mobile apps
            ".apk", ".ipa", ".aab",

            // Other binary
            ".bin", ".dat", ".sqlite", ".db"
    );

    // ============================================================
    // Binary Subtype Keywords (for fuzzy matching)
    // ============================================================

    /**
     * Keywords in content subtype that indicate binary.
     * Used for fuzzy matching when exact type is not in predefined list.
     * E.g., "application/x-custom-archive" matches "archive"
     */
    Set<String> BINARY_SUBTYPE_KEYWORDS = Set.of(
            "zip",
            "archive",
            "binary",
            "octet",
            "compressed",
            "package",
            "stream",
            "download"
    );
}