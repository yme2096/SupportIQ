package com.supportiq.service;

import com.supportiq.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final BillingService billing;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.security.access-minutes:10}") long accessMinutes;
    @Value("${app.security.refresh-days:14}") long refreshDays;
    @Value("${app.security.frontend-url}") String frontendUrl;
    @Value("${app.security.local-link-exposure:true}") boolean exposeLocalLinks;

    public AuthService(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder jwtEncoder, BillingService billing) {
        this.db = db; this.passwords = passwords; this.jwtEncoder = jwtEncoder; this.billing=billing;
    }

    @Transactional
    public Session registerCompany(String companyName, String businessEmail, String fullName, String password, String phone, String website) {
        String normalizedEmail = normalizeEmail(businessEmail);
        if (existsEmail(normalizedEmail)) throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with that email already exists.");
        String slug = slug(companyName) + "-" + UUID.randomUUID().toString().substring(0, 6);
        UUID companyId = db.queryForObject("insert into companies(name,slug,business_email,phone,website) values(?,?,?,?,?) returning id", UUID.class,
                companyName.trim(), slug, normalizedEmail, emptyToNull(phone), emptyToNull(website));
        Integer roleId = roleId("COMPANY_ADMIN");
        UUID userId = db.queryForObject("insert into app_users(company_id,role_id,email,full_name,password_hash,phone,status) values(?,?,?,?,?,?, 'ACTIVE') returning id", UUID.class,
                companyId, roleId, normalizedEmail, fullName.trim(), passwords.encode(password), emptyToNull(phone));
        db.update("insert into company_settings(company_id,settings) values(?, '{\"workspaceName\":\"Support workspace\",\"timezone\":\"Asia/Kolkata\"}'::jsonb)", companyId);
        audit(companyId, userId, "COMPANY_REGISTERED", "COMPANY", companyId.toString(), Map.of("slug", slug));
        return session(userId, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Session login(String email, String password, String remoteAddress) {
        String normalized = normalizeEmail(email);
        enforceRateLimit("login:" + remoteAddress + ":" + normalized, 10, 900);
        List<Map<String,Object>> users = db.queryForList("select u.id,u.company_id,u.email,u.full_name,u.password_hash,u.status,r.name role,c.status company_status from app_users u join roles r on r.id=u.role_id left join companies c on c.id=u.company_id where lower(u.email)=?", normalized);
        if (users.isEmpty() || !passwords.matches(password, Objects.toString(users.get(0).get("password_hash"), "")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        Map<String,Object> user = users.get(0);
        if (!"ACTIVE".equals(user.get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not active.");
        if (user.get("company_id") != null && !"ACTIVE".equals(user.get("company_status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This company workspace is suspended. Contact SupportIQ billing support.");
        return session((UUID) user.get("id"), UUID.randomUUID());
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh session is unavailable.");
        String tokenHash = hash(rawToken);
        List<Map<String,Object>> matches = db.queryForList("select token_hash,family_id,user_id,expires_at,revoked_at from refresh_tokens where token_hash=?", tokenHash);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh session is invalid.");
        Map<String,Object> prior = matches.get(0);
        UUID family = (UUID) prior.get("family_id");
        if (prior.get("revoked_at") != null) {
            db.update("update refresh_tokens set revoked_at=coalesce(revoked_at,now()) where family_id=?", family);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh session was already used or revoked.");
        }
        Instant expiry = ((java.sql.Timestamp) prior.get("expires_at")).toInstant();
        if (expiry.isBefore(Instant.now())) {
            db.update("update refresh_tokens set revoked_at=now() where token_hash=?", tokenHash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh session has expired.");
        }
        UUID userId = (UUID) prior.get("user_id");
        Map<String,Object> user = user(userId);
        if (!"ACTIVE".equals(user.get("status"))) {
            db.update("update refresh_tokens set revoked_at=now() where family_id=?", family);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not active.");
        }
        if(user.get("company_id")!=null&& !"ACTIVE".equals(user.get("company_status"))) {
            db.update("update refresh_tokens set revoked_at=now() where family_id=?",family);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This company workspace is suspended. Contact SupportIQ billing support.");
        }
        String replacement = token();
        String replacementHash = hash(replacement);
        db.update("update refresh_tokens set revoked_at=now(),replaced_by_hash=? where token_hash=? and revoked_at is null", replacementHash, tokenHash);
        db.update("insert into refresh_tokens(token_hash,family_id,user_id,expires_at) values(?,?,?,?)", replacementHash, family, userId, timestampAfter(Duration.ofDays(refreshDays)));
        return new Session(accessToken(user), replacement, publicUser(user));
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) db.update("update refresh_tokens set revoked_at=coalesce(revoked_at,now()) where token_hash=?", hash(rawToken));
    }

    public Map<String,Object> me(UUID userId) { return publicUser(user(userId)); }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        Map<String,Object> user = user(userId);
        if (!passwords.matches(currentPassword, Objects.toString(user.get("password_hash"), "")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        db.update("update app_users set password_hash=?,updated_at=now() where id=?", passwords.encode(newPassword), userId);
        db.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null", userId);
        audit((UUID) user.get("company_id"), userId, "PASSWORD_CHANGED", "USER", userId.toString(), Map.of());
    }

    @Transactional
    public Map<String,Object> forgotPassword(String email, String remoteAddress) {
        String normalized = normalizeEmail(email);
        enforceRateLimit("password-reset:" + remoteAddress + ":" + normalized, 4, 3600);
        List<UUID> ids = db.query("select id from app_users where lower(email)=? and status='ACTIVE'", (rs,n) -> UUID.fromString(rs.getString(1)), normalized);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("message", "If an active account matches that email, a reset link is ready.");
        if (!ids.isEmpty()) {
            UUID userId = ids.get(0);
            db.update("update password_reset_tokens set used_at=now() where user_id=? and used_at is null", userId);
            String raw = token();
            db.update("insert into password_reset_tokens(token_hash,user_id,expires_at) values(?,?,?)", hash(raw), userId, timestampAfter(Duration.ofMinutes(30)));
            if (exposeLocalLinks) response.put("localResetUrl", frontendUrl + "/reset-password?token=" + raw);
        }
        return response;
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        List<Map<String,Object>> rows = db.queryForList("select user_id from password_reset_tokens where token_hash=? and used_at is null and expires_at>now()", hash(rawToken));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset link is invalid or expired.");
        UUID userId = (UUID) rows.get(0).get("user_id");
        db.update("update app_users set password_hash=?,updated_at=now() where id=?", passwords.encode(newPassword), userId);
        db.update("update password_reset_tokens set used_at=now() where token_hash=? and used_at is null", hash(rawToken));
        db.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null", userId);
        Map<String,Object> user = user(userId);
        audit((UUID) user.get("company_id"), userId, "PASSWORD_RESET", "USER", userId.toString(), Map.of());
    }

    @Transactional
    public Map<String,Object> inviteAgent(String email, UUID actorId) {
        UUID companyId = TenantContext.requireCompanyId();
        billing.assertAgentCapacity(companyId);
        Map<String,Object> actor = user(actorId);
        String normalized = normalizeEmail(email);
        if (existsEmail(normalized)) throw new ResponseStatusException(HttpStatus.CONFLICT, "That email already belongs to an account.");
        String raw = token();
        UUID invitationId = db.queryForObject("insert into agent_invitations(company_id,invited_by,email,role_id,token_hash,expires_at) values(?,?,?,?,?,?) returning id",
                UUID.class, companyId, actorId, normalized, roleId("AGENT"), hash(raw), timestampAfter(Duration.ofDays(7)));
        audit(companyId, actorId, "AGENT_INVITED", "INVITATION", invitationId.toString(), Map.of("email", normalized));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", invitationId);
        result.put("email", normalized);
        result.put("role", "AGENT");
        result.put("expiresInDays", 7);
        if (exposeLocalLinks) result.put("invitationUrl", frontendUrl + "/accept-invitation?token=" + raw);
        return result;
    }

    public Map<String,Object> validateInvitation(String rawToken) {
        List<Map<String,Object>> rows = db.queryForList("select i.email,i.expires_at,c.name company_name from agent_invitations i join companies c on c.id=i.company_id where i.token_hash=? and i.accepted_at is null", hash(rawToken));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation is invalid or already accepted.");
        Map<String,Object> row = rows.get(0);
        if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "Invitation has expired.");
        return Map.of("email", row.get("email"), "companyName", row.get("company_name"), "role", "AGENT", "expiresAt", row.get("expires_at"));
    }

    @Transactional
    public Session acceptInvitation(String rawToken, String fullName, String password) {
        validateInvitation(rawToken);
        List<Map<String,Object>> rows = db.queryForList("select id,company_id,email,role_id from agent_invitations where token_hash=? and accepted_at is null and expires_at>now() for update", hash(rawToken));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.GONE, "Invitation expired or already accepted.");
        Map<String,Object> invite = rows.get(0);
        billing.assertInvitedAgentAcceptance((UUID)invite.get("company_id"));
        if (existsEmail(Objects.toString(invite.get("email")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        UUID userId = db.queryForObject("insert into app_users(company_id,role_id,email,full_name,password_hash,status) values(?,?,?,?,?,'ACTIVE') returning id", UUID.class,
                invite.get("company_id"), invite.get("role_id"), invite.get("email"), fullName.trim(), passwords.encode(password));
        db.update("update agent_invitations set accepted_at=now(),accepted_by=? where id=? and accepted_at is null", userId, invite.get("id"));
        audit((UUID) invite.get("company_id"), userId, "INVITATION_ACCEPTED", "INVITATION", invite.get("id").toString(), Map.of());
        return session(userId, UUID.randomUUID());
    }

    public List<Map<String,Object>> users() {
        UUID companyId = TenantContext.requireCompanyId();
        return db.queryForList("select u.id,u.email,u.full_name,u.phone,u.status,r.name role,u.created_at from app_users u join roles r on r.id=u.role_id where u.company_id=? order by u.created_at", companyId);
    }

    public Map<String,Object> userById(UUID id) {
        UUID companyId = TenantContext.requireCompanyId();
        List<Map<String,Object>> rows = db.queryForList("select u.id,u.email,u.full_name,u.phone,u.status,r.name role,u.created_at from app_users u join roles r on r.id=u.role_id where u.id=? and u.company_id=?", id, companyId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        return rows.get(0);
    }

    @Transactional
    public Map<String,Object> deleteUser(UUID id, UUID actorId) {
        if (id.equals(actorId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot remove your own account.");
        return updateUserStatus(id, "SUSPENDED", actorId);
    }

    @Transactional
    public Map<String,Object> updateUserRole(UUID targetId, String roleName, UUID actorId) {
        UUID companyId = TenantContext.requireCompanyId();
        if (!Set.of("AGENT", "COMPANY_ADMIN").contains(roleName)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Company administrators cannot assign this role.");
        if ("AGENT".equals(roleName)) {
            String current=db.query("select r.name from app_users u join roles r on r.id=u.role_id where u.id=? and u.company_id=?",rs->rs.next()?rs.getString(1):null,targetId,companyId);
            if(current==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found.");
            if(!"AGENT".equals(current))billing.assertAgentCapacity(companyId);
        }
        int count = db.update("update app_users set role_id=?,updated_at=now() where id=? and company_id=?", roleId(roleName), targetId, companyId);
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        audit(companyId, actorId, "USER_ROLE_CHANGED", "USER", targetId.toString(), Map.of("role", roleName));
        return Map.of("id", targetId, "role", roleName);
    }

    @Transactional
    public Map<String,Object> updateUserStatus(UUID targetId, String status, UUID actorId) {
        UUID companyId = TenantContext.requireCompanyId();
        if (!Set.of("ACTIVE", "SUSPENDED").contains(status)) throw new IllegalArgumentException("Status must be ACTIVE or SUSPENDED.");
        if (targetId.equals(actorId) && "SUSPENDED".equals(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot suspend your own account.");
        if ("ACTIVE".equals(status)) {
            Integer agent=db.queryForObject("select count(*) from app_users u join roles r on r.id=u.role_id where u.id=? and u.company_id=? and r.name='AGENT' and u.status<>'ACTIVE'",Integer.class,targetId,companyId);
            if(agent!=null&&agent>0) billing.assertAgentCapacity(companyId);
        }
        int count = db.update("update app_users set status=?,updated_at=now() where id=? and company_id=?", status, targetId, companyId);
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        if ("SUSPENDED".equals(status)) db.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null", targetId);
        audit(companyId, actorId, "USER_STATUS_CHANGED", "USER", targetId.toString(), Map.of("status", status));
        return Map.of("id", targetId, "status", status);
    }

    public Map<String,Object> companyProfile() {
        UUID companyId = TenantContext.requireCompanyId();
        return db.queryForMap("select id,name,slug,public_id,business_email,phone,website,created_at from companies where id=?", companyId);
    }

    @Transactional
    public Map<String,Object> updateCompanyProfile(Map<String,Object> body, UUID actorId) {
        UUID companyId = TenantContext.requireCompanyId();
        String name = Objects.toString(body.get("name"), "").trim();
        String email = normalizeEmail(Objects.toString(body.get("businessEmail"), ""));
        if (name.isBlank() || email.isBlank()) throw new IllegalArgumentException("Company name and business email are required.");
        db.update("update companies set name=?,business_email=?,phone=?,website=? where id=?", name, email,
                emptyToNull(Objects.toString(body.get("phone"), "")), emptyToNull(Objects.toString(body.get("website"), "")), companyId);
        audit(companyId, actorId, "COMPANY_PROFILE_UPDATED", "COMPANY", companyId.toString(), Map.of());
        return companyProfile();
    }

    public Map<String,Object> companySettings() {
        UUID companyId = TenantContext.requireCompanyId();
        Map<String,Object> row = db.queryForMap("select settings,updated_at from company_settings where company_id=?", companyId);
        Object value = row.get("settings");
        try { row.put("settings", value == null ? Map.of() : new com.fasterxml.jackson.databind.ObjectMapper().readValue(value.toString(), Map.class)); }
        catch (Exception ex) { throw new IllegalStateException("Company settings could not be read.", ex); }
        return row;
    }

    @Transactional
    public Map<String,Object> updateCompanySettings(Map<String,Object> settings, UUID actorId) {
        UUID companyId = TenantContext.requireCompanyId();
        String encoded;
        try { encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(settings); }
        catch (Exception ex) { throw new IllegalArgumentException("Settings must be valid JSON."); }
        db.update("update company_settings set settings=?::jsonb,updated_at=now() where company_id=?", encoded, companyId);
        audit(companyId, actorId, "COMPANY_SETTINGS_UPDATED", "COMPANY", companyId.toString(), Map.of());
        return companySettings();
    }

    private Session session(UUID userId, UUID familyId) {
        Map<String,Object> user = user(userId);
        String raw = token();
        db.update("insert into refresh_tokens(token_hash,family_id,user_id,expires_at) values(?,?,?,?)", hash(raw), familyId, userId, timestampAfter(Duration.ofDays(refreshDays)));
        return new Session(accessToken(user), raw, publicUser(user));
    }

    private String accessToken(Map<String,Object> user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("supportiq").audience(List.of("supportiq-api"))
                .subject(user.get("id").toString()).issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(accessMinutes)))
                .claim("role", user.get("role"));
        if (user.get("company_id") != null) claims.claim("companyId", user.get("company_id").toString());
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
    }

    private Map<String,Object> user(UUID id) {
        try { return db.queryForMap("select u.id,u.company_id,u.email,u.full_name,u.password_hash,u.status,r.name role,c.status company_status from app_users u join roles r on r.id=u.role_id left join companies c on c.id=u.company_id where u.id=?", id); }
        catch (org.springframework.dao.EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is unavailable."); }
    }

    private Map<String,Object> publicUser(Map<String,Object> row) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", row.get("id")); value.put("email", row.get("email")); value.put("fullName", row.get("full_name"));
        value.put("role", row.get("role")); value.put("status", row.get("status"));
        if (row.get("company_id") != null) {
            Map<String,Object> company = db.queryForMap("select name,slug from companies where id=?", row.get("company_id"));
            value.put("companyName", company.get("name"));
            value.put("companySlug", company.get("slug"));
        }
        return value;
    }

    private Integer roleId(String role) { return db.queryForObject("select id from roles where name=?", Integer.class, role); }
    private boolean existsEmail(String email) { return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from app_users where lower(email)=?)", Boolean.class, normalizeEmail(email))); }
    private void audit(UUID companyId, UUID actorId, String action, String type, String targetId, Map<String,Object> details) {
        try { db.update("insert into audit_logs(company_id,actor_id,action,target_type,target_id,details) values(?,?,?,?,?,?::jsonb)", companyId, actorId, action, type, targetId, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details)); }
        catch (Exception ex) { throw new IllegalStateException("Could not write audit record.", ex); }
    }

    private void enforceRateLimit(String key, int maximum, int seconds) {
        String digest = hash(key);
        Integer attempts = db.queryForObject("insert into auth_rate_limits(key_hash,window_started,attempts) values(?,now(),1) " +
                "on conflict(key_hash) do update set window_started=case when auth_rate_limits.window_started < now() - make_interval(secs => ?::double precision) then now() else auth_rate_limits.window_started end, " +
                "attempts=case when auth_rate_limits.window_started < now() - make_interval(secs => ?::double precision) then 1 else auth_rate_limits.attempts+1 end returning attempts", Integer.class, digest, seconds, seconds);
        if (attempts != null && attempts > maximum) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please try again later.");
    }

    private String normalizeEmail(String email) { return Objects.toString(email, "").trim().toLowerCase(Locale.ROOT); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String slug(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "company" : normalized.substring(0, Math.min(80, normalized.length()));
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private java.sql.Timestamp timestampAfter(Duration duration) { return java.sql.Timestamp.from(Instant.now().plus(duration)); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public record Session(String accessToken, String refreshToken, Map<String,Object> user) {}
}
