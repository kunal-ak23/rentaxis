package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.StaffRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StaffService {

    private final StaffRepository repository;
    private final TenantReferences refs;
    private final UserRepository userRepository;
    private final UserService userService;

    /** Logins a staff record may be linked to: the roles a ticket can be assigned to. */
    private static final java.util.Set<UserRole> LINKABLE_ROLES =
            java.util.EnumSet.of(UserRole.TENANT_USER, UserRole.PROPERTY_MANAGER, UserRole.TENANT_ADMIN);

    @Transactional(readOnly = true)
    public List<Staff> getAllStaff() {
        return repository.findAllByOrderByNameEnAsc();
    }

    @Transactional(readOnly = true)
    public List<Staff> getByProperty(UUID propertyId) {
        return repository.findByPropertyId(propertyId);
    }

    @Transactional(readOnly = true)
    public Staff getStaffById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Staff not found"));
    }

    /**
     * Creates a staff member from a request body. The property and salary account
     * arrive as ids and are resolved inside this transaction and the caller's
     * tenant: another tenant's or a missing row is a 404, a salary account that is
     * not an active expense leaf is a 400, and nothing is saved either way.
     */
    @Transactional
    public Staff createStaff(StaffRequest request) {
        Staff staff = new Staff();
        apply(staff, request);
        // A new record is active unless the form says otherwise.
        if (request.active() == null) staff.setActive(true);
        return repository.save(staff);
    }

    /** Replaces every field, as the entity-bound endpoint did; see {@link StaffRequest}. */
    @Transactional
    public Staff updateStaff(UUID id, StaffRequest request) {
        Staff existing = getStaffById(id);
        apply(existing, request);
        return repository.save(existing);
    }

    private void apply(Staff s, StaffRequest r) {
        if (r.nameEn() == null || r.nameEn().isBlank()) {
            throw new BusinessRuleViolationException("Name (English) is required");
        }
        // Resolved first, so a refused reference leaves the managed row untouched.
        var property = refs.propertyOrNull(r.property());
        var salaryAccount = refs.salaryAccountOrNull(r.salaryAccount(), s.getSalaryAccount());
        s.setNameEn(r.nameEn().trim());
        s.setNameAr(r.nameAr());
        s.setEmployeeId(r.employeeId());
        s.setDesignation(r.designation());
        s.setDepartment(r.department());
        // Absent was ZERO when the entity was bound (its field default); keep that.
        s.setMonthlySalary(r.monthlySalary() == null ? java.math.BigDecimal.ZERO : r.monthlySalary());
        s.setJoinDate(r.joinDate());
        s.setPhone(r.phone());
        s.setEmiratesId(r.emiratesId());
        s.setPassportNumber(r.passportNumber());
        s.setProperty(property);
        s.setSalaryAccount(salaryAccount);
        // Tutorial 22: an edit that did not carry the flag silently re-activated an
        // inactive staff member. Absent now keeps the stored value.
        if (r.active() != null) s.setActive(r.active());
    }

    /**
     * "Give login" (tutorial 22): staff records are HR rows and cannot be assigned
     * tickets, because the assignee has to see and work the ticket in the app. This
     * links the record to a login — an existing staff login in this organisation with
     * that email, or a new Staff user (TENANT_USER) invited by email to set a
     * password. The staff member then appears under "Assign to", with their
     * designation.
     *
     * @throws BusinessRuleViolationException (400) when the record already has a
     *         login, the email is missing, or it belongs to someone who cannot be
     *         assigned tickets or is already linked to another staff record
     */
    @Transactional
    public Staff giveLogin(UUID staffId, String email) {
        Staff staff = getStaffById(staffId);
        if (staff.getUserId() != null) {
            throw new BusinessRuleViolationException("This staff member already has a login");
        }
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (normalized.isEmpty() || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessRuleViolationException("Enter a valid email address");
        }
        UUID tenantId = staff.getTenantId() != null ? staff.getTenantId() : TenantContextHolder.getTenantId();
        User login = userRepository.findByTenantIdAndEmail(tenantId, normalized).orElse(null);
        if (login != null) {
            if (!LINKABLE_ROLES.contains(login.getRole()) || login.getStatus() != UserStatus.ACTIVE) {
                throw new BusinessRuleViolationException(
                        "That email belongs to a user who cannot be assigned tickets");
            }
            if (repository.existsByUserId(login.getId())) {
                throw new BusinessRuleViolationException("That login is already linked to another staff member");
            }
        } else {
            login = userService.createUser(normalized, null, staff.getNameEn(), UserRole.TENANT_USER,
                    tenantId.toString(), null, "staff");
        }
        staff.setUserId(login.getId());
        return repository.save(staff);
    }

    @Transactional
    public void deleteStaff(UUID id) {
        repository.deleteById(id);
    }
}
