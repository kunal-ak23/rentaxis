package com.datagami.rentaxis.domain.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
public class BankAccount extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "account_number", length = 50, nullable = false)
    private String accountNumber;

    @Column(length = 34)
    private String iban;

    @Column(name = "branch_name")
    private String branchName;

    @Column(length = 10)
    private String currency = "AED";

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "property_id")
    private Property property;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "coa_account_id")
    private Account coaAccount;

    @Column(name = "is_default")
    private boolean isDefault = false;

    @Column(name = "is_active")
    private boolean isActive = true;

    /**
     * Explicit accessors so Jackson names the JSON property {@code isDefault}
     * instead of the {@code default} it would derive from the Lombok-generated
     * {@code isDefault()}/{@code setDefault()} pair. Both the web dashboard and
     * the manager app read and send {@code isDefault}; without this annotation
     * the flag never round-trips (and a PUT silently cleared it).
     */
    @JsonProperty("isDefault")
    public boolean isDefault() {
        return isDefault;
    }

    @JsonProperty("isDefault")
    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}
