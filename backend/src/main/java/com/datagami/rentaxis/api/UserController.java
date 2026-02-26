package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record CreateUserRequest(String email, String password, String name, UserRole role, String tenantId,
            List<UUID> propertyIds) {
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest request) {
        User user = userService.createUser(
                request.email(),
                request.password(),
                request.name(),
                request.role(),
                request.tenantId());

        // If creating a PROPERTY_MANAGER, assign properties
        if (request.role() == UserRole.PROPERTY_MANAGER && request.propertyIds() != null) {
            for (UUID propertyId : request.propertyIds()) {
                userService.assignPropertyToUser(user.getId(), propertyId);
            }
        }

        return ResponseEntity.ok(user);
    }

    @PostMapping("/{userId}/properties/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Void> assignProperty(@PathVariable UUID userId, @PathVariable UUID propertyId) {
        userService.assignPropertyToUser(userId, propertyId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}/properties/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Void> removePropertyAssignment(@PathVariable UUID userId, @PathVariable UUID propertyId) {
        userService.removePropertyFromUser(userId, propertyId);
        return ResponseEntity.ok().build();
    }
}
