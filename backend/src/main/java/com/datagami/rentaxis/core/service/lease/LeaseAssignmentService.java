package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.AssignLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseAssignmentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAssignment;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A lease assigned to another renter (F14-39): the death of the tenant, a company
 * novation. Same unit, same lease number, same deposit and cheque schedule, same
 * history — only who owes changes.
 *
 * <p><b>Draft, then post.</b> The draft names the incoming renter, the effective
 * date and the reason; it writes nothing to the ledger. The post moves every
 * balance the outgoing renter's sub-ledger holds on this lease — rent receivable,
 * PDC receivable (per cheque), the deposit liability, unearned rent and charges,
 * deferred VAT — to the incoming renter in <em>one</em> journal: per
 * account, property and cheque, {@code Dr B / Cr A} for a debit balance and the
 * other way round for a credit. Income already earned stays with A: it is history.
 * The open cheques (not cleared, returned, replaced or cancelled) are re-attributed
 * to B; the drawer on the paper is left as written. Planned VAT tax points follow.</p>
 *
 * <p><b>Overdue items</b> (an instalment past its grace, a bounce) are refused
 * unless the user confirms that B takes them on. Every method is
 * {@code @Transactional}: the tenant filter applies only inside one, and the native
 * balance query binds {@code tenant_id} itself.</p>
 */
@Service
public class LeaseAssignmentService {

