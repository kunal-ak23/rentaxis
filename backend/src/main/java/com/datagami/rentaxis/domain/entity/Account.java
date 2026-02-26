package com.datagami.rentaxis.domain.entity;

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
}
