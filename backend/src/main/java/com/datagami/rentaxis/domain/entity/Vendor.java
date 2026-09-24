package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "vendors")
@Getter
@Setter
public class Vendor extends BaseTenantEntity {

    // Serialized in responses, never accepted from a request body.
    //
    // These six endpoints bind the JPA entity directly as the request DTO, so
    // every settable property was client-writable. A POST carrying an id made
    // Hibernate treat repository.save() as an update to that row rather than an
    // insert, turning "create" into "silently overwrite something else in my
    // tenant". READ_ONLY closes that without changing any response shape.
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @NotBlank(message = "Vendor name (English) is required")
    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "trade_license_number", length = 50)
    private String tradeLicenseNumber;

    /**
     * UAE Tax Registration Number: 15 digits when present (finance-ops spec §2),
     * checked by {@code VendorService.normaliseTrn}. A PISR carrying input VAT
     * needs it — VoucherService refuses one without.
     */
    @Column(name = "trn", length = 20)
    private String trn;

    /**
     * Days from the supplier's invoice date to its due date; a PISR's due date
     * defaults from it. Boxed so an update that leaves it out keeps the stored
     * value rather than resetting it to 30 (VendorService).
     */
    @jakarta.validation.constraints.Min(value = 0, message = "Payment terms cannot be negative")
    @jakarta.validation.constraints.Max(value = 365, message = "Payment terms are at most 365 days")
    @Column(name = "payment_terms_days", nullable = false)
    private Integer paymentTermsDays;

    /** 30 days unless the vendor was created with terms of its own. */
    @PrePersist
    void defaultPaymentTerms() {
        if (paymentTermsDays == null) paymentTermsDays = 30;
    }

    @Email(message = "Email must be a valid address")
    @Column(length = 100)
    private String email;

    @Size(max = 20, message = "Phone must be 20 characters or fewer")
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

    // Serialized in responses, never accepted from a request body (PR #340 review
    // I1). The server creates this leaf when the vendor is created and keeps it
    // for life. Bound from JSON it arrived as a transient Account (Account.id is
    // READ_ONLY): a POST carrying one 500'd at flush, and a PUT carrying one
    // inserted a client-shaped orphan account and moved the vendor's payable onto
    // it. No client sends it on purpose; the web vendors page never has.
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "payable_account_id")
    private Account payableAccount;
}
