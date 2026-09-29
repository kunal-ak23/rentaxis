package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.OpeningBalancePostingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantFiscalSettingsServiceTest {

    TenantFiscalSettingsRepository repo = mock(TenantFiscalSettingsRepository.class);
    /**
     * The books start date cannot move while an opening-balance journal is live, so
     * the calendar reads the cut-over marker directly (a dependency on
     * {@code OpeningBalanceService} would be a cycle). Both mocks answer "nothing
     * posted", which is this unit's world.
     */
    OpeningBalancePostingRepository openingBalances = mock(OpeningBalancePostingRepository.class);
    JournalEntryRepository journals = mock(JournalEntryRepository.class);
    /** Same shape, for the other half of the invariant: no posted cut-over batch here either. */
    ImportBatchRepository importBatches = mock(ImportBatchRepository.class);
    /** No VAT tax point is waiting in this unit's world, so the lock is never refused for one. */
    com.datagami.rentaxis.domain.repository.VatTaxPointRepository vatTaxPoints =
            mock(com.datagami.rentaxis.domain.repository.VatTaxPointRepository.class);
    TenantFiscalSettingsService service =
            new TenantFiscalSettingsService(repo, openingBalances, journals, importBatches, vatTaxPoints,
                    mock(com.datagami.rentaxis.domain.repository.RecognitionEntryRepository.class),
                    mock(jakarta.persistence.EntityManager.class));
    UUID tenant = UUID.randomUUID();

    @BeforeEach void ctx() { TenantContextHolder.setTenantId(tenant); when(repo.save(any())).thenAnswer(i -> i.getArgument(0)); }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    private TenantFiscalSettings settings(int startMonth, LocalDate lockedThrough) {
        TenantFiscalSettings s = new TenantFiscalSettings();
        s.setTenantId(tenant); s.setFiscalYearStartMonth(startMonth); s.setBooksLockedThrough(lockedThrough);
        return s;
    }

    @Test
    void getCreatesDefaultsWhenMissing() {
        when(repo.findById(tenant)).thenReturn(Optional.empty());
        TenantFiscalSettings s = service.get();
        assertThat(s.getFiscalYearStartMonth()).isEqualTo(1);
        verify(repo).save(any());
    }

    @Test
    void fiscalYearIsCalendarYearWhenStartMonthIsJanuary() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 9, 24))).isEqualTo(2026);
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 1, 1))).isEqualTo(2026);
    }

    @Test
    void fiscalYearIsTheYearTheFiscalYearStartsWhenStartMonthIsJune() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(6, null)));
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 9, 24))).isEqualTo(2026);
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 5, 31))).isEqualTo(2025);
    }

    @Test
    void assertOpenRejectsDatesOnOrBeforeTheLock() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        assertThatThrownBy(() -> service.assertOpen(LocalDate.of(2026, 8, 31)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through 2026-08-31");
        service.assertOpen(LocalDate.of(2026, 9, 1)); // no throw
    }

    @Test
    void assertOpenPassesWhenNoLockIsSet() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        service.assertOpen(LocalDate.of(2000, 1, 1));
    }

    @Test
    void isOpenAnswersTheSameQuestionWithoutThrowing() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        assertThat(service.isOpen(LocalDate.of(2026, 8, 31))).isFalse();
        assertThat(service.isOpen(LocalDate.of(2026, 9, 1))).isTrue();
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        assertThat(service.isOpen(LocalDate.of(2000, 1, 1))).isTrue();
    }

    @Test
    void setBooksStartDateClosesEverythingBeforeItButLeavesAnExistingLockAlone() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        TenantFiscalSettings opened = service.setBooksStartDate(LocalDate.of(2026, 4, 1));
        assertThat(opened.getBooksStartDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(opened.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 3, 31));

        // Once journals exist, an existing lock is left alone (it only moves forward).
        when(journals.existsByTenantId(tenant)).thenReturn(true);
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        TenantFiscalSettings alreadyLocked = service.setBooksStartDate(LocalDate.of(2026, 4, 1));
        assertThat(alreadyLocked.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    private void today(String instant) {
        service.setManualPostingDates(new ManualPostingDates(java.time.Clock.fixed(
                java.time.Instant.parse(instant), ManualPostingDates.BUSINESS_ZONE)));
    }

    /**
     * Break-it R3 money3 N1 (BS1–BS3): the books start implied a far-future lock —
     * 2062-10-01 locked the organisation through 2062-09-30. At most three months
     * ahead (Dubai), with a readable coded refusal; nothing is saved.
     */
    @Test
    void theBooksStartIsAtMostThreeMonthsAhead() {
        today("2026-09-28T08:00:00Z");
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        for (LocalDate typo : new LocalDate[]{LocalDate.of(2062, 10, 1), LocalDate.of(2099, 12, 31), LocalDate.of(2027, 9, 29),
                LocalDate.of(2026, 12, 29)}) {
            assertThatThrownBy(() -> service.setBooksStartDate(typo))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("more than three months ahead")
                    .satisfies(e -> {
                        BusinessRuleViolationException b = (BusinessRuleViolationException) e;
                        assertThat(b.getCode()).isEqualTo("fiscal.booksStartTooFar");
                        assertThat(b.getArgs()).containsEntry("latest", "28/12/2026");
                    });
        }
        verify(repo, never()).save(any());
        TenantFiscalSettings ok = service.setBooksStartDate(LocalDate.of(2026, 12, 28));
        // Break-it R4 money4 F3 / review I2: the implied lock is never after yesterday.
        assertThat(ok.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    /**
     * Break-it R3 money3 N1 (BS4, the recovery path): an organisation already locked
     * to 2062 by the old bug, with nothing posted, sets its books start back to
     * 2026-01-01 — the implied lock follows (2025-12-31), and a lock through a past
     * date then works. Both directions move freely while there are no journals.
     */
    @Test
    void withNoJournalsTheBooksStartMovesTheImpliedLockBothWays() {
        today("2026-09-28T08:00:00Z");
        TenantFiscalSettings stuck = settings(1, LocalDate.of(2062, 9, 30));
        stuck.setBooksStartDate(LocalDate.of(2062, 10, 1));
        when(repo.findById(tenant)).thenReturn(Optional.of(stuck));
        when(journals.existsByTenantId(tenant)).thenReturn(false);

        TenantFiscalSettings back = service.setBooksStartDate(LocalDate.of(2026, 1, 1));
        assertThat(back.getBooksLockedThrough()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(service.lockThroughAsUser(LocalDate.of(2026, 9, 27)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 9, 27));
        // Forward again (still nothing posted): the lock follows the start, but never
        // past yesterday (R4 money4 F3, review I2).
        assertThat(service.setBooksStartDate(LocalDate.of(2026, 9, 1)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(service.setBooksStartDate(LocalDate.of(2026, 10, 1)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 9, 27));
        // And a user lock may move back while nothing is posted.
        assertThat(service.lockThroughAsUser(LocalDate.of(2026, 3, 31)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 3, 31));
    }

    /** Break-it R3 money3 N1: once journals exist the lock only moves forward, with a coded, readable refusal. */
    @Test
    void onceJournalsExistTheLockCannotMoveBack() {
        today("2026-09-28T08:00:00Z");
        when(journals.existsByTenantId(tenant)).thenReturn(true);
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 6, 30))));
        assertThatThrownBy(() -> service.lockThroughAsUser(LocalDate.of(2026, 3, 31)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot move backwards (currently 30/06/2026)")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("fiscal.lockBackwards"));
        TenantFiscalSettings s = service.setBooksStartDate(LocalDate.of(2026, 1, 1));
        assertThat(s.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    /**
     * Break-it R4 money4 F3: an organisation with a posted JV and no lock set its
     * books start to 28/12/2026 (inside the +3 months rule) and got a lock through
     * 27/12/2026 — every deposit, clearing and receipt (dated today or earlier) was
     * refused for three months, and the lock could not be taken back. The implied
     * lock is never after today.
     */
    @Test
    void withJournalsAFutureBooksStartNeverLocksPastToday() {
        today("2026-09-28T08:00:00Z");
        when(journals.existsByTenantId(tenant)).thenReturn(true);
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));

        TenantFiscalSettings s = service.setBooksStartDate(LocalDate.of(2026, 12, 28));
        assertThat(s.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    /**
     * Break-it R4 money4 F3 (the undo): a lock the books start set is the books
     * start's; moving the books start back moves it back, even with journals. A
     * lock somebody set (lock-through, a year-end close) still only moves forward.
     */
    @Test
    void movingTheBooksStartBackMovesTheLockItSetBack() {
        today("2026-09-28T08:00:00Z");
        when(journals.existsByTenantId(tenant)).thenReturn(true);
        TenantFiscalSettings row = settings(1, null);
        when(repo.findById(tenant)).thenReturn(Optional.of(row));

        assertThat(service.setBooksStartDate(LocalDate.of(2026, 9, 1)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(service.setBooksStartDate(LocalDate.of(2026, 1, 1)).getBooksLockedThrough())
                .as("the lock the books start set follows it back").isEqualTo(LocalDate.of(2025, 12, 31));

        // Once a user locks, the lock is theirs: the books start no longer moves it.
        service.lockThroughAsUser(LocalDate.of(2026, 6, 30));
        assertThat(service.setBooksStartDate(LocalDate.of(2025, 6, 1)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 6, 30));
    }

    /**
     * Review of R4-B, I2: the lock is inclusive ({@code assertOpen} refuses a date not
     * after it), so a lock through today refused today's receipts and deposits — which
     * the fiscal page's copy says can still be recorded. The implied lock stops at
     * yesterday (Dubai business date).
     */
    @Test
    void aFutureBooksStartLeavesTodayOpenForPosting() {
        today("2026-09-28T21:30:00Z");   // 29/09 01:30 in Dubai, still 28/09 in UTC
        when(journals.existsByTenantId(tenant)).thenReturn(true);
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));

        TenantFiscalSettings s = service.setBooksStartDate(LocalDate.of(2026, 12, 28));
        assertThat(s.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(service.isOpen(LocalDate.of(2026, 9, 29))).as("today (Dubai) stays open").isTrue();
        service.assertOpen(LocalDate.of(2026, 9, 29));
    }

    @Test
    void fiscalYearStartMonthMustBeACalendarMonth() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        assertThatThrownBy(() -> service.setFiscalYearStartMonth(0))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.setFiscalYearStartMonth(13))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(service.setFiscalYearStartMonth(1).getFiscalYearStartMonth()).isEqualTo(1);
        assertThat(service.setFiscalYearStartMonth(12).getFiscalYearStartMonth()).isEqualTo(12);
    }

    @Test
    void lockThroughCannotMoveBackwards() {
        when(journals.existsByTenantId(tenant)).thenReturn(true);
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        assertThatThrownBy(() -> service.lockThrough(LocalDate.of(2026, 7, 31)))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(service.lockThrough(LocalDate.of(2026, 9, 30)).getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    /** Break-it R2 money2 F3: a user cannot lock a period that has not happened (2062 for 2026). */
    @Test
    void aUserLockCannotBeAfterToday() {
        service.setManualPostingDates(new ManualPostingDates(java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-28T08:00:00Z"), ManualPostingDates.BUSINESS_ZONE)));
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 6, 30))));
        assertThatThrownBy(() -> service.lockThroughAsUser(LocalDate.of(2062, 9, 30)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("period lock date 30/09/2062 is in the future");
        assertThatThrownBy(() -> service.lockThroughAsUser(LocalDate.of(2026, 9, 29)))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(service.lockThroughAsUser(LocalDate.of(2026, 9, 28)).getBooksLockedThrough())
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }

    /** Break-it R2 money2 review N2: the OB journal is dated the day before the books start, so that day must be postable. */
    @Test
    void theBooksCannotStartBeforeTheSecondOfJanuary2000() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        assertThatThrownBy(() -> service.setBooksStartDate(LocalDate.of(2000, 1, 1)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot start on 2000-01-01")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("fiscal.booksStartOutOfRange"));
        assertThat(service.setBooksStartDate(LocalDate.of(2000, 1, 2)).getBooksStartDate()).isEqualTo(LocalDate.of(2000, 1, 2));
    }
}
