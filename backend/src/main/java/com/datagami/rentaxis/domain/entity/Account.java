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

    @Column(name = "parent_code", length = 20)
    private String parentCode;

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

    @Column(name = "hierarchy_level")
    private int hierarchyLevel = 1;

    @Column(name = "is_group")
    private boolean isGroup = false;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "display_order")
    private int displayOrder = 0;
}
