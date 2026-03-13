package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
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
    private final FinancialTransactionRepository transactionRepository;

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
                .orElseThrow(() -> new RuntimeException("Staff not found"));
    }

    @Transactional
    public Staff createStaff(Staff staff) {
        return repository.save(staff);
    }

    @Transactional
    public Staff updateStaff(UUID id, Staff updates) {
        Staff existing = getStaffById(id);
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setEmployeeId(updates.getEmployeeId());
        existing.setDesignation(updates.getDesignation());
        existing.setDepartment(updates.getDepartment());
        existing.setMonthlySalary(updates.getMonthlySalary());
        existing.setJoinDate(updates.getJoinDate());
        existing.setPhone(updates.getPhone());
        existing.setEmiratesId(updates.getEmiratesId());
        existing.setPassportNumber(updates.getPassportNumber());
        existing.setProperty(updates.getProperty());
        existing.setSalaryAccount(updates.getSalaryAccount());
        existing.setActive(updates.isActive());
        return repository.save(existing);
    }

    @Transactional
    public void deleteStaff(UUID id) {
        if (!transactionRepository.findByStaffId(id).isEmpty()) {
            throw new IllegalStateException("Cannot delete staff member with existing transactions");
        }
        repository.deleteById(id);
    }
}
