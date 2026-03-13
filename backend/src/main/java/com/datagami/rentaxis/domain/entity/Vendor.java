package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "vendors")
@Getter
@Setter
public class Vendor extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "trade_license_number", length = 50)
    private String tradeLicenseNumber;

    @Column(name = "trn", length = 20)
    private String trn;

    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "contact_person")
    private String contactPerson;

    @Column(columnDefinition = "text")
    private String address;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(length = 34)
    private String iban;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(columnDefinition = "text")
    private String notes;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "payable_account_id")
    private Account payableAccount;
}
