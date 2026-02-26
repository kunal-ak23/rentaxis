package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "financial_transactions")
@Getter
@Setter
public class FinancialTransaction extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "property_id")
    private Property property;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @Column(name = "vat_applicable")
    private boolean vatApplicable = false;

    @Column(name = "vat_amount", precision = 14, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(columnDefinition = "text")
    private String notes;

    /**
     * Auto-populate denormalized fields from the linked Account entity,
     * and auto-resolve property from unit if not explicitly set.
     */
    @PrePersist
    @PreUpdate
    public void onPrePersistFinancial() {
        if (account != null) {
            this.accountCode = account.getCode();
            this.accountType = account.getAccountType();
        }
        if (unit != null && property == null) {
            this.property = unit.getProperty();
        }
    }
}
