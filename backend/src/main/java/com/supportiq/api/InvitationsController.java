package com.supportiq.api;

import com.supportiq.security.TenantContext;
import com.supportiq.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/invitations")
public class InvitationsController {
    private final AuthService auth;
    @Value("${app.security.cookie-secure:false}") boolean secureCookie;
    @Value("${app.security.refresh-days:14}") long refreshDays;
    public InvitationsController(AuthService auth) { this.auth = auth; }

    @PostMapping @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> invite(@Valid @RequestBody Invite body) { return auth.inviteAgent(body.email(), TenantContext.userIdOrNull()); }

    @GetMapping("/validate") public Map<String,Object> validate(@RequestParam String token) { return auth.validateInvitation(token); }

    @PostMapping("/accept")
    public ResponseEntity<?> accept(@Valid @RequestBody Accept body) {
        AuthService.Session session = auth.acceptInvitation(body.token(), body.fullName(), body.password());
        ResponseCookie cookie = ResponseCookie.from("supportiq_refresh", session.refreshToken()).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/api/auth").maxAge(Duration.ofDays(refreshDays)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("accessToken", session.accessToken(), "user", session.user(), "message", "Invitation accepted."));
    }

    public record Invite(@NotBlank @Email @Size(max=320) String email) {}
    public record Accept(@NotBlank String token, @NotBlank @Size(max=160) String fullName, @NotBlank @Size(min=12,max=100) String password) {}
}
