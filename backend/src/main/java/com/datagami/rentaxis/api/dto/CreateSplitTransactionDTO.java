package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CreateSplitTransactionDTO {

    private LocalDate date;
    private String description;
    private UUID accountId;
    private BigDecimal debit;
    private BigDecimal credit;
    private boolean vatApplicable;
    private BigDecimal vatRate;
    private String notes;
    private UUID vendorId;
    private UUID staffId;
    private List<SplitAllocation> splits;

    public static class SplitAllocation {
        private UUID propertyId;
        private UUID unitId;
        private BigDecimal amount;

        public UUID getPropertyId() { return propertyId; }
        public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
        public UUID getUnitId() { return unitId; }
        public void setUnitId(UUID unitId) { this.unitId = unitId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }

    // Getters and setters
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
    public boolean isVatApplicable() { return vatApplicable; }
    public void setVatApplicable(boolean vatApplicable) { this.vatApplicable = vatApplicable; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }
    public List<SplitAllocation> getSplits() { return splits; }
    public void setSplits(List<SplitAllocation> splits) { this.splits = splits; }
}
