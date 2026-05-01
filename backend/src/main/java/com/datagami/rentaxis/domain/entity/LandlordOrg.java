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

    @Column(name = "ticket_otp_required")
    private Boolean ticketOtpRequired = true;

    @Column(nullable = false, unique = true)
    private String slug;

    @PrePersist
    public void onPrePersist() {
        if (this.slug == null || this.slug.isBlank()) {
            String base = this.name == null ? "" : this.name.toLowerCase();
            String slugified = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
            // TODO: handle slug collisions at the service layer; DB unique constraint enforces uniqueness for now.
            this.slug = slugified.isBlank() ? "tenant" : slugified;
        }
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
