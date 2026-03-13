package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
public class Account extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "parent_code", length = 20)
    private String parentCode;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "is_system")
    private boolean isSystem = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_sub_type", length = 30)
    private AccountSubType accountSubType;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "hierarchy_level")
    private int hierarchyLevel = 1;

    @Column(name = "is_group")
    private boolean isGroup = false;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "display_order")
    private int displayOrder = 0;
}
