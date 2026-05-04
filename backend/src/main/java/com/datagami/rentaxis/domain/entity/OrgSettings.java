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
