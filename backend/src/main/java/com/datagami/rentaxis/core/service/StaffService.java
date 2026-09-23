package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.StaffRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.repository.StaffRepository;
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
        s.setActive(r.active() == null || r.active());
    }

    @Transactional
    public void deleteStaff(UUID id) {
        repository.deleteById(id);
    }
}
