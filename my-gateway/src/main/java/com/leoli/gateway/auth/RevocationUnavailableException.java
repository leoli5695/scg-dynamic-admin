package com.leoli.gateway.auth;

/** Authentication cannot establish whether the token has been revoked. */
public class RevocationUnavailableException extends RuntimeException {
    public RevocationUnavailableException(Throwable cause) {
        super("JWT revocation service unavailable", cause);
    }
}
