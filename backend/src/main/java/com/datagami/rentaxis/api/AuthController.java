package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final LandlordOrgService orgService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, LandlordOrgService orgService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.orgService = orgService;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginRequest(String email, String password) {
    }

    public record RegisterRequest(String fullName, String companyName, String email, String password) {
    }

    public record AuthResponse(String id, String email, String name, String role, String tenantId) {
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        Optional<User> userOpt = userService.findByEmail(request.email());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                return ResponseEntity.ok(new AuthResponse(
                        user.getId().toString(),
                        user.getEmail(),
                        user.getName(),
                        user.getRole().name(),
                        user.getTenantId() != null ? user.getTenantId().toString() : null));
            }
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        // 1. Provision New Organization
        LandlordOrg org = orgService.provisionTenant(request.companyName());

        // 2. Create the first user as TENANT_ADMIN for this new organization
        User user = userService.createUser(
                request.email(),
                request.password(),
                request.fullName(),
                UserRole.TENANT_ADMIN,
                org.getId().toString());

        return ResponseEntity.ok(new AuthResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getTenantId() != null ? user.getTenantId().toString() : null));
    }
}
