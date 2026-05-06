package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.PasswordChangedPayload;
import com.datagami.rentaxis.core.email.event.payload.StaffRoleChangedPayload;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.email.event.payload.UserWelcomedPayload;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.UserTenantMembership;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserPropertyAssignmentRepository propertyAssignmentRepository;
    private final UserTenantMembershipRepository tenantMembershipRepository;
    private final ApplicationEventPublisher events;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            UserPropertyAssignmentRepository propertyAssignmentRepository,
            UserTenantMembershipRepository tenantMembershipRepository,
            ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.propertyAssignmentRepository = propertyAssignmentRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.events = events;
    }

    @Transactional
    public User createUser(String email, String rawPassword, String name, UserRole role, String tenantId,
            String phoneNumber) {
        String normalizedEmail = email.toLowerCase().trim();
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("User with this email already exists.");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setName(name);
        user.setRole(role);
        user.setPhoneNumber(phoneNumber);
        user.setTenantId(tenantId != null && !tenantId.isBlank() ? UUID.fromString(tenantId) : null);

        User saved = userRepository.save(user);

        // Auto-create tenant membership for tenant-scoped roles
        if (tenantId != null && !tenantId.isBlank()
                && (role == UserRole.TENANT_ADMIN || role == UserRole.PROPERTY_MANAGER
                        || role == UserRole.TENANT_USER || role == UserRole.RENTER)) {
            addTenantMembership(saved.getId(), UUID.fromString(tenantId));
        }

        // Emit USER_INVITED for staff users (PROPERTY_MANAGER, TENANT_USER) created by an admin
        if (role == UserRole.PROPERTY_MANAGER || role == UserRole.TENANT_USER) {
            // TODO: switch to tokenized invite URL once onboarding token flow is designed (see Task 26 follow-up)
            String setPasswordUrl = "/set-password?userId=" + saved.getId();
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.USER_INVITED,
                    saved.getTenantId(),
                    new UserInvitedPayload(saved.getId(), saved.getName(), setPasswordUrl),
                    "USER_INVITED:" + saved.getId()));
        }

        return saved;
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User saveUser(User user) {
        return userRepository.save(user);
    }

    /**
     * Marks a user as welcomed (sets welcomedAt) and publishes USER_WELCOMED within
     * a single transaction so the entity write and event publish share the same
     * commit boundary (TransactionalEventListener fires on commit).
     */
    @Transactional
    public void markWelcomed(User user) {
        user.setWelcomedAt(Instant.now());
        userRepository.save(user);
        events.publishEvent(new EmailEvent(this,
                EmailEventType.USER_WELCOMED,
                user.getTenantId(),
                new UserWelcomedPayload(user.getId(), user.getName(), "/dashboard"),
                "USER_WELCOMED:" + user.getId()));
    }

    /**
     * Encodes and persists the new password, then publishes PASSWORD_CHANGED within
     * a single transaction so the entity write and event publish share the same
     * commit boundary (TransactionalEventListener fires on commit).
     */
    @Transactional
    public void changePassword(User user, String newRawPassword) {
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        userRepository.save(user);
        events.publishEvent(new EmailEvent(this,
                EmailEventType.PASSWORD_CHANGED,
                user.getTenantId(),
                new PasswordChangedPayload(user.getId(), user.getName(),
                        Instant.now().toString(), null),
                "PASSWORD_CHANGED:" + user.getId()));
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public List<User> getUsersByTenantId(UUID tenantId) {
        return userRepository.findByTenantId(tenantId);
    }

    @Transactional
    public User updateUser(UUID id, String email, String rawPassword, String name, UserRole role, String tenantId,
            String phoneNumber) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));

        String normalizedEmail = email.toLowerCase().trim();
        if (!user.getEmail().equals(normalizedEmail) && userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("User with this email already exists.");
        }

        UserRole previousRole = user.getRole();
        user.setEmail(normalizedEmail);
        user.setName(name);
        user.setRole(role);
        user.setPhoneNumber(phoneNumber);

        UUID newTenantId = (tenantId != null && !tenantId.isBlank()) ? UUID.fromString(tenantId) : null;
        user.setTenantId(newTenantId);

        if (rawPassword != null && !rawPassword.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
        }

        User saved = userRepository.save(user);

        // Auto-create tenant membership if new tenantId is provided
        if (newTenantId != null && (role == UserRole.TENANT_ADMIN || role == UserRole.PROPERTY_MANAGER
                || role == UserRole.TENANT_USER || role == UserRole.RENTER)) {
            addTenantMembership(saved.getId(), newTenantId);
        }

        // Emit STAFF_ROLE_CHANGED when role transitions to a different value
        if (previousRole != role) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.STAFF_ROLE_CHANGED,
                    saved.getTenantId(),
                    new StaffRoleChangedPayload(saved.getTenantId(), saved.getId(), saved.getName(),
                            previousRole.name(), role.name()),
                    "STAFF_ROLE_CHANGED:" + saved.getId()));
        }

        return saved;
    }

    @Transactional
    public void deleteUser(UUID id) {
        tenantMembershipRepository.deleteByUserId(id);
        propertyAssignmentRepository.deleteByUserId(id);
        userRepository.deleteById(id);
    }

    // --- Property Assignment Methods ---

    @Transactional
    public void assignPropertyToUser(UUID userId, UUID propertyId) {
        if (propertyAssignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            return; // Already assigned
        }
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(userId);
        assignment.setPropertyId(propertyId);
        propertyAssignmentRepository.save(assignment);
    }

    @Transactional
    public void removePropertyFromUser(UUID userId, UUID propertyId) {
        propertyAssignmentRepository.deleteByUserIdAndPropertyId(userId, propertyId);
    }

    public List<UUID> getAssignedPropertyIds(UUID userId) {
        return propertyAssignmentRepository.findByUserId(userId)
                .stream()
                .map(UserPropertyAssignment::getPropertyId)
                .toList();
    }

    // --- Tenant Membership Methods ---

    @Transactional
    public void addTenantMembership(UUID userId, UUID tenantId) {
        if (tenantMembershipRepository.existsByUserIdAndTenantId(userId, tenantId)) {
            return;
        }
        UserTenantMembership membership = new UserTenantMembership();
        membership.setUserId(userId);
        membership.setTenantId(tenantId);
        tenantMembershipRepository.save(membership);
    }

    @Transactional
    public void removeTenantMembership(UUID userId, UUID tenantId) {
        tenantMembershipRepository.deleteByUserIdAndTenantId(userId, tenantId);
    }

    public List<UUID> getUserTenantIds(UUID userId) {
        return tenantMembershipRepository.findByUserId(userId)
                .stream()
                .map(UserTenantMembership::getTenantId)
                .toList();
    }

    public List<User> getAssignedManagers(UUID propertyId) {
        List<UUID> userIds = propertyAssignmentRepository.findByPropertyId(propertyId)
                .stream()
                .map(UserPropertyAssignment::getUserId)
                .toList();
        return userRepository.findAllById(userIds);
    }
}
