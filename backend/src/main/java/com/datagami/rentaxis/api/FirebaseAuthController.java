package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.service.auth.FirebaseGuardAuthService;
import com.datagami.rentaxis.domain.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Public mobile-only Firebase exchange endpoint. */
@RestController
@RequestMapping("/api/v1/auth")
public class FirebaseAuthController {

    private final UserService userService;
    private final FirebaseGuardAuthService firebaseGuardAuthService;

    public FirebaseAuthController(UserService userService,
            FirebaseGuardAuthService firebaseGuardAuthService) {
        this.userService = userService;
        this.firebaseGuardAuthService = firebaseGuardAuthService;
    }

    public record FirebaseLoginRequest(String idToken) {
    }

    @PostMapping("/firebase")
    public ResponseEntity<AuthController.AuthResponse> firebaseLogin(
            @RequestBody FirebaseLoginRequest request) {
        User guard = firebaseGuardAuthService.authenticate(request.idToken());
        List<String> tenantIds = userService.getUserTenantIds(guard.getId())
                .stream().map(UUID::toString).toList();
        return ResponseEntity.ok(new AuthController.AuthResponse(
                guard.getId().toString(),
                guard.getEmail(),
                guard.getName(),
                guard.getRole().name(),
                guard.getTenantId() != null ? guard.getTenantId().toString() : null,
                tenantIds));
    }
}
