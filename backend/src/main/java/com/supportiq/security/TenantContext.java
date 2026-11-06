package com.supportiq.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

public final class TenantContext {
    private TenantContext() {}

    public static Jwt jwtOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    public static UUID companyIdOrNull() {
        Jwt jwt = jwtOrNull();
        String value = jwt == null ? null : jwt.getClaimAsString("companyId");
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    public static UUID requireCompanyId() {
        UUID id = companyIdOrNull();
        if (id == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A company account is required.");
        return id;
    }

    public static UUID userIdOrNull() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) return null;
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    public static String role() {
        Jwt jwt = jwtOrNull();
        return jwt == null ? null : jwt.getClaimAsString("role");
    }

    public static boolean isStaff() {
        return "AGENT".equals(role()) || "COMPANY_ADMIN".equals(role()) || "SUPER_ADMIN".equals(role());
    }
}
