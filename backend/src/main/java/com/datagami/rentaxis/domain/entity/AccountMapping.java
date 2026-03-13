package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "account_mappings")
@Getter
@Setter
public class AccountMapping extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_nature", length = 50, nullable = false)
    private TransactionNature transactionNature;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "debit_account_id", nullable = false)
    private Account debitAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "credit_account_id", nullable = false)
    private Account creditAccount;
}
