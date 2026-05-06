package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.UserResponseDTO;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.TenantAdminAddedPayload;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    public UserController(UserService userService, ApplicationEventPublisher events) {
        this.userService = userService;
        this.events = events;
    }

    public record CreateUserRequest(String email, String password, String name, UserRole role, String tenantId,
            String phoneNumber, List<UUID> propertyIds) {
    }

    @GetMapping
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers().stream()
                .map(UserResponseDTO::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<UserResponseDTO> createUser(@RequestBody CreateUserRequest request) {
        User user = userService.createUser(
                request.email(),
                request.password(),
                request.name(),
                request.role(),
                request.tenantId(),
                request.phoneNumber());

        // If creating a PROPERTY_MANAGER, assign properties
        if (request.role() == UserRole.PROPERTY_MANAGER && request.propertyIds() != null) {
            for (UUID propertyId : request.propertyIds()) {
                userService.assignPropertyToUser(user.getId(), propertyId);
            }
        }

        // TENANT_ADMIN_ADDED: when an admin adds another TENANT_ADMIN to an existing tenant
        if (request.role() == UserRole.TENANT_ADMIN && request.tenantId() != null) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.TENANT_ADMIN_ADDED,
                    user.getTenantId(),
                    new TenantAdminAddedPayload(user.getTenantId(), user.getId(), user.getName(), "admin"),
                    "TENANT_ADMIN_ADDED:" + user.getId()));
        }

        return ResponseEntity.ok(UserResponseDTO.from(user));
    }

    public record UpdateUserRequest(String email, String password, String name, UserRole role, String tenantId,
            String phoneNumber, List<UUID> propertyIds) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDTO> updateUser(@PathVariable UUID id, @RequestBody UpdateUserRequest request) {
        User user = userService.updateUser(
                id,
                request.email(),
                request.password(),
                request.name(),
                request.role(),
                request.tenantId(),
                request.phoneNumber());

        if (request.role() == UserRole.PROPERTY_MANAGER && request.propertyIds() != null) {
            List<UUID> existingIds = userService.getAssignedPropertyIds(id);
            for (UUID existingId : existingIds) {
                if (!request.propertyIds().contains(existingId)) {
                    userService.removePropertyFromUser(id, existingId);
                }
            }
            for (UUID newId : request.propertyIds()) {
                if (!existingIds.contains(newId)) {
                    userService.assignPropertyToUser(id, newId);
                }
            }
        }

        return ResponseEntity.ok(UserResponseDTO.from(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok().build();
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

    @GetMapping("/{userId}/properties")
    public ResponseEntity<List<UUID>> getUserProperties(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.getAssignedPropertyIds(userId));
    }
}