    private static final Set<LeaseStatus> ASSIGNABLE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN);
    /** The rows that are still somebody's to pay; the rest are history and stay with A. */
    private static final Set<ChequeStatus> OPEN = EnumSet.of(ChequeStatus.DRAFT, ChequeStatus.REGISTERED,
            ChequeStatus.DEPOSITED, ChequeStatus.ONLINE_PENDING, ChequeStatus.BOUNCED);

    private final LeaseRepository leaseRepository;
    private final LeaseAssignmentRepository assignments;
    private final RenterRepository renters;
    private final ChequeRepository chequeRepository;
    private final LeaseService leaseService;
    private final LeasePostingService leasePostingService;
    private final PostingService postingService;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired
    private com.datagami.rentaxis.core.service.ledger.AccountResolver accountResolver;

    public LeaseAssignmentService(LeaseRepository leaseRepository, LeaseAssignmentRepository assignments,
                                  RenterRepository renters, ChequeRepository chequeRepository, LeaseService leaseService,
                                  LeasePostingService leasePostingService, PostingService postingService,
                                  LeaseAccessPolicy leaseAccessPolicy, NamedParameterJdbcTemplate jdbc,
                                  EntityManager entityManager) {
        this.leaseRepository = leaseRepository;
        this.assignments = assignments;
        this.renters = renters;
        this.chequeRepository = chequeRepository;
        this.leaseService = leaseService;
        this.leasePostingService = leasePostingService;
        this.postingService = postingService;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------
    // draft
    // ------------------------------------------------------------------

    @Transactional
    public LeaseAssignmentDTO draft(UUID leaseId, AssignLeaseRequest r) {
        Lease lease = leasePostingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        requireAssignable(lease);
        if (assignments.existsByLeaseIdAndStatus(leaseId, LeaseAssignment.DRAFT)) {
            throw new BusinessRuleViolationException("This lease already has a draft assignment; post or delete it first.",
                    "lease.assignmentDraftExists", Map.of());
        }
        Renter to = validate(lease, r);
        LeaseAssignment a = new LeaseAssignment();
        a.setLeaseId(leaseId);
        a.setFromRenterId(lease.getRenter().getId());
        a.setToRenterId(to.getId());
        a.setEffectiveDate(r.effectiveDate());
        a.setReason(r.reason().trim());
        a.setTakeOverOverdue(r.takesOverOverdue());
        a.setCreatedBy(currentUserId());
        a = assignments.save(a);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Assignment to " + to.getNameEn() + " from " + r.effectiveDate() + " drafted: " + a.getReason());
        return dto(a, lease);
    }

    @Transactional(readOnly = true)
    public List<LeaseAssignmentDTO> list(UUID leaseId) {
        Lease lease = readable(leaseId);
        return assignments.findByLeaseIdOrderByCreatedAtAsc(leaseId).stream().map(a -> dto(a, lease)).toList();
    }

    @Transactional
    public void cancel(UUID leaseId, UUID assignmentId) {
        Lease lease = leasePostingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        LeaseAssignment a = assignments.findByIdAndLeaseId(assignmentId, leaseId)
                .orElseThrow(() -> new NotFoundException("Assignment not found"));
        if (!LeaseAssignment.DRAFT.equals(a.getStatus())) {
            throw new BusinessRuleViolationException("Only a draft assignment can be deleted; this one is " + a.getStatus() + ".");
        }
        a.setStatus(LeaseAssignment.CANCELLED);
        assignments.save(a);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(), "Draft assignment deleted");
    }

    // ------------------------------------------------------------------
    // post
    // ------------------------------------------------------------------

    /**
     * @param takeOverOverdue the confirmation, when given here rather than on the draft
     */
    @Transactional
    public LeaseAssignmentDTO post(UUID leaseId, UUID assignmentId, Boolean takeOverOverdue) {
        Lease lease = leasePostingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        requireAssignable(lease);
        LeaseAssignment a = assignments.findByIdAndLeaseId(assignmentId, leaseId)
                .orElseThrow(() -> new NotFoundException("Assignment not found"));
        if (!LeaseAssignment.DRAFT.equals(a.getStatus())) {
            throw new BusinessRuleViolationException("This assignment is " + a.getStatus() + "; only a draft can be posted.");
        }
        if (!lease.getRenter().getId().equals(a.getFromRenterId())) {
            throw new BusinessRuleViolationException("The lease has changed hands since this assignment was drafted;"
                    + " delete it and draft again.");
        }
        if (Boolean.TRUE.equals(takeOverOverdue)) a.setTakeOverOverdue(true);
        Renter to = validate(lease, new AssignLeaseRequest(a.getToRenterId(), a.getEffectiveDate(), a.getReason(),
                a.isTakeOverOverdue()));
        Renter from = lease.getRenter();
        LocalDate on = a.getEffectiveDate();

        List<String> lock = leasePostingService.periodLockErrors(on, List.of());
        if (!lock.isEmpty()) throw new BusinessRuleViolationException(String.join(" ", lock));

        // ---- the journal: A's sub-ledger on this lease, moved to B ------------
        List<Group> groups = balances(lease, from.getId());
        UUID propertyId = LeasePostingService.propertyIdOf(lease);
        UUID unitId = lease.getUnit() == null ? null : lease.getUnit().getId();
        String narration = "Lease assigned from " + from.getNameEn() + " to " + to.getNameEn()
                + (a.getReason() == null ? "" : ": " + a.getReason());
        List<PostingRequest.Pair> pairs = new ArrayList<>();
        for (Group g : groups) {
            PostingRequest.Dimensions dimsA = new PostingRequest.Dimensions(g.propertyId(), g.unitId(), leaseId, from.getId(), g.chequeId());
            PostingRequest.Dimensions dimsB = new PostingRequest.Dimensions(g.propertyId(), g.unitId(), leaseId, to.getId(), g.chequeId());
            BigDecimal amount = g.net().abs();
            PostingRequest.AccountRef acct = new PostingRequest.ById(g.accountId());
            // A debit balance (receivable, PDC) is owed by B now; a credit (deposit,
            // unearned rent, deferred VAT) is held for B now.
            PostingRequest.Dimensions drDims = g.net().signum() > 0 ? dimsB : dimsA;
            PostingRequest.Dimensions crDims = g.net().signum() > 0 ? dimsA : dimsB;
            pairs.add(PostingRequest.pair(
                    new PostingRequest.Line(acct, PostingRequest.Side.DR, amount, drDims, narration),
                    new PostingRequest.Line(acct, PostingRequest.Side.CR, amount, crDims, narration)));
        }
        JournalEntry jv = null;
        if (!pairs.isEmpty()) {
            jv = postingService.post(PostingRequest.ofPairs(JournalDocType.JV, on, narration,
                    new PostingRequest.Dimensions(propertyId, unitId, leaseId, null, null),
                    JournalSourceType.LEASE, leaseId, null, pairs));
        }

        // ---- the paper and the contract ----------------------------------
        int moved = 0;
        for (Cheque c : chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            if (!OPEN.contains(c.getStatus())) continue;
            if (c.getRenter() != null && !c.getRenter().getId().equals(from.getId())) continue;
            // The drawer is who signed the paper; it is recorded as written.
            if (c.getPayerName() == null || c.getPayerName().isBlank()) c.setPayerName(from.getNameEn());
            c.setRenter(to);
            chequeRepository.save(c);
            moved++;
        }
        entityManager.createQuery("update VatTaxPoint p set p.renterId = :to where p.leaseId = :lease"
                        + " and p.status = com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus.PLANNED")
                .setParameter("to", to.getId()).setParameter("lease", leaseId).executeUpdate();
        lease.setRenter(to);
        // B has not accepted this contract in the portal; A's acceptance is not B's.
        lease.setRenterAcceptedAt(null);
        leaseRepository.save(lease);
        leaseService.resettleUnitOf(lease);

        a.setStatus(LeaseAssignment.POSTED);
        a.setJournalId(jv == null ? null : jv.getId());
        a.setJournalNumber(jv == null ? null : jv.getEntryNumber());
        a.setPostedBy(currentUserId());
        a.setPostedAt(Instant.now());
        assignments.save(a);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Assigned from " + from.getNameEn() + " to " + to.getNameEn() + " on " + on
                        + (jv == null ? "" : ", balances moved by " + jv.getEntryNumber())
                        + "; " + moved + " open instalment(s) re-attributed"
                        + (a.isTakeOverOverdue() ? "; overdue items taken on" : "")
                        + (a.getReason() == null ? "" : " — " + a.getReason()));
        return dto(a, lease);
    }

    // ------------------------------------------------------------------
    // rules
    // ------------------------------------------------------------------

    private void requireAssignable(Lease lease) {
        if (!ASSIGNABLE.contains(lease.getStatus()) || lease.getPostingJournalId() == null) {
            throw new BusinessRuleViolationException("Only a posted ACTIVE or NOTICE_GIVEN lease can be assigned;"
                    + " this one is " + lease.getStatus() + ".");
        }
        // PR #359 R1 P2-3: a draft renewal or transfer was drawn up in the outgoing
        // renter's name; posted after the assignment it would hand everything back.
        java.util.List<Lease> successors = new java.util.ArrayList<>(leaseRepository.findByRenewedFromLeaseId(lease.getId()));
        successors.addAll(leaseRepository.findByTransferredFromLeaseId(lease.getId()));
        for (Lease s : successors) {
            if (s.getStatus() == LeaseStatus.DRAFT || s.getStatus() == LeaseStatus.PENDING_SIGNATURE) {
                throw new BusinessRuleViolationException("This lease has a draft " + (s.getTransferredFromLeaseId() != null
                        ? "transfer" : "renewal") + " in the current renter's name; delete it before assigning the lease.",
                        "lease.assignmentSuccessorDraft", Map.of("kind", s.getTransferredFromLeaseId() != null ? "transfer" : "renewal"));
            }
        }
    }

    private Renter validate(Lease lease, AssignLeaseRequest r) {
        if (r == null || r.toRenterId() == null) {
            throw new BusinessRuleViolationException("Choose the renter the lease goes to.");
        }
        if (r.effectiveDate() == null) {
            throw new BusinessRuleViolationException("The assignment needs an effective date.");
        }
        if (r.reason() == null || r.reason().isBlank()) {
            throw new BusinessRuleViolationException("Say why the lease changes hands.", "lease.assignmentReason", Map.of());
        }
        LocalDate end = lease.getTerminatedOn() != null ? lease.getTerminatedOn() : lease.getEndDate();
        if (r.effectiveDate().isBefore(lease.getStartDate()) || r.effectiveDate().isAfter(end)) {
            throw new BusinessRuleViolationException("The assignment must take effect within the tenancy ("
                    + lease.getStartDate() + " to " + end + "), not " + r.effectiveDate() + ".");
        }
        Renter to = renters.findById(r.toRenterId()).orElseThrow(() -> new NotFoundException("Renter not found"));
        UUID tenant = TenantContextHolder.getTenantId();
        if (tenant != null && !tenant.equals(to.getTenantId())) throw new NotFoundException("Renter not found");
        if (to.getId().equals(lease.getRenter().getId())) {
            throw new BusinessRuleViolationException("The lease already belongs to " + to.getNameEn() + ".",
                    "lease.assignmentSameRenter", Map.of("renter", to.getNameEn()));
        }
        List<Cheque> overdue = overdue(lease, r.effectiveDate());
        if (!overdue.isEmpty() && !r.takesOverOverdue()) {
            BigDecimal total = overdue.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            throw new BusinessRuleViolationException(lease.getRenter().getNameEn() + " has " + overdue.size()
                    + " overdue instalment(s) on this lease (" + LeasePostingService.money(total) + "). Confirm that "
                    + to.getNameEn() + " takes them on, or collect them first.",
                    "lease.assignmentOverdue", Map.of("from", lease.getRenter().getNameEn(), "to", to.getNameEn(),
                            "count", overdue.size(), "amount", LeasePostingService.money(total)));
        }
        return to;
    }

    private List<Cheque> overdue(Lease lease, LocalDate on) {
        return chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                .filter(c -> c.getChequeDate() != null && ChequeDueRules.overdue(c, lease.getGracePeriodDays(), on))
                .toList();
    }

    /**
     * One account/property/unit balance of A's sub-ledger on the lease (debit
     * positive) — per cheque for PDC receivable, where the clearing of each
     * instrument credits its own cheque's line.
     */
    record Group(UUID accountId, UUID propertyId, UUID unitId, UUID chequeId, BigDecimal net) {
    }

    /**
     * The renter's sub-ledger: receivable, PDCs held, unearned rent and charges,
     * deposits held, and the deferred output VAT their instalments will declare.
     * Not the bank a cleared cheque went into, and not income: those are history.
     */
    private List<Group> balances(Lease lease, UUID renterId) {
        return jdbc.query("""
                select l.account_id, l.property_id, l.unit_id,
                       case when a.account_sub_type = 'PDC_RECEIVABLE' then l.cheque_id end as cheque_id,
                       coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) as net
                  from journal_lines l
                  join journal_entries e on e.id = l.journal_entry_id
                  join accounts a on a.id = l.account_id
                 where l.tenant_id = :t and e.tenant_id = :t and a.tenant_id = :t
                   and l.lease_id = :lease and l.renter_id = :renter
                   and (a.account_sub_type in ('RECEIVABLE', 'PDC_RECEIVABLE', 'ADVANCE', 'DEPOSIT_HELD')
                        or a.id = :deferredVat)
                 group by l.account_id, l.property_id, l.unit_id,
                          case when a.account_sub_type = 'PDC_RECEIVABLE' then l.cheque_id end
                having coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) <> 0
                 order by 1, 4 nulls first""",
                new MapSqlParameterSource("t", lease.getTenantId()).addValue("lease", lease.getId())
                        .addValue("renter", renterId).addValue("deferredVat", deferredVatLeaf(lease)),
                (rs, i) -> new Group(rs.getObject("account_id", UUID.class), rs.getObject("property_id", UUID.class),
                        rs.getObject("unit_id", UUID.class), rs.getObject("cheque_id", UUID.class),
                        rs.getBigDecimal("net").setScale(2, RoundingMode.HALF_UP)))
                .stream().filter(g -> g.net().signum() != 0).toList();
    }

    private UUID deferredVatLeaf(Lease lease) {
        var acct = accountResolver.resolveOrNull(com.datagami.rentaxis.domain.entity.enums.AccountRole.OUTPUT_VAT_DEFERRED,
                LeasePostingService.propertyIdOf(lease));
        return acct == null ? new UUID(0, 0) : acct.getId();
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    private Lease readable(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenant = TenantContextHolder.getTenantId();
        if (tenant != null && !tenant.equals(lease.getTenantId())) throw new NotFoundException("Lease not found");
        leaseAccessPolicy.requireReadable(lease);
        return lease;
    }

    private LeaseAssignmentDTO dto(LeaseAssignment a, Lease lease) {
        Renter from = renters.findById(a.getFromRenterId()).orElse(null);
        Renter to = renters.findById(a.getToRenterId()).orElse(null);
        boolean draft = LeaseAssignment.DRAFT.equals(a.getStatus());
        List<LeaseAssignmentDTO.Balance> balances = List.of();
        List<LeaseAssignmentDTO.Overdue> overdue = List.of();
        int moving = 0;
        if (draft) {
            balances = balances(lease, a.getFromRenterId()).stream().map(g -> {
                var acct = entityManager.find(com.datagami.rentaxis.domain.entity.Account.class, g.accountId());
                return new LeaseAssignmentDTO.Balance(g.accountId(), acct == null ? null : acct.getCode(),
                        acct == null ? null : acct.getName(), acct == null ? null : acct.getNameAr(), g.net());
            }).toList();
            overdue = overdue(lease, a.getEffectiveDate()).stream().map(c -> new LeaseAssignmentDTO.Overdue(c.getId(),
                    c.getSeqNo(), c.getChequeNumber(), c.getChequeDate(), c.getStatus().name(), c.getAmount())).toList();
            moving = (int) chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                    .filter(c -> OPEN.contains(c.getStatus())).count();
        }
        return new LeaseAssignmentDTO(a.getId(), a.getLeaseId(), a.getFromRenterId(), from == null ? null : from.getNameEn(),
                a.getToRenterId(), to == null ? null : to.getNameEn(), a.getEffectiveDate(), a.getReason(),
                a.isTakeOverOverdue(), a.getStatus(), a.getJournalId(), a.getJournalNumber(), a.getCreatedAt(),
                a.getPostedAt(), balances, overdue, moving);
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
