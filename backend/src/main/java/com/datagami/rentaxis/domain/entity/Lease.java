package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The lease contract — from accounting v2 onwards, a posting document.
 *
 * <p>What the lease charges for lives in {@link LeaseLine} rows. {@code rentAmount}
 * and {@code depositAmount} stay on the row as <em>derived mirrors</em> of the
 * RENT and DEPOSIT lines (spec §6.3): they are recomputed by
 * {@code LeaseService.syncDerivedTotals} on every line change and must never be
 * set from a request body. Reports, the unit's {@code actualRent} and the renter
 * portal all read them, which is why they were kept rather than removed.</p>
 *
 * <p>{@code monthlyRent} is gone. It was a second source of truth for the same
 * money — the schedule generator multiplied it by a month count while the
 * contract PDF printed {@code rentAmount}, and the two disagreed whenever the
 * term was not a whole number of months. A monthly figure is now derived where it
 * is displayed ({@code LeaseService.monthlyRentOf}). The column survives until
 * changeset 84; JPA simply ignores it.</p>
 */
@Entity
@Table(name = "leases")
@Getter
@Setter
public class Lease extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id", nullable = false)
    private Renter renter;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LeaseStatus status = LeaseStatus.DRAFT;

    /** Derived: Σ net of the RENT lines. Read-only to callers — see the class note. */
    @Column(name = "rent_amount", nullable = false)
    private BigDecimal rentAmount = BigDecimal.ZERO;

    /** Derived: Σ net of the DEPOSIT-behaviour lines. Read-only to callers. */
    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Column(name = "ejari_number")
    private String ejariNumber;

    @Column(name = "payment_terms")
    private Integer paymentTerms;

    @Enumerated(EnumType.STRING)
    @Column(name = "installment_distribution", nullable = false, length = 30)
    private InstallmentDistribution installmentDistribution = InstallmentDistribution.LAST_LARGER;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.CHEQUE;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_payment_method", length = 20)
    private PaymentMethod depositPaymentMethod = PaymentMethod.CHEQUE;

    @Column(name = "payment_reference_number")
    private String paymentReferenceNumber;

    @Column(name = "contract_number")
    private Long contractNumber;

    /**
     * The contract number the tenancy carried in the system this tenant migrated
     * from — PACT's "SAMPLE-25/001" (changeset 88, spec §10.3).
     *
     * <p>Deliberately not {@code contractNumber}: that is a {@code Long}, our own
     * per-tenant sequence, and the column the next contract number is generated
     * from. An alphanumeric foreign identifier does not fit in it and must not be
     * allowed to steer it. Written only by the cut-over contract import; indexed
     * per tenant so a re-import after a batch reverse can find the earlier lease
     * by it.</p>
     */
    @Column(name = "external_contract_ref", length = 64)
    private String externalContractRef;

    @Column(name = "agreement_date")
    private LocalDate agreementDate;

    @Column(name = "rent_vat_applicable", nullable = false)
    private boolean rentVatApplicable = false;

    // ---- contract header (spec §6.3) ----------------------------------------

    /**
     * The date the contract is dated, and the date the posting journal carries.
     * Distinct from {@code startDate} (tenancy begins) and from
     * {@code agreementDate} (when it was signed): a contract dated in March for a
     * tenancy starting in June posts in March.
     */
    @Column(name = "contract_date")
    private LocalDate contractDate;

    /**
     * The earliest day something can happen under this lease: the contract date,
     * or the tenancy start if that is earlier or there is no contract date. The
     * floor for a notice date and a penalty's incident date (web review M5/M7).
     *
     * <p>Not the start date alone: a contract is dated before the tenancy begins
     * (the example above posts in March for June), and an advance cheque can
     * bounce, or a renter withdraw, in between.
     */
    public LocalDate earliestEventDate() {
        if (contractDate == null) return startDate;
        if (startDate == null) return contractDate;
        return contractDate.isBefore(startDate) ? contractDate : startDate;
    }

    /** Derived: inclusive day count of the term. The denominator of per-day rent recognition. */
    @Column(name = "total_days")
    private Integer totalDays;

    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays = 0;

    /** When the first instalment falls due; defaults to the tenancy start. */
    @Column(name = "first_due_date")
    private LocalDate firstDueDate;

    /**
     * When the renter accepted the contract in the portal.
     *
     * <p>Acceptance is a fact about the renter, not a posting: it records that the
     * paper is agreed and leaves the lease in {@code PENDING_SIGNATURE}. Only
     * {@code LeasePostingService.post} moves a lease to ACTIVE (spec §6.3), because
     * ACTIVE means "the contract is on the books" and a renter tapping Accept
     * cannot write journals.</p>
     */
    @Column(name = "renter_accepted_at")
    private Instant renterAcceptedAt;

    // ---- renewal chain ------------------------------------------------------

    @Column(name = "renewed_from_lease_id")
    private UUID renewedFromLeaseId;

    /**
     * Every lease in a renewal chain shares this id; the first lease's chain id is
     * its own id. Reports that ask "how long has this renter been here" walk the
     * chain rather than the {@code renewedFromLeaseId} links one at a time.
     */
    @Column(name = "chain_id")
    private UUID chainId;

    /**
     * The accountant chose "carry the deposit forward" when this lease was created
     * as a renewal (spec §6.6).
     *
     * <p>It is a decision recorded on the draft and acted on once, when the lease
     * <em>posts</em>: {@code DepositCarryForward} then writes one {@code JV} moving
     * the predecessor's remaining deposit liability onto this lease's dimension.
     * Storing it rather than asking again at post time is what makes the review
     * screen's promise and the posting's behaviour the same decision — and what
     * stops a renewal being drafted with no deposit line and posted with no
     * carry-forward either, leaving the money stranded on a retired contract.</p>
     */
    @Column(name = "carry_deposit_forward", nullable = false)
    private boolean carryDepositForward = false;

    // ---- posting (filled by Task 6) -----------------------------------------

    @Column(name = "receivable_account_id")
    private UUID receivableAccountId;

    @Column(name = "income_account_id")
    private UUID incomeAccountId;

    @Column(name = "posting_journal_id")
    private UUID postingJournalId;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    // ---- termination (spec §9.1) --------------------------------------------

    /**
     * The effective date of the termination — {@code T}.
     *
     * <p>Not "when somebody pressed the button": every journal the termination
     * writes is dated this day, the rent is earned up to and including it, and the
     * settlement statement is drawn as of it. It can be back-dated (the renter
     * moved out on the 15th and finance got to it on the 20th) and it can be
     * forward-dated within the term, which is why it is a date the caller supplies
     * rather than a timestamp taken here.</p>
     */
    @Column(name = "terminated_on")
    private LocalDate terminatedOn;

    /** The day notice was given (#27); null unless the lease went through NOTICE_GIVEN after changeset 94. */
    @Column(name = "notice_date")
    private LocalDate noticeDate;

    /** Who gave it: the renter leaving, or the landlord serving notice (#27). */
    @Enumerated(EnumType.STRING)
    @Column(name = "notice_given_by", length = 20)
    private com.datagami.rentaxis.domain.entity.enums.NoticeParty noticeGivenBy;

    /** The move-out date the notice names, when it names one (#27). */
    @Column(name = "intended_move_out_date")
    private LocalDate intendedMoveOutDate;

    /** The {@code TCR} that reversed the unearned rent, or null when nothing was unearned. */
    @Column(name = "termination_journal_id")
    private UUID terminationJournalId;

    @Column(name = "termination_notes")
    private String terminationNotes;

    @Version
    private Long version;
}
