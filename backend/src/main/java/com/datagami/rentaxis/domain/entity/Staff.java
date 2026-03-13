package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "staff")
@Getter
@Setter
public class Staff extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "employee_id", length = 20)
    private String employeeId;

    @Column(length = 100)
    private String designation;

    @Column(length = 100)
    private String department;

    @Column(name = "monthly_salary", precision = 14, scale = 2)
    private BigDecimal monthlySalary = BigDecimal.ZERO;

    @Column(name = "join_date")
    private LocalDate joinDate;

    @Column(length = 20)
    private String phone;

    @Column(name = "emirates_id", length = 20)
    private String emiratesId;

    @Column(name = "passport_number", length = 20)
    private String passportNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "property_id")
    private Property property;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "salary_account_id")
    private Account salaryAccount;

    @Column(name = "is_active")
    private boolean isActive = true;
}
