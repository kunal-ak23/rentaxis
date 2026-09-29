package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.OrgSettingsService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PayeeCheck;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner ruling 2026-09-29: with "Check the payee name on scanned cheques" on, a
 * scanned cheque whose payee matches none of the organisation's valid names is
 * flagged; staff may still attach it, but only after confirming, and the flag and
 * who confirmed it stay on the cheque.
 *
 * <p>The payee is the one the server read off the scan at extract time (kept on the
 * issued upload row), not a value from the attach request.</p>
 */
@SpringBootTest
class ChequePayeeCheckIT extends AbstractPostgresIT {

    @Autowired ChequeDetailsService details;
    @Autowired OrgSettingsService orgSettings;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired ChequeImageUploadRepository imageUploads;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);
    private static final String VALID = "Palm Ridge Properties L.L.C.";

    private UUID tenantId;
    private UUID leaseId;
    private List<Cheque> register;
    private User clerk;

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    private record Org(UUID tenantId, UUID leaseId, List<Cheque> register, User clerk) {}

    private Org newOrg() {
        LeaseTestFixtures f = fixtures();
        UUID tenant = f.tenantId();
        UUID lease = f.postedLeaseUnnumbered(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")), 4)
                .lease().getId();
        User u = new User();
        u.setEmail("clerk-" + UUID.randomUUID() + "@test.invalid");
        u.setName("Mona Clerk");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenant);
        User saved = userRepo.save(u);
        List<Cheque> rows = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(lease));
        return new Org(tenant, lease, rows, saved);
    }

    private void use(Org org) {
        TenantContextHolder.setTenantId(org.tenantId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                org.clerk().getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    @BeforeEach
    void setUp() {
        Org org = newOrg();
        tenantId = org.tenantId();
        leaseId = org.leaseId();
        register = org.register();
        clerk = org.clerk();
        use(org);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** A scan as /cheques/extract records it, with the payee the OCR read. */
    private String scan(UUID tenant, String payee) {
        ChequeImageUpload u = new ChequeImageUpload();
        u.setTenantId(tenant);
        u.setBlobPath("cheques/" + UUID.randomUUID() + ".jpg");
        u.setImageUrl("https://blob/" + u.getBlobPath());
        u.setExtractedPayeeName(payee);
        return imageUploads.save(u).getBlobPath();
    }

    private BulkAttachChequeItem item(UUID chequeId, String number, String blobPath, Boolean confirmed) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setChequeId(chequeId);
        it.setChequeNumber(number);
        it.setChequeDate(LocalDate.of(2026, 3, 1));
        it.setBankName("Emirates NBD");
        it.setPayerName("Test Renter");
        it.setImageUrl("https://blob/x.jpg");
        it.setImageBlobPath(blobPath);
        it.setImageUploadedAt(OffsetDateTime.now());
        it.setPayeeMismatchConfirmed(confirmed);
        return it;
    }

    private Cheque reread(UUID id) {
        return tx.execute(s -> chequeRepo.findById(id).orElseThrow());
    }

    private static String num() {
        return "PY-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aMismatchIsRefusedUntilConfirmedAndNothingIsWritten() {
        orgSettings.updatePayeeCheck(true, List.of(VALID));
        UUID id = register.getFirst().getId();

        assertThatThrownBy(() -> details.bulkAttach(leaseId,
                List.of(item(id, num(), scan(tenantId, "Someone Else Real Estate"), null))))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(((BulkAttachValidationException) e).getRows())
                        .extracting(BulkAttachErrorRow::reason).containsExactly("payee_mismatch_unconfirmed"));

        Cheque after = reread(id);
        assertThat(after.getChequeNumber()).isNull();
        assertThat(after.getPayeeCheck()).isNull();
    }

    @Test
    void aConfirmedMismatchIsAttachedFlaggedAndRecordsWhoConfirmed() {
        orgSettings.updatePayeeCheck(true, List.of(VALID));
        UUID id = register.getFirst().getId();

        List<ChequeDTO> out = details.bulkAttach(leaseId,
                List.of(item(id, num(), scan(tenantId, "Someone Else Real Estate"), true)));

        Cheque after = reread(id);
        assertThat(after.getPayeeName()).isEqualTo("Someone Else Real Estate");
        assertThat(after.getPayeeCheck()).isEqualTo(PayeeCheck.MISMATCH);
        assertThat(after.getPayeeMismatchConfirmedBy()).isEqualTo(clerk.getId());
        assertThat(after.getPayeeMismatchConfirmedByName()).isEqualTo("Mona Clerk");
        assertThat(after.getPayeeMismatchConfirmedAt()).isNotNull();
        // Visible on the cheque afterwards: the register row carries it.
        assertThat(out.getFirst().payeeCheck()).isEqualTo(PayeeCheck.MISMATCH);
        assertThat(out.getFirst().payeeName()).isEqualTo("Someone Else Real Estate");
        assertThat(out.getFirst().payeeMismatchConfirmedByName()).isEqualTo("Mona Clerk");
    }

    @Test
    void theRequestCannotOverrideThePayeeTheScanSaid() {
        // BulkAttachChequeItem has no payee field at all: the check reads the
        // server's own record of the scan.
        assertThat(java.util.Arrays.stream(BulkAttachChequeItem.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("payeeName");
    }

    @Test
    void aMatchingPayeeIsNotFlaggedAndNeedsNoConfirmation() {
        orgSettings.updatePayeeCheck(true, List.of("Another Name", VALID));
        UUID id = register.getFirst().getId();

        details.bulkAttach(leaseId, List.of(item(id, num(), scan(tenantId, "PALM RIDGE PROPERTIES LLC"), null)));

        Cheque after = reread(id);
        assertThat(after.getPayeeCheck()).isEqualTo(PayeeCheck.MATCH);
        assertThat(after.getPayeeName()).isEqualTo("PALM RIDGE PROPERTIES LLC");
        assertThat(after.getPayeeMismatchConfirmedBy()).isNull();
        assertThat(after.getPayeeMismatchConfirmedAt()).isNull();
    }

    @Test
    void anUnreadablePayeeIsNotedButNeedsNoConfirmation() {
        orgSettings.updatePayeeCheck(true, List.of(VALID));
        UUID id = register.getFirst().getId();

        details.bulkAttach(leaseId, List.of(item(id, num(), scan(tenantId, null), null)));

        Cheque after = reread(id);
        assertThat(after.getPayeeCheck()).isEqualTo(PayeeCheck.UNREADABLE);
        assertThat(after.getPayeeMismatchConfirmedBy()).isNull();
    }

    @Test
    void withTheSettingOffNothingIsChecked() {
        orgSettings.updatePayeeCheck(false, List.of(VALID));
        UUID id = register.getFirst().getId();

        details.bulkAttach(leaseId, List.of(item(id, num(), scan(tenantId, "Someone Else Real Estate"), null)));

        Cheque after = reread(id);
        assertThat(after.getPayeeCheck()).isNull();
        assertThat(after.getPayeeName()).isEqualTo("Someone Else Real Estate");
    }

    @Test
    void withAnEmptyListNothingIsChecked() {
        orgSettings.updatePayeeCheck(true, List.of());
        UUID id = register.getFirst().getId();

        details.bulkAttach(leaseId, List.of(item(id, num(), scan(tenantId, "Someone Else Real Estate"), null)));

        assertThat(reread(id).getPayeeCheck()).isNull();
    }

    @Test
    void theSettingsAreThisOrganisationsOnly() {
        orgSettings.updatePayeeCheck(true, List.of(VALID));
        Org orgA = new Org(tenantId, leaseId, register, clerk);

        Org orgB = newOrg();
        use(orgB);
        // B has never configured the check: A's switch and list do not apply to it.
        assertThat(orgSettings.getPayeeCheck().enabled()).isFalse();
        assertThat(orgSettings.getPayeeCheck().validNames()).isEmpty();
        UUID bRow = orgB.register().getFirst().getId();
        details.bulkAttach(orgB.leaseId(), List.of(item(bRow, num(), scan(orgB.tenantId(), "Someone Else"), null)));
        assertThat(reread(bRow).getPayeeCheck()).isNull();

        // B turns it on with its own name: A's valid name is a mismatch in B.
        orgSettings.updatePayeeCheck(true, List.of("Beta Holdings"));
        UUID bRow2 = orgB.register().get(1).getId();
        assertThatThrownBy(() -> details.bulkAttach(orgB.leaseId(),
                List.of(item(bRow2, num(), scan(orgB.tenantId(), VALID), null))))
                .isInstanceOf(BulkAttachValidationException.class);

        // And A still has only its own list.
        use(orgA);
        assertThat(orgSettings.getPayeeCheck().validNames()).containsExactly(VALID);
        UUID aRow = register.get(1).getId();
        details.bulkAttach(leaseId, List.of(item(aRow, num(), scan(tenantId, "Palm Ridge Properties LLC"), null)));
        assertThat(reread(aRow).getPayeeCheck()).isEqualTo(PayeeCheck.MATCH);

    }

    @Test
    void theSavedListIsTrimmedAndBlankNamesDropped() {
        var saved = orgSettings.updatePayeeCheck(true, List.of("  " + VALID + "  ", "", "   ", "Second"));
        assertThat(saved.validNames()).containsExactly(VALID, "Second");
        assertThat(orgSettings.getPayeeCheck().validNames()).containsExactly(VALID, "Second");
        assertThat(orgSettings.getPayeeCheck().enabled()).isTrue();
    }
}
