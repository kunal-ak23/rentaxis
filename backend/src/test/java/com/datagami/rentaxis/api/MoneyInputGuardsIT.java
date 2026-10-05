package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.lease.LeaseTransferService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 1 (money), asserted over HTTP: every money form the adversarial
 * pass broke answers a 400 with a sentence, writes nothing, and never rounds.
 *
 * <ul>
 *   <li>F1 — three decimals are refused, not rounded to two.</li>
 *   <li>F2 — less than one fil is refused (a renewal or transfer never drafts a zero rent).</li>
 *   <li>F3 — an amount the columns cannot hold is "too large", a 400, not a 409 conflict.</li>
 *   <li>F4 — a manual journal-type posting dated more than a year ahead is refused.</li>
 *   <li>F5 — an amendment that changes nothing posts nothing.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MoneyInputGuardsIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGenerationService;
    @Autowired LeasePostingService leasePostingService;
    @Autowired LeaseRenewalService leaseRenewalService;
    @Autowired LeaseTransferService leaseTransferService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    private User accountant;
    private User tenantAdmin;
    private String bankId;
    private String capitalId;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGenerationService, leasePostingService);
        leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();
        bankId = accountService.createLeaf("ENBD Main", accountService.getAccountByCode("A-02-02"), null)
                .getId().toString();
        capitalId = accountService.getAccountByCode("F-01").getId().toString();

        User u = new User();
        u.setEmail("accountant-" + UUID.randomUUID() + "@t.io");
        u.setName("ACCOUNTANT");
        u.setRole(UserRole.ACCOUNTANT);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        accountant = userRepo.save(u);

        User ta = new User();
        ta.setEmail("ta-" + UUID.randomUUID() + "@t.io");
        ta.setName("TENANT_ADMIN");
        ta.setRole(UserRole.TENANT_ADMIN);
        ta.setStatus(UserStatus.ACTIVE);
        ta.setPasswordHash("x");
        ta.setTenantId(fixtures.tenantId());
        tenantAdmin = userRepo.save(ta);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(HttpMethod method, String path, Object body) {
        return callAs(accountant, method, path, body);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> callAs(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(status -> true, (request, response) -> { }).toEntity(Map.class);
    }

    private long journalCount() {
        Long n = jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
        return n == null ? 0 : n;
    }

    private long chequeCount() {
        Long n = jdbc.queryForObject("select count(*) from cheques where lease_id = ?", Long.class, leaseId);
        return n == null ? 0 : n;
    }

    @SuppressWarnings("rawtypes")
    private static void assertRefused(ResponseEntity<Map> res, String... fragments) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(400);
        String message = String.valueOf(res.getBody().get("message"));
        for (String f : fragments) {
            assertThat(message).containsIgnoringCase(f);
        }
        assertThat(message).doesNotContain("ck_").doesNotContain("conflicts with existing");
    }

    private Map<String, Object> cashReceipt(Object amount, String date) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("chequeDate", date);
        body.put("postingDate", date);
        body.put("mode", "CASH");
        body.put("narration", "Counter receipt");
        return body;
    }

    private String receiptPath() {
        return "/api/v1/cheques/lease/" + leaseId + "/cash-receipt";
    }

    // ---- F1 / F2 / F3: cash receipt --------------------------------------------

    @Test
    void aCashReceiptWithThreeDecimalsIsRefusedNotRounded() {
        long rows = chequeCount();
        long journals = journalCount();
        assertRefused(call(HttpMethod.POST, receiptPath(), cashReceipt(new BigDecimal("1000.555"), "2026-09-20")),
                "2 decimal places");
        assertThat(chequeCount()).isEqualTo(rows);
        assertThat(journalCount()).isEqualTo(journals);
    }

    @Test
    void aCashReceiptBelowOneFilOrBeyondTheColumnIsRefusedInWords() {
        long rows = chequeCount();
        assertRefused(call(HttpMethod.POST, receiptPath(), cashReceipt(new BigDecimal("0.001"), "2026-09-20")),
                "0.01");
        assertRefused(call(HttpMethod.POST, receiptPath(), cashReceipt(new BigDecimal("1E12"), "2026-09-20")),
                "too large");
        assertRefused(call(HttpMethod.POST, receiptPath(), cashReceipt(new BigDecimal("99999999999999"), "2026-09-20")),
                "too large");
        assertThat(chequeCount()).isEqualTo(rows);
    }

    // ---- F4: manual dates ------------------------------------------------------

    @Test
    void aCashReceiptDatedMoreThanAYearAheadIsRefused() {
        String tooFar = LocalDate.now(ZoneId.of("Asia/Dubai")).plusYears(1).plusDays(1).toString();
        long rows = chequeCount();
        assertRefused(call(HttpMethod.POST, receiptPath(), cashReceipt(new BigDecimal("100"), tooFar)),
                "more than a year");
        assertThat(chequeCount()).isEqualTo(rows);
    }

    private Map<String, Object> jv(String date, Object amount) {
        return Map.of("entryDate", date, "narration", "BRK", "lines", List.of(
                Map.of("accountId", bankId, "debit", amount, "credit", 0),
                Map.of("accountId", capitalId, "debit", 0, "credit", amount)));
    }

    @Test
    void aJournalVoucherDatedAHundredYearsAheadIsRefusedNotAConflict() {
        long journals = journalCount();
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/journals", jv("2126-09-28", 5)), "more than a year");
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/journals", jv("2099-12-31", 5)), "more than a year");
        assertThat(journalCount()).isEqualTo(journals);
        // A year ahead, to the day, is still allowed.
        String edge = LocalDate.now(ZoneId.of("Asia/Dubai")).plusYears(1).toString();
        assertThat(call(HttpMethod.POST, "/api/v1/finance/journals", jv(edge, 5)).getStatusCode().value())
                .isEqualTo(201);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aJournalReversalDatedMoreThanAYearAheadIsRefused() {
        ResponseEntity<Map> posted = call(HttpMethod.POST, "/api/v1/finance/journals", jv("2026-09-20", 5));
        assertThat(posted.getStatusCode().value()).isEqualTo(201);
        String id = String.valueOf(posted.getBody().get("id"));
        long journals = journalCount();
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/journals/" + id + "/reverse",
                Map.of("date", "2126-09-20", "reason", "typo")), "more than a year");
        assertThat(journalCount()).isEqualTo(journals);
    }

    @Test
    void aJournalVoucherAmountTooLargeOrOverScaleIsA400() {
        long journals = journalCount();
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/journals", jv("2026-09-20", new BigDecimal("1E12"))),
                "too large");
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/journals", jv("2026-09-20", new BigDecimal("100.004"))),
                "2 decimal places");
        assertThat(journalCount()).isEqualTo(journals);
    }

    @Test
    void aVoucherDatedMoreThanAYearAheadIsRefused() {
        Map<String, Object> body = Map.of(
                "docType", "BPV", "docDate", "2126-09-28", "narration", "BRK",
                "paymentAccountId", bankId,
                "lines", List.of(Map.of("accountId", capitalId, "amount", 55, "shared", true)));
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/vouchers", body), "more than a year");
    }

    @Test
    void aVoucherLineBelowOneFilIsRefusedWithoutTheConstraintName() {
        Map<String, Object> body = Map.of(
                "docType", "BPV", "docDate", "2026-09-20", "narration", "BRK",
                "paymentAccountId", bankId,
                "lines", List.of(Map.of("accountId", capitalId, "amount", new BigDecimal("0.001"), "shared", true)));
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/vouchers", body), "0.01");
        Map<String, Object> huge = Map.of(
                "docType", "BPV", "docDate", "2026-09-20", "narration", "BRK",
                "paymentAccountId", bankId,
                "lines", List.of(Map.of("accountId", capitalId, "amount", new BigDecimal("1E12"), "shared", true)));
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/vouchers", huge), "too large");
    }

    // ---- F1 / F2 / F3: extend, renew, transfer ---------------------------------

    private Map<String, Object> extendBody(Object chequeAmount) {
        return Map.of(
                "newEndDate", "2027-12-31",
                "contractDate", "2027-09-20",
                "lines", List.of(Map.of("chargeTypeCode", "RENT", "grossAmount", 12000)),
                "cheques", List.of(Map.of("amount", chequeAmount, "chequeDate", "2027-10-02",
                        "chequeNumber", LeaseTestFixtures.nextChequeNumber())));
    }

    @Test
    void anExtensionChequeWithThreeDecimalsOrBelowOneFilIsRefused() {
        long journals = journalCount();
        String path = "/api/v1/leases/" + leaseId + "/extend";
        assertRefused(call(HttpMethod.POST, path, extendBody(new BigDecimal("15000.555"))), "2 decimal places");
        assertRefused(call(HttpMethod.POST, path, extendBody(new BigDecimal("0.001"))), "0.01");
        assertRefused(call(HttpMethod.POST, path, extendBody(new BigDecimal("1E12"))), "too large");
        assertThat(journalCount()).isEqualTo(journals);
    }

    private Map<String, Object> renewBody(Map<String, Object> rentChange) {
        return Map.of("startDate", "2027-10-02", "endDate", "2028-10-01",
                "carryDepositForward", false, "rentChange", rentChange);
    }

    @Test
    void aRenewalRentBelowOneFilIsRefusedAndDraftsNothing() {
        String path = "/api/v1/leases/" + leaseId + "/renew";
        assertRefused(call(HttpMethod.POST, path,
                renewBody(Map.of("mode", "AMOUNT", "newRentAmount", new BigDecimal("0.001")))), "0.01");
        assertRefused(call(HttpMethod.POST, path,
                renewBody(Map.of("mode", "AMOUNT", "newRentAmount", new BigDecimal("1E12")))), "too large");
        assertRefused(call(HttpMethod.POST, path,
                renewBody(Map.of("mode", "PERCENT", "percent", new BigDecimal("1000000")))), "rent change is too large");
        // Nothing was drafted, so the renewal is still open.
        assertThat(call(HttpMethod.POST, path, renewBody(Map.of("mode", "NONE"))).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void theRenewalServiceItselfNeverDraftsARentThatRoundsToZero() {
        RenewLeaseRequest r = new RenewLeaseRequest(null, LocalDate.of(2027, 10, 2), LocalDate.of(2028, 10, 1),
                null, false, new RenewLeaseRequest.RentChange(RenewLeaseRequest.RentChange.Mode.AMOUNT, null,
                new BigDecimal("0.004")), null, null);
        assertThatThrownBy(() -> leaseRenewalService.renew(leaseId, r))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("0.01");
    }

    @Test
    void aTransferRentBelowOneFilIsRefused() {
        Unit target = fixtures.createUnit(fixtures.property(), "102");
        Map<String, Object> body = Map.of("moveDate", "2026-12-31", "targetUnitId", target.getId().toString(),
                "rent", new BigDecimal("0.001"));
        assertRefused(call(HttpMethod.POST, "/api/v1/leases/" + leaseId + "/transfer", body), "0.01");
    }

    @Test
    void theTransferServiceItselfNeverDraftsARentThatRoundsToZero() {
        Unit target = fixtures.createUnit(fixtures.property(), "103");
        var r = new com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest(LocalDate.of(2026, 12, 31),
                target.getId(), null, null, null, null, new BigDecimal("0.004"));
        assertThatThrownBy(() -> leaseTransferService.draft(leaseId, r, leasePostingService))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("0.01");
    }

    // ---- F5: a no-op amendment --------------------------------------------------

    @Test
    void anAmendmentThatChangesNothingPostsNothing() {
        long journals = journalCount();
        List<Map<String, Object>> same = leaseService.getLines(leaseId).stream().map(l -> {
            Map<String, Object> m = new HashMap<>();
            m.put("chargeTypeId", l.chargeTypeId() == null ? null : l.chargeTypeId().toString());
            m.put("grossAmount", l.grossAmount());
            m.put("discountAmount", l.discountAmount());
            m.put("narration", l.narration());
            m.put("vatApplicable", l.vatApplicable());
            m.put("creditAccountId", l.creditAccountId() == null ? null : l.creditAccountId().toString());
            m.put("periodStart", l.periodStart() == null ? null : l.periodStart().toString());
            m.put("periodEnd", l.periodEnd() == null ? null : l.periodEnd().toString());
            return m;
        }).toList();
        String path = "/api/v1/leases/" + leaseId + "/amend-lines";
        assertRefused(call(HttpMethod.POST, path, Map.of("lines", same, "reason", "nothing")), "No changes to amend");
        assertThat(journalCount()).isEqualTo(journals);

        // A real change — 500 moved from the rent to the fee, the contract value
        // unchanged as an amendment requires — still amends: TCR + TCO.
        List<Map<String, Object>> changed = same.stream().map(m -> {
            Map<String, Object> c = new HashMap<>(m);
            BigDecimal gross = new BigDecimal(String.valueOf(c.get("grossAmount")));
            if (gross.compareTo(new BigDecimal("2000")) == 0) c.put("grossAmount", new BigDecimal("2500"));
            if (gross.compareTo(new BigDecimal("51000")) == 0) c.put("grossAmount", new BigDecimal("50500"));
            return c;
        }).toList();
        long contractJournals = journalCount("TCR", "TCO");
        assertThat(call(HttpMethod.POST, path, Map.of("lines", changed, "reason", "fee up")).getStatusCode().value())
                .isEqualTo(200);
        // Counted by type: once the contract has started (2026-10-02) the amendment also
        // catches up the days already recognised, which a total would count too.
        assertThat(journalCount("TCR", "TCO")).isEqualTo(contractJournals + 2);
    }

    private long journalCount(String... docTypes) {
        Long n = jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = any(?)",
                Long.class, fixtures.tenantId(), docTypes);
        return n == null ? 0 : n;
    }

    // ---- Batch 4 review #3: the rest of the money request bodies ------------------

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static final BigDecimal THREE_DP = new BigDecimal("1000.555");
    private static final BigDecimal TOO_BIG = new BigDecimal("1E12");
    private static final BigDecimal BELOW_FIL = new BigDecimal("0.001");

    @Test
    void payablesBodiesAreMoneyChecked() {
        String any = UUID.randomUUID().toString();
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/voucher-allocations",
                m("paymentId", any, "invoiceId", any, "amount", THREE_DP)), "2 decimal places");
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/ap-opening-items",
                m("vendorId", any, "invoiceNumber", "INV-1", "invoiceDate", "2026-09-01", "amount", TOO_BIG)), "too large");
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/issued-cheques/opening",
                m("vendorId", any, "bankAccountId", any, "chequeNumber", "1", "chequeDate", "2026-09-01",
                        "amount", BELOW_FIL)), "0.01");
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/payment-runs",
                m("paymentDate", "2026-09-20", "paymentAccountId", bankId, "method", "TRANSFER",
                        "items", List.of(m("invoiceId", any, "amount", THREE_DP)))), "2 decimal places");
    }

    @Test
    void aPaymentRunDatedMoreThanAYearAheadIsRefusedBeforeItIsNumbered() {
        Long before = jdbc.queryForObject("select count(*) from payment_runs where tenant_id = ?", Long.class,
                fixtures.tenantId());
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/payment-runs",
                m("paymentDate", "2126-09-20", "paymentAccountId", bankId, "method", "TRANSFER",
                        "items", List.of(m("invoiceId", UUID.randomUUID().toString(), "amount", 100)))),
                "more than a year");
        assertThat(jdbc.queryForObject("select count(*) from payment_runs where tenant_id = ?", Long.class,
                fixtures.tenantId())).isEqualTo(before);
    }

    @Test
    void recoveryRechargeAndPenaltyReductionAreMoneyChecked() {
        String any = UUID.randomUUID().toString();
        assertRefused(callAs(tenantAdmin, HttpMethod.POST, "/api/v1/finance/bad-debts/" + any + "/recoveries",
                m("amount", THREE_DP, "date", "2026-09-20", "accountId", bankId)), "2 decimal places");
        assertRefused(call(HttpMethod.POST, "/api/v1/tickets/" + any + "/recharge", m("amount", BELOW_FIL)), "0.01");
        assertRefused(call(HttpMethod.POST, "/api/v1/penalties/" + any + "/reduce", m("amount", THREE_DP)),
                "2 decimal places");
    }

    @Test
    void bankRecOpeningBalancesSettlementRentFreeAndFinesAreMoneyChecked() {
        String any = UUID.randomUUID().toString();
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/bank-reconciliation/lines/actions/post",
                m("statementLineIds", List.of(any), "kind", "CHARGE", "net", THREE_DP, "vat", BigDecimal.ZERO)),
                "2 decimal places");
        assertRefused(call(HttpMethod.POST, "/api/v1/finance/bank-reconciliation/bank-accounts/" + any + "/opening-items",
                m("itemDate", "2026-09-01", "description", "x", "amount", TOO_BIG.negate())), "too large");
        assertRefused(call(HttpMethod.PUT, "/api/v1/finance/opening-balances/" + capitalId,
                m("debit", THREE_DP, "credit", BigDecimal.ZERO)), "2 decimal places");
        assertRefused(call(HttpMethod.POST, "/api/v1/leases/" + leaseId + "/settlement/draft",
                m("deductions", List.of(m("category", "PROPERTY_DAMAGE", "description", "x", "amount", THREE_DP)))),
                "2 decimal places");
        assertRefused(callAs(tenantAdmin, HttpMethod.PUT, "/api/v1/leases/" + leaseId + "/rent-free-periods",
                List.of(m("fromDate", "2026-10-02", "toDate", "2026-10-31", "concessionOverride", THREE_DP))),
                "2 decimal places");
        assertRefused(callAs(tenantAdmin, HttpMethod.PUT, "/api/v1/settings/fines",
                m("bounceAmount", THREE_DP, "signatureMismatchAmount", 0, "accountClosedAmount", 0,
                        "graceDays", 0, "perDayRate", 0)), "2 decimal places");
    }

    // ---- Final round: @Money's max narrowed to the actual decimal(12,2) columns ----

    @Test
    void aUnitExpectedRentPastTheColumnIs400NotAnOverflow() {
        Map<String, Object> tooBig = m("unitNumber", "OVR-1",
                "property", m("id", fixtures.property().getId().toString()),
                "expectedRent", new BigDecimal("10000000000.00"));
        assertRefused(callAs(tenantAdmin, HttpMethod.POST, "/api/v1/units", tooBig), "too large");

        Map<String, Object> atLimit = m("unitNumber", "OVR-2",
                "property", m("id", fixtures.property().getId().toString()),
                "expectedRent", new BigDecimal("9999999999.99"));
        ResponseEntity<Map> ok = callAs(tenantAdmin, HttpMethod.POST, "/api/v1/units", atLimit);
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
    }

}
