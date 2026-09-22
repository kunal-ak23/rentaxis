package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create / update body for a draft lease (spec §6.2).
 *
 * <p>What the lease charges for arrives as {@link #lines}. The old
 * {@code rentAmount} / {@code monthlyRent} / {@code depositAmount} /
 * {@code charges} / {@code bookingDeposit} fields are gone: the first three are
 * now derived from the lines, and the last two were separate representations of
 * money the lines already describe.</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is deliberate and not
 * merely Jackson's default being restated. The web wizard still posts the legacy
 * shape until it is rewritten, and an unknown-property failure there would be a
 * 500 from deserialization rather than a message anyone can act on. A body with
 * no {@code lines} gets a plain 400 from {@code LeaseService.applyLines}
 * instead.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateLeaseDTO {
    @NotNull
    private UUID unitId;

    @NotNull
    private UUID renterId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    /** The date the contract is dated. Defaults to {@code agreementDate}, else today. */
    private LocalDate contractDate;

    /** Days after a due date before a late-payment penalty may be assessed. */
    @Min(0)
    private Integer gracePeriodDays;

    /** First instalment due date. Defaults to {@code startDate}. */
    private LocalDate firstDueDate;

    private String ejariNumber;

    @Min(1)
    private Integer paymentTerms;

    private InstallmentDistribution installmentDistribution;

    private String paymentMethod; // CHEQUE or ONLINE

    private String depositPaymentMethod; // CHEQUE or ONLINE

    private String paymentReferenceNumber;

    private LocalDate agreementDate;

    private Boolean rentVatApplicable;

    /** At least one line is required; {@code LeaseService} rejects an empty list. */
    private List<LeaseLineInput> lines;
}
