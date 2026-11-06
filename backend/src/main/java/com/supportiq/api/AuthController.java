package com.supportiq.api;

import com.supportiq.service.AuthService;
import com.supportiq.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String COOKIE = "supportiq_refresh";
    private final AuthService auth;
    @Value("${app.security.cookie-secure:false}") boolean secureCookie;
    @Value("${app.security.refresh-days:14}") long refreshDays;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register-company")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterCompany request) {
        AuthService.Session session = auth.registerCompany(request.companyName(), request.businessEmail(), request.fullName(), request.password(), request.phone(), request.website());
        return session(session, "Company workspace created.");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Login request, HttpServletRequest servletRequest) {
        return session(auth.login(request.email(), request.password(), servletRequest.getRemoteAddr()), "Signed in.");
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name=COOKIE, required=false) String refreshToken) {
        return session(auth.refresh(refreshToken), "Session renewed.");
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@CookieValue(name=COOKIE, required=false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @GetMapping("/me")
    public Map<String,Object> me() { return auth.me(TenantContext.userIdOrNull()); }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePassword request) {
        auth.changePassword(TenantContext.userIdOrNull(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public Map<String,Object> forgotPassword(@Valid @RequestBody ForgotPassword request, HttpServletRequest servletRequest) {
        return auth.forgotPassword(request.email(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPassword request) {
        auth.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated. Sign in with your new password."));
    }

    private ResponseEntity<Map<String,Object>> session(AuthService.Session session, String message) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, session.refreshToken())
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/api/auth")
                .maxAge(Duration.ofDays(refreshDays)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("accessToken", session.accessToken(), "user", session.user(), "message", message));
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(secureCookie).sameSite("Strict").path("/api/auth").maxAge(Duration.ZERO).build();
    }

    public record RegisterCompany(
            @NotBlank @Size(max=160) String companyName,
            @NotBlank @Email @Size(max=320) String businessEmail,
            @NotBlank @Size(max=160) String fullName,
            @NotBlank @Size(min=12,max=100) String password,
            @NotBlank String confirmPassword,
            @Size(max=40) String phone,
            @Size(max=255) String website) {
        @jakarta.validation.constraints.AssertTrue(message="Password confirmation does not match.")
        public boolean isPasswordConfirmed() { return password != null && password.equals(confirmPassword); }
    }

    public record Login(@NotBlank @Email @Size(max=320) String email, @NotBlank @Size(max=100) String password) {}
    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min=12,max=100) String newPassword, @NotBlank String confirmPassword) {
        @jakarta.validation.constraints.AssertTrue(message="Password confirmation does not match.")
        public boolean isPasswordConfirmed() { return newPassword != null && newPassword.equals(confirmPassword); }
    }
    public record ForgotPassword(@NotBlank @Email @Size(max=320) String email) {}
    public record ResetPassword(@NotBlank String token, @NotBlank @Size(min=12,max=100) String newPassword, @NotBlank String confirmPassword) {
        @jakarta.validation.constraints.AssertTrue(message="Password confirmation does not match.")
        public boolean isPasswordConfirmed() { return newPassword != null && newPassword.equals(confirmPassword); }
    }
}
