package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.UserTenantMembership;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserPropertyAssignmentRepository propertyAssignmentRepository;
    private final UserTenantMembershipRepository tenantMembershipRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            UserPropertyAssignmentRepository propertyAssignmentRepository,
            UserTenantMembershipRepository tenantMembershipRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.propertyAssignmentRepository = propertyAssignmentRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
    }

    @Transactional
    public User createUser(String email, String rawPassword, String name, UserRole role, String tenantId,
            String phoneNumber) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("User with this email already exists.");
        }

        User user = new User();
        user.setEmail(email);
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

        return saved;
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
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

        if (!user.getEmail().equals(email) && userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("User with this email already exists.");
        }

        user.setEmail(email);
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
