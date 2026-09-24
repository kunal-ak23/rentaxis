package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChargeTypeRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

class LeaseServiceUnitOccupancyTest {

    /**
     * What "living on this unit" must mean (review I3), written out rather than
     * read off {@code LeaseService.LIVE}: a stub keyed on the constant matches
     * whatever the constant happens to be, and would go on passing if somebody
     * narrowed it back to ACTIVE alone.
     */
    private static final java.util.Set<LeaseStatus> LIVE_STATUSES =
            java.util.EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN);

    private LeaseRepository leaseRepository;
    private UnitRepository unitRepository;
    private RenterRepository renterRepository;
    private LeaseInteractionRepository leaseInteractionRepository;
    private LeaseLineRepository leaseLineRepository;
    private ChequeRepository chequeRepository;
    private LeaseDocumentRepository leaseDocumentRepository;
    private LeaseService service;

    @BeforeEach
    void setUp() {
        leaseRepository = mock(LeaseRepository.class);
        unitRepository = mock(UnitRepository.class);
        renterRepository = mock(RenterRepository.class);
        leaseInteractionRepository = mock(LeaseInteractionRepository.class);
        leaseDocumentRepository = mock(LeaseDocumentRepository.class);

        // Pass-through by default: these tests are about lease behaviour, not
        // authorization. LeaseAccessPolicyTest covers the scoping itself.
        com.datagami.rentaxis.core.security.LeaseAccessPolicy leaseAccessPolicy =
                mock(com.datagami.rentaxis.core.security.LeaseAccessPolicy.class);
        when(leaseAccessPolicy.filterReadable(any())).thenAnswer(inv -> inv.getArgument(0));

        leaseLineRepository = mock(LeaseLineRepository.class);
        chequeRepository = mock(ChequeRepository.class);

        service = new LeaseService(
                leaseRepository,
                unitRepository,
                renterRepository,
                mock(LeaseEventRepository.class),
                leaseDocumentRepository,
                mock(LeaseAttachmentRepository.class),
                leaseInteractionRepository,
                leaseLineRepository,
                mock(com.datagami.rentaxis.domain.repository.LeaseAddendumRepository.class),
                mock(ChargeTypeRepository.class),
                mock(AccountRepository.class),
                chequeRepository,
                // Answers empty by default: a draft with no explicit grace falls back
                // to the property's rent-collection policy, and there is none here.
                mock(com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository.class),
                mock(com.datagami.rentaxis.core.service.ledger.AccountResolver.class),
                mock(UnitListingService.class),
                mock(ApplicationEventPublisher.class),
                leaseAccessPolicy);

        when(leaseRepository.save(any(Lease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(leaseDocumentRepository.findByLeaseId(any())).thenReturn(List.of());
        when(leaseLineRepository.findByLease_IdOrderBySeqNoAsc(any())).thenReturn(List.of());
        // Activation and termination now take the unit row FOR UPDATE so the
        // occupancy check and the status flip cannot interleave with a
        // concurrent activation; hand the same instance back.
        when(unitRepository.findByIdForUpdate(any()))
                .thenAnswer(inv -> Optional.empty());
        when(unitRepository.save(any(Unit.class))).thenAnswer(inv -> inv.getArgument(0));
        // No other lease holds any unit unless a test says so.
        when(leaseRepository.findByUnitIdAndStatus(any(), any())).thenReturn(List.of());
        // The occupancy rules ask "who is living on this unit" — ACTIVE *and*
        // NOTICE_GIVEN (LIVE_STATUSES), review I3.
        when(leaseRepository.findByUnitIdAndStatusIn(any(), any())).thenReturn(List.of());
    }

    /** Makes findByIdForUpdate resolve to this lease's unit. */
    private void lockableUnit(Lease lease) {
        when(unitRepository.findByIdForUpdate(lease.getUnit().getId()))
                .thenReturn(Optional.of(lease.getUnit()));
    }

    /**
     * {@code activateLease} is gone; the lease side of a post is
     * {@code markActiveOnPosting}, called by {@code LeasePostingService} once the
     * journals are written. The occupancy behaviour it carries is unchanged, which
     * is what these tests are here for.
     */
    @Test
    void markActiveOnPosting_updatesUnitRevenueAndTenantName() {
        Lease lease = lease(LeaseStatus.DRAFT);
        lockableUnit(lease);

        service.markActiveOnPosting(lease, "Lease posted TCO-26/1");

        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(lease.getUnit().getActualRent()).isEqualByComparingTo("72000");
        assertThat(lease.getUnit().getCurrentTenantName()).isEqualTo("Test Renter");
    }

    /**
     * Renter acceptance records a fact and stops there.
     *
     * <p>It used to activate the lease and take the unit, which meant a renter
     * tapping Accept in the portal moved a contract onto the landlord's books with
     * no journal behind it — nothing in the renter's own flow can post one. The
     * accountant closes the loop with Post, which is now the only path to ACTIVE
     * (spec §6.3).</p>
     */
    @Test
    void renterAcceptance_recordsTheAcceptanceWithoutActivatingOrTakingTheUnit() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));
        lockableUnit(lease);

        service.acceptLease(lease.getId(), userId);

        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.PENDING_SIGNATURE);
        assertThat(lease.getRenterAcceptedAt()).isNotNull();
        // The unit is untouched: it is still lettable until the contract posts.
        assertThat(lease.getUnit().getStatus()).isNotEqualTo(UnitStatus.OCCUPIED);
        assertThat(lease.getUnit().getCurrentTenantName()).isNull();
        verify(unitRepository, org.mockito.Mockito.never()).save(any(Unit.class));
    }

    /** #79: once the renter has accepted, they cannot reject — the lease stays with the landlord. */
    @Test
    void rejectAfterAcceptance_isRefusedAndTheLeaseStaysPending() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        lease.setRenterAcceptedAt(java.time.Instant.parse("2026-09-20T21:30:00Z"));
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rejectLease(lease.getId(), userId))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                // 21:30Z is already the 21st in Dubai.
                .hasMessageContaining("accepted this contract on 21/09/2026")
                .hasMessageContaining("can no longer be rejected");
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.PENDING_SIGNATURE);
        verify(leaseRepository, org.mockito.Mockito.never()).save(any(Lease.class));
    }

    @Test
    void rejectBeforeAcceptance_stillSendsTheLeaseBackToDraft() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));
        when(leaseRepository.save(any(Lease.class))).thenAnswer(inv -> inv.getArgument(0));

        service.rejectLease(lease.getId(), userId);

        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    /**
     * PR #344 review M5: accept is bound to the contract document the renter was
     * shown. A page opened before the landlord regenerated the contract sends the
     * old document's id and is refused; the current id is accepted.
     */
    @Test
    void acceptingAStaleContractVersion_isRefused_theCurrentOneIsAccepted() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));
        com.datagami.rentaxis.domain.entity.LeaseDocument current = new com.datagami.rentaxis.domain.entity.LeaseDocument();
        current.setId(UUID.randomUUID());
        current.setType(com.datagami.rentaxis.domain.entity.enums.DocumentType.CONTRACT);
        when(leaseDocumentRepository.findByLeaseId(lease.getId())).thenReturn(List.of(current));

        UUID stale = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.acceptLease(lease.getId(), userId, stale))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .hasMessageContaining("new version of this contract");
        assertThat(lease.getRenterAcceptedAt()).isNull();

        service.acceptLease(lease.getId(), userId, current.getId());
        assertThat(lease.getRenterAcceptedAt()).isNotNull();
    }

    @Test
    void acceptingTwice_isRefusedAndKeepsTheFirstDate() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        java.time.Instant first = java.time.Instant.parse("2026-09-20T08:00:00Z");
        lease.setRenterAcceptedAt(first);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.acceptLease(lease.getId(), userId))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .hasMessageContaining("waiting for your landlord");
        assertThat(lease.getRenterAcceptedAt()).isEqualTo(first);
    }

    @Test
    void termination_clearsUnitRevenueAndTenantName() {
        Lease lease = lease(LeaseStatus.ACTIVE);
        lease.getUnit().setStatus(UnitStatus.OCCUPIED);
        lease.getUnit().setActualRent(new BigDecimal("72000"));
        lease.getUnit().setCurrentTenantName("Test Renter");
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);

        // terminateLease is gone: a termination is now returning the uncleared
        // paper, truncating recognition and reversing the unearned rent
        // (LeaseTerminationService). markTerminated is the lease-side step that
        // kept this occupancy rule, which is what these two tests are about.
        service.markTerminated(lease.getId(), LocalDate.of(2026, 6, 30), "Move out complete", null, null);

        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(lease.getUnit().getActualRent()).isZero();
        assertThat(lease.getUnit().getCurrentTenantName()).isNull();
    }

    private Lease lease(LeaseStatus status) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn("Test Property");

        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber("101");
        unit.setProperty(property);

        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setNameEn("Test Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(UUID.randomUUID());
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStatus(status);
        lease.setStartDate(LocalDate.of(2026, 1, 1));
        lease.setEndDate(LocalDate.of(2026, 12, 31));
        lease.setRentAmount(new BigDecimal("72000"));
        return lease;
    }

    // ---- one active lease per unit (#197) ----------------------------------

    /** A different lease already ACTIVE on the same unit. */
    private Lease otherActiveLeaseOn(Lease lease) {
        Lease other = new Lease();
        other.setId(UUID.randomUUID());
        other.setStatus(LeaseStatus.ACTIVE);
        other.setUnit(lease.getUnit());
        return other;
    }

    @Test
    void markActiveOnPosting_refusesWhenAnotherLeaseAlreadyHoldsTheUnit() {
        Lease lease = lease(LeaseStatus.DRAFT);
        lockableUnit(lease);
        when(leaseRepository.findByUnitIdAndStatusIn(lease.getUnit().getId(), LIVE_STATUSES))
                .thenReturn(List.of(otherActiveLeaseOn(lease)));

        // Before the fix both leases activated and both renters were invoiced
        // for the same unit.
        assertThatThrownBy(() -> service.markActiveOnPosting(lease, "Lease posted TCO-26/1"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already has an active lease");
    }

    /**
     * The precondition {@code activateLease} carried, kept on its successor.
     *
     * <p>The method is public, it claims a unit, it publishes an activation e-mail
     * and it writes a transition row. Handed an already-ACTIVE lease it would do all
     * three for a contract that is not becoming active — a second LEASE_ACTIVATED to
     * the renter and a DRAFT→ACTIVE row that never happened.</p>
     */
    @Test
    void markActiveOnPosting_refusesALeaseThatIsNotDraftOrPendingSignature() {
        for (LeaseStatus status : List.of(LeaseStatus.ACTIVE, LeaseStatus.TERMINATED, LeaseStatus.RENEWED)) {
            Lease lease = lease(status);
            lockableUnit(lease);

            assertThatThrownBy(() -> service.markActiveOnPosting(lease, "Lease posted TCO-26/1"))
                    .as("from " + status)
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("Only a DRAFT or PENDING_SIGNATURE lease can become ACTIVE");
            assertThat(lease.getStatus()).isEqualTo(status);
        }
        verify(unitRepository, org.mockito.Mockito.never()).save(any(Unit.class));
    }

    /** PENDING_SIGNATURE is the other legitimate starting point: the renter has accepted. */
    @Test
    void markActiveOnPosting_acceptsAPendingSignatureLease() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        lockableUnit(lease);

        service.markActiveOnPosting(lease, "Lease posted TCO-26/1");

        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
    }

    @Test
    void markActiveOnPosting_isNotBlockedByItsOwnAlreadyActiveRow() {
        Lease lease = lease(LeaseStatus.DRAFT);
        lockableUnit(lease);
        // The lease being posted must not count as "another" holder.
        when(leaseRepository.findByUnitIdAndStatusIn(lease.getUnit().getId(), LIVE_STATUSES))
                .thenReturn(List.of(lease));

        service.markActiveOnPosting(lease, "Lease posted TCO-26/1");

        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
    }

    @Test
    void termination_leavesTheUnitOccupiedWhenAnotherLeaseIsStillActive() {
        Lease lease = lease(LeaseStatus.ACTIVE);
        lease.getUnit().setStatus(UnitStatus.OCCUPIED);
        lease.getUnit().setActualRent(new BigDecimal("72000"));
        lease.getUnit().setCurrentTenantName("Sitting Renter");
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);
        when(leaseRepository.findByUnitIdAndStatusIn(lease.getUnit().getId(), LIVE_STATUSES))
                .thenReturn(List.of(lease, otherActiveLeaseOn(lease)));

        // terminateLease is gone: a termination is now returning the uncleared
        // paper, truncating recognition and reversing the unearned rent
        // (LeaseTerminationService). markTerminated is the lease-side step that
        // kept this occupancy rule, which is what these two tests are about.
        service.markTerminated(lease.getId(), LocalDate.of(2026, 6, 30), "Move out complete", null, null);

        // Terminating the older of two overlapping leases used to wipe the
        // occupancy of the one still running, freeing a unit someone lives in.
        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(lease.getUnit().getCurrentTenantName()).isEqualTo("Sitting Renter");
        assertThat(lease.getUnit().getActualRent()).isEqualByComparingTo("72000");
    }

    /**
     * A renter who has given notice is still in the flat (review I3).
     *
     * <p>Both halves of the rule, on the same neighbour: the unit cannot be claimed
     * by another lease's post, and ending an overlapping lease does not vacate it.
     * Before {@code LIVE} the occupancy rules asked only about ACTIVE, so recording
     * a notice — which this task made reachable for the first time — quietly made a
     * unit lettable while somebody lived in it.</p>
     */
    @Test
    void aTenancyOnNoticeStillHoldsItsUnit() {
        Lease posting = lease(LeaseStatus.DRAFT);
        lockableUnit(posting);
        Lease neighbourOnNotice = otherActiveLeaseOn(posting);
        neighbourOnNotice.setStatus(LeaseStatus.NOTICE_GIVEN);
        when(leaseRepository.findByUnitIdAndStatusIn(posting.getUnit().getId(), LIVE_STATUSES))
                .thenReturn(List.of(neighbourOnNotice));

        assertThatThrownBy(() -> service.markActiveOnPosting(posting, "Lease posted TCO-26/1"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already has an active lease");

        Lease ending = lease(LeaseStatus.ACTIVE);
        ending.getUnit().setStatus(UnitStatus.OCCUPIED);
        ending.getUnit().setCurrentTenantName("Renter On Notice");
        ending.getUnit().setActualRent(new BigDecimal("72000"));
        when(leaseRepository.findById(ending.getId())).thenReturn(Optional.of(ending));
        lockableUnit(ending);
        Lease sameUnitOnNotice = otherActiveLeaseOn(ending);
        sameUnitOnNotice.setStatus(LeaseStatus.NOTICE_GIVEN);
        when(leaseRepository.findByUnitIdAndStatusIn(ending.getUnit().getId(), LIVE_STATUSES))
                .thenReturn(List.of(ending, sameUnitOnNotice));

        service.markTerminated(ending.getId(), LocalDate.of(2026, 6, 30), "Move out complete", null, null);

        assertThat(ending.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(ending.getUnit().getCurrentTenantName()).isEqualTo("Renter On Notice");
        assertThat(ending.getUnit().getActualRent()).isEqualByComparingTo("72000");
    }

    // ---- draft deletion releases lease_interactions (#200) ------------------

    @Test
    void deleteDraftLease_alsoRemovesLeaseInteractions() {
        Lease lease = lease(LeaseStatus.DRAFT);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        service.deleteDraftLease(lease.getId());

        // lease_interactions.lease_id is NOT NULL with no ON DELETE clause, and
        // softDelete only stamps deletedAt — the row keeps holding the FK. A
        // single note therefore made the draft permanently undeletable behind an
        // opaque 500, so this delete has to happen before the lease goes.
        verify(leaseInteractionRepository).deleteByLeaseId(lease.getId());
        // cheques.lease_id has no ON DELETE clause either, so a draft that had
        // cheques generated against it would fail the same way.
        verify(leaseLineRepository).deleteByLease_Id(lease.getId());
        verify(chequeRepository).deleteByLease_IdAndStatus(lease.getId(), ChequeStatus.DRAFT);
        verify(leaseRepository).delete(lease);
    }

    /**
     * Going ACTIVE cuts no instruments. Cheques are generated explicitly against
     * the lease's lines and registered by the post — a payment plan appearing as a
     * side effect of a status change is what let a lease bill a renter for
     * instalments nobody had agreed to.
     *
     * <p>This used to assert that no {@code payment_schedules} row was written.
     * That table is gone (changeset 84) and the register took its place, so the
     * same fact is now pinned on {@code ChequeRepository}.</p>
     */
    @Test
    void markActiveOnPosting_cutsNoInstruments() {
        Lease lease = lease(LeaseStatus.DRAFT);
        lockableUnit(lease);

        service.markActiveOnPosting(lease, "Lease posted TCO-26/1");

        verify(chequeRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
        verify(chequeRepository, org.mockito.Mockito.never())
                .saveAll(org.mockito.ArgumentMatchers.any());
    }
}
