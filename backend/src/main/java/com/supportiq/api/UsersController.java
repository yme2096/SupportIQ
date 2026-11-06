package com.supportiq.api;

import com.supportiq.security.TenantContext;
import com.supportiq.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class UsersController {
    private final AuthService auth;
    public UsersController(AuthService auth) { this.auth = auth; }

    @GetMapping public List<Map<String,Object>> users() { return auth.users(); }
    @GetMapping("/{id}") public Map<String,Object> user(@PathVariable UUID id) { return auth.userById(id); }
    @PutMapping("/{id}/role") public Map<String,Object> role(@PathVariable UUID id, @Valid @RequestBody RoleChange body) { return auth.updateUserRole(id, body.role(), TenantContext.userIdOrNull()); }
    @PutMapping("/{id}/status") public Map<String,Object> status(@PathVariable UUID id, @Valid @RequestBody StatusChange body) { return auth.updateUserStatus(id, body.status(), TenantContext.userIdOrNull()); }
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable UUID id) { return auth.deleteUser(id, TenantContext.userIdOrNull()); }

    public record RoleChange(@NotBlank String role) {}
    public record StatusChange(@NotBlank String status) {}
}
