package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-14: saving a draft wiped its cheque grid on every save, even when nothing
 * the grid was cut from had changed, so the cheque numbers and banks the user
 * had typed were lost. The grid now goes only when the money or the schedule
 * changes.
 */
@SpringBootTest
class DraftSaveKeepsChequesIT extends AbstractPostgresIT {

    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID draftWithGrid() {
        UUID id = leaseService.createDraftLease(fixtures.draftDto(START, END, List.of(
                line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")))).getId();
        cheques.generate(id, new GenerateChequesRequest(4, null, null, "Emirates NBD", null, null, null));
        assertThat(draftCheques(id)).isEqualTo(4);
        return id;
    }

    private int draftCheques(UUID leaseId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from cheques where lease_id = ? and status = 'DRAFT'", Integer.class, leaseId);
        return n == null ? 0 : n;
    }

    private static LeaseLineInput narrated(String code, String gross, String narration) {
        return new LeaseLineInput(null, code, new BigDecimal(gross), BigDecimal.ZERO,
                narration, null, null, null, null);
    }

    @Test
    void aSaveThatLeavesTheMoneyAloneKeepsTheGrid() {
        UUID id = draftWithGrid();

        CreateLeaseDTO same = fixtures.draftDto(START, END, List.of(
                narrated("RENT", "51000.00", "Yearly rent, as agreed"),
                line("SECURITY_DEPOSIT", "3000")));
        same.setEjariNumber("EJ-QA14");
        leaseService.updateDraftLease(id, same);

        assertThat(draftCheques(id)).isEqualTo(4);
    }

    /** Review on #477: a kept grid must not print a renter name corrected since. */
    @Test
    void aKeptGridTakesTheRenterNameCorrectedSince() {
        UUID id = draftWithGrid();
        jdbc.update("update renters set name_en = 'CORRECTED NAME' where id = "
                + "(select renter_id from leases where id = ?)", id);

        leaseService.updateDraftLease(id, fixtures.draftDto(START, END, List.of(
                line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000"))));

        assertThat(draftCheques(id)).isEqualTo(4);
        assertThat(jdbc.queryForList("select distinct payer_name from cheques where lease_id = ?",
                String.class, id)).containsExactly("CORRECTED NAME");
    }

    @Test
    void aChangedAmountStillDropsTheGrid() {
        UUID id = draftWithGrid();

        leaseService.updateDraftLease(id, fixtures.draftDto(START, END, List.of(
                line("RENT", "52000"), line("SECURITY_DEPOSIT", "3000"))));

        assertThat(draftCheques(id)).isZero();
    }

    @Test
    void aChangedTermStillDropsTheGrid() {
        UUID id = draftWithGrid();

        leaseService.updateDraftLease(id, fixtures.draftDto(START.plusMonths(1), END.plusMonths(1), List.of(
                line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000"))));

        assertThat(draftCheques(id)).isZero();
    }

    @Test
    void aRemovedLineStillDropsTheGrid() {
        UUID id = draftWithGrid();

        leaseService.updateDraftLease(id, fixtures.draftDto(START, END, List.of(line("RENT", "51000"))));

        assertThat(draftCheques(id)).isZero();
    }
}
