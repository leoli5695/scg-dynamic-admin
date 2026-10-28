package com.seckill.service;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;

public final class TrustedUserIdentity {
    private TrustedUserIdentity() {}
    public static long requireUserId() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated())throw new AccessDeniedException("Verified identity required");
        try {
            long userId=Long.parseLong(jwt.getName());
            if(userId<=0)throw new NumberFormatException();
            return userId;
        }catch(NumberFormatException e){throw new AccessDeniedException("Invalid user identity");}
    }
    public static void requireSameUser(long userId) {
        if(userId!=requireUserId())throw new AccessDeniedException("Cannot access another user's order");
    }
    public static void requireAdmin() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || authentication.getAuthorities().stream().noneMatch(a->"SCOPE_seckill:admin".equals(a.getAuthority())))
            throw new AccessDeniedException("Administrative scope required");
    }
}
