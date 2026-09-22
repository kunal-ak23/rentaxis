package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The register row's wire shape. A 36-component record built by positional
 * construction has several runs of same-typed components — three labels, four
 * lifecycle dates, three journal ids — where a transposition compiles cleanly and
 * ships a cheque's bounce date in the "cleared on" column. Every value here is
 * distinct so a swap cannot pass.
 */
class ChequeMapperTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 18);

    private final UUID chequeId = UUID.randomUUID();
    private final UUID leaseId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();
    private final UUID debitAccountId = UUID.randomUUID();
    private final UUID replacesId = UUID.randomUUID();
    private final UUID replacedById = UUID.randomUUID();
    private final UUID pdrId = UUID.randomUUID();
    private final UUID crtId = UUID.randomUUID();
    private final UUID cbrId = UUID.randomUUID();
    private final UUID penaltyId = UUID.randomUUID();

    private Cheque fullyPopulated() {
        Property property = new Property();
        property.setId(propertyId);
        property.setNameEn("L'Olivier");

        Unit unit = new Unit();
        unit.setId(unitId);
        unit.setUnitNumber("304");

        Renter renter = new Renter();
        renter.setId(renterId);
        renter.setNameEn("Prabhjot Singh");

        Lease lease = new Lease();
        lease.setId(leaseId);
        lease.setStatus(LeaseStatus.TERMINATED);

        Account debitAccount = new Account();
        debitAccount.setId(debitAccountId);
        debitAccount.setName("Emirates NBD - Current");

        Cheque replaces = new Cheque();
        replaces.setId(replacesId);
        Cheque replacedBy = new Cheque();
        replacedBy.setId(replacedById);

        Cheque c = new Cheque();
        c.setId(chequeId);
        c.setLease(lease);
        c.setProperty(property);
        c.setUnit(unit);
        c.setRenter(renter);
        c.setDebitAccount(debitAccount);
        c.setSeqNo(3);
        c.setPostingDate(LocalDate.of(2026, 9, 1));
        c.setChequeNumber("000123");
        c.setChequeDate(LocalDate.of(2026, 9, 10));
        c.setPayeeBank("Emirates NBD");
        c.setPayerName("Prabhjot Singh");
        c.setAmount(new BigDecimal("13700.00"));
        c.setNarration("Instalment 3 of 4");
        c.setMode(ChequeMode.PDC);
        c.setStatus(ChequeStatus.BOUNCED);
        c.setFailureReason(ChequeFailureReason.SIGNATURE_MISMATCH);
        c.setReplaces(replaces);
        c.setReplacedBy(replacedBy);
        c.setImageUrl("https://blob/cheques/000123.jpg");
        c.setDepositedAt(LocalDate.of(2026, 9, 11));
        c.setClearedAt(LocalDate.of(2026, 9, 12));
        c.setBouncedAt(LocalDate.of(2026, 9, 13));
        c.setReturnedAt(LocalDate.of(2026, 9, 14));
        c.setPdrJournalId(pdrId);
        c.setCrtJournalId(crtId);
        c.setCbrJournalId(cbrId);
        c.setPenaltyAssessmentId(penaltyId);
        return c;
    }

    @Test
    void everyComponentComesFromTheFieldItNames() {
        ChequeDTO dto = ChequeMapper.toDto(fullyPopulated(), TODAY, 0);

        assertThat(dto.id()).isEqualTo(chequeId);
        assertThat(dto.leaseId()).isEqualTo(leaseId);
        // Not the cheque's status, which is BOUNCED on this fixture: the two are
        // adjacent enums on the wire and the register renders a different set of
        // actions from each.
        assertThat(dto.leaseStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertThat(dto.propertyId()).isEqualTo(propertyId);
        assertThat(dto.unitId()).isEqualTo(unitId);
        assertThat(dto.renterId()).isEqualTo(renterId);

        // The three labels are all Strings in a row — a transposition would compile.
        assertThat(dto.propertyName()).isEqualTo("L'Olivier");
        assertThat(dto.unitIdentifier()).isEqualTo("304");
        assertThat(dto.renterName()).isEqualTo("Prabhjot Singh");

        assertThat(dto.seqNo()).isEqualTo(3);
        assertThat(dto.postingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(dto.chequeNumber()).isEqualTo("000123");
        assertThat(dto.chequeDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(dto.payeeBank()).isEqualTo("Emirates NBD");
        assertThat(dto.payerName()).isEqualTo("Prabhjot Singh");
        assertThat(dto.debitAccountId()).isEqualTo(debitAccountId);
        assertThat(dto.debitAccountName()).isEqualTo("Emirates NBD - Current");
        assertThat(dto.amount()).isEqualByComparingTo("13700.00");
        assertThat(dto.narration()).isEqualTo("Instalment 3 of 4");
        assertThat(dto.mode()).isEqualTo(ChequeMode.PDC);
        assertThat(dto.status()).isEqualTo(ChequeStatus.BOUNCED);
        assertThat(dto.failureReason()).isEqualTo(ChequeFailureReason.SIGNATURE_MISMATCH);

        // The replacement chain points both ways and the two ends must not swap.
        assertThat(dto.replacesId()).isEqualTo(replacesId);
        assertThat(dto.replacedById()).isEqualTo(replacedById);
        assertThat(dto.imageUrl()).isEqualTo("https://blob/cheques/000123.jpg");

        // Four consecutive LocalDates, one day apart, so an off-by-one slot shows.
        assertThat(dto.depositedAt()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(dto.clearedAt()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(dto.bouncedAt()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(dto.returnedAt()).isEqualTo(LocalDate.of(2026, 9, 14));

        // Three consecutive journal ids — the ledger entries for register, clear and bounce.
        assertThat(dto.pdrJournalId()).isEqualTo(pdrId);
        assertThat(dto.crtJournalId()).isEqualTo(crtId);
        assertThat(dto.cbrJournalId()).isEqualTo(cbrId);
        assertThat(dto.penaltyAssessmentId()).isEqualTo(penaltyId);
    }

    /**
     * The contract's own status travels with the row, because what a row still
     * admits is a property of the lease and not of the instrument: a CLOSED tenancy
     * refuses every transition, and a register that reads only {@code status} keeps
     * offering Deposit and Clear on rows the server will turn down.
     */
    @Test
    void theRowCarriesTheLeaseStatusItBelongsTo() {
        Cheque c = fullyPopulated();
        for (LeaseStatus status : LeaseStatus.values()) {
            c.getLease().setStatus(status);
            assertThat(ChequeMapper.toDto(c, TODAY, 0).leaseStatus())
                    .as("a row on a " + status + " lease").isEqualTo(status);
        }
    }

    /** A cash receipt has no unit, no bank account and no replacement chain. */
    @Test
    void nullRelationsMapToNullRatherThanThrowing() {
        Cheque c = fullyPopulated();
        c.setUnit(null);
        c.setDebitAccount(null);
        c.setReplaces(null);
        c.setReplacedBy(null);

        assertThatCode(() -> ChequeMapper.toDto(c, TODAY, 0)).doesNotThrowAnyException();

        ChequeDTO dto = ChequeMapper.toDto(c, TODAY, 0);
        assertThat(dto.unitId()).isNull();
        assertThat(dto.unitIdentifier()).isNull();
        assertThat(dto.debitAccountId()).isNull();
        assertThat(dto.debitAccountName()).isNull();
        assertThat(dto.replacesId()).isNull();
        assertThat(dto.replacedById()).isNull();
        assertThat(dto.leaseStatus()).as("still the lease's").isEqualTo(LeaseStatus.TERMINATED);
        // The rest of the row still maps.
        assertThat(dto.propertyName()).isEqualTo("L'Olivier");
        assertThat(dto.renterName()).isEqualTo("Prabhjot Singh");
    }

    @Test
    void dueFlagsAreComputedAgainstTheSuppliedDateAndGrace() {
        Cheque c = fullyPopulated();
        c.setStatus(ChequeStatus.REGISTERED);
        c.setChequeDate(TODAY.minusDays(10));

        ChequeDTO noGrace = ChequeMapper.toDto(c, TODAY, 0);
        assertThat(noGrace.due()).isTrue();
        assertThat(noGrace.overdue()).isTrue();
        assertThat(noGrace.daysOverdue()).isEqualTo(10);

        // Grace shortens the count; it does not change whether the money is due.
        ChequeDTO withGrace = ChequeMapper.toDto(c, TODAY, 5);
        assertThat(withGrace.due()).isTrue();
        assertThat(withGrace.overdue()).isTrue();
        assertThat(withGrace.daysOverdue()).isEqualTo(5);

        // Grace still running: due, not yet overdue, and no count to show.
        ChequeDTO inGrace = ChequeMapper.toDto(c, TODAY, 30);
        assertThat(inGrace.due()).isTrue();
        assertThat(inGrace.overdue()).isFalse();
        assertThat(inGrace.daysOverdue()).isZero();
    }

    /**
     * A cheque that cleared a year ago is not 365 days overdue. The underlying rule
     * is a pure date difference and would say otherwise, so the wire shape zeroes
     * the count whenever nothing is overdue.
     */
    @Test
    void daysOverdueIsZeroWheneverTheChequeIsNotOverdue() {
        Cheque cleared = fullyPopulated();
        cleared.setStatus(ChequeStatus.CLEARED);
        cleared.setChequeDate(TODAY.minusYears(1));

        ChequeDTO dto = ChequeMapper.toDto(cleared, TODAY, 0);
        assertThat(dto.due()).isFalse();
        assertThat(dto.overdue()).isFalse();
        assertThat(dto.daysOverdue()).isZero();
        // The rule itself keeps its unconditional meaning for other callers.
        assertThat(ChequeDueRules.daysOverdue(cleared, 0, TODAY)).isEqualTo(365);

        for (ChequeStatus status : ChequeStatus.values()) {
            Cheque c = fullyPopulated();
            c.setStatus(status);
            c.setChequeDate(TODAY.minusYears(1));
            ChequeDTO row = ChequeMapper.toDto(c, TODAY, 0);
            assertThat(row.daysOverdue() > 0)
                    .as("%s: a positive day count must only accompany an overdue row", status)
                    .isEqualTo(row.overdue());
        }
    }
}
