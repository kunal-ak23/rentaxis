package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
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
    TenantFiscalSettingsService service = new TenantFiscalSettingsService(repo, openingBalances, journals);
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
    void setBooksStartDateClosesEverythingBeforeItButLeavesAnExistingLockAlone() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        TenantFiscalSettings opened = service.setBooksStartDate(LocalDate.of(2026, 4, 1));
        assertThat(opened.getBooksStartDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(opened.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 3, 31));

        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        TenantFiscalSettings alreadyLocked = service.setBooksStartDate(LocalDate.of(2026, 4, 1));
        assertThat(alreadyLocked.getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 8, 31));
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
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        assertThatThrownBy(() -> service.lockThrough(LocalDate.of(2026, 7, 31)))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(service.lockThrough(LocalDate.of(2026, 9, 30)).getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
