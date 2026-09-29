package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "landlord_org")
public class LandlordOrg {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "address")
    private String address;

    @Column(name = "trn", length = 50)
    private String trn;

    @Column(name = "logo_url", columnDefinition = "text")
    private String logoUrl;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "stamp_image_url", columnDefinition = "text")
    private String stampImageUrl;

    /**
     * When the current stamp was saved (null: none, or saved before this was
     * recorded). The executed-copy sweep only retries leases posted after it.
     */
    @Column(name = "stamp_set_at")
    private java.time.Instant stampSetAt;

    public java.time.Instant getStampSetAt() {
        return stampSetAt;
    }

    public void setStampSetAt(java.time.Instant stampSetAt) {
        this.stampSetAt = stampSetAt;
    }

    @Column(name = "ticket_otp_required")
    private Boolean ticketOtpRequired = true;

    @Column(nullable = false, unique = true)
    private String slug;

    @PrePersist
    public void onPrePersist() {
        if (this.slug == null || this.slug.isBlank()) {
            // LandlordOrgService.provisionTenant refuses a name whose slug is taken
            // (break-it R4 brand4 F2); the unique index is the backstop.
            this.slug = slugOf(this.name);
        }
    }

    /** The slug a name gets: lower case, runs of anything but a–z/0–9 as one hyphen. */
    public static String slugOf(String name) {
        String base = name == null ? "" : name.toLowerCase();
        String slugified = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slugified.isBlank() ? "tenant" : slugified;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getTrn() {
        return trn;
    }

    public void setTrn(String trn) {
        this.trn = trn;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getStampImageUrl() {
        return stampImageUrl;
    }

    public void setStampImageUrl(String stampImageUrl) {
        this.stampImageUrl = stampImageUrl;
    }

    public Boolean getTicketOtpRequired() { return ticketOtpRequired; }
    public void setTicketOtpRequired(Boolean v) { this.ticketOtpRequired = v; }
}
