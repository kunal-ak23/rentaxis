package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "org_settings")
public class OrgSettings extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "landlord_org_id", insertable = false, updatable = false)
    private LandlordOrg landlordOrg;

    /**
     * The same column as {@link #landlordOrg}, writable.
     *
     * <p>{@code landlord_org_id} is NOT NULL (changeset 01) and the association
     * above is {@code insertable = false}, so nothing in this entity could supply
     * it: {@code repo.save(new OrgSettings())} INSERTed a null and came back a 409.
     * That is why the PUT below could only ever edit a row somebody else had
     * created — the create branch it appeared to have never worked. Mapping the
     * FK as a plain value alongside the read-only association is the standard way
     * to have both, and it is a column write, not a schema change: nothing is
     * added to the table, so no changeset is needed in a hotfix.</p>
     */
    @Column(name = "landlord_org_id", nullable = false)
    private UUID landlordOrgId;

    @Column(name = "default_currency")
    private String defaultCurrency = "AED";

    @Column(name = "default_locale")
    private String defaultLocale = "en";

    @Column(name = "timezone")
    private String timezone = "Asia/Dubai";

    @Column(name = "reminder_days")
    private Integer reminderDays = 30;

    @Column(name = "penalty_payment_instructions", columnDefinition = "TEXT")
    private String penaltyPaymentInstructions;

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public LandlordOrg getLandlordOrg() {
        return landlordOrg;
    }

    public void setLandlordOrg(LandlordOrg landlordOrg) {
        this.landlordOrg = landlordOrg;
    }

    public UUID getLandlordOrgId() {
        return landlordOrgId;
    }

    public void setLandlordOrgId(UUID landlordOrgId) {
        this.landlordOrgId = landlordOrgId;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Integer getReminderDays() {
        return reminderDays;
    }

    public void setReminderDays(Integer reminderDays) {
        this.reminderDays = reminderDays;
    }

    public String getPenaltyPaymentInstructions() {
        return penaltyPaymentInstructions;
    }

    public void setPenaltyPaymentInstructions(String penaltyPaymentInstructions) {
        this.penaltyPaymentInstructions = penaltyPaymentInstructions;
    }
}
