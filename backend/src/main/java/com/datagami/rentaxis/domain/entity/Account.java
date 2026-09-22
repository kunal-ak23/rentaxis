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

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    /**
     * The tree is by id, not by code: {@code parent_code} was a string with no
     * foreign key, so a typo or a renamed code silently orphaned a whole branch
     * and nothing in the database noticed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Account parent;

    /** Serialized as parentId for the web; never bound from a request body (the controller takes parentId on its request record). */
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public UUID getParentId() {
        return parent == null ? null : parent.getId();
    }

    /** Set on leaves that belong to exactly one building, so a property's ledger can be sliced out. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Property property;

    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public UUID getPropertyId() {
        return property == null ? null : property.getId();
    }

    /** Short label the accountant recognises the account by, independent of its name. */
    @Column(length = 255)
    private String alias;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "is_system")
    // Jackson sees TWO properties here, which is the trap: the field's implicit
    // name is "isSystem", while Lombok's isSystem()/setSystem() give the
    // accessors the implicit name "system". Blocking one leaves the other
    // writable. This ignores the field-named one; READ_ONLY on the explicit
    // getter below handles the accessor-named one while keeping it in
    // responses, which the web accounts page depends on.
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean isSystem = false;

    /**
     * Server-owned: only {@code seedDefaultAccounts} sets it, in code.
     *
     * <p>The annotation sits on the getter, and getting there took three tries
     * worth recording. On the field it did nothing: Lombok generates
     * {@code isSystem()}/{@code setSystem()}, from which Jackson derives the
     * property name "system", while the field's implicit name is "isSystem" —
     * two separate logical properties, so the setter-backed one stayed fully
     * writable. Moving it to the setter as {@code @JsonIgnore} then removed the
     * property from responses altogether, which would have broken the web
     * accounts page that reads {@code account.system} to decide whether to
     * offer edit and delete.</p>
     *
     * <p>READ_ONLY on the getter is the combination that serializes but does
     * not bind. It matters because isSystem gates "system accounts cannot be
     * modified" and "cannot be deleted": a client able to set it on create
     * would own a row nothing in the API can subsequently touch.</p>
     */
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean isSystem() {
        return isSystem;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "account_sub_type", length = 30)
    private AccountSubType accountSubType;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "is_group")
    private boolean isGroup = false;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "display_order")
    private int displayOrder = 0;
}
