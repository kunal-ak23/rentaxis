package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #351 review P3-1 / concern 7: an allocation and an amend of the same payment
 * take the payment and invoice rows in opposite orders, so they can deadlock.
 * Postgres breaks the cycle (40P01); the loser rolls back and is answered 409
 * "try again" by {@code GlobalExceptionHandler.handleConcurrency}, the winner
 * completes, and nothing is over-allocated.
 *
 * <p>The cycle is forced, not hoped for. Two test connections hold the invoice
 * row (c1) and the payment row (c2). The allocation starts and blocks on the
 * invoice (it locks in id order and the invoice sorts first); the amend starts
 * and blocks on the payment. Releasing c1 lets the allocation take the invoice
 * and queue on the payment behind the amend; releasing c2 lets the amend take
 * the payment and ask for the invoice — which the allocation now holds.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VoucherAllocationDeadlockIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired VoucherService vouchers;
    @Autowired VoucherAllocationService allocations;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void anAllocationRacingAnAmendOfItsPaymentDeadlocksIntoOne409AndNoOverAllocation() throws Exception {
        LandlordOrg org = new LandlordOrg();
        org.setName("Deadlock-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        Property p = new Property();
        p.setNameEn("Marina Tower");
        p.setEmirate(Emirate.DUBAI);
        p = propertyRepo.save(p);
        Account rm = accounts.createLeaf("Repairs & Maintenance - Marina Tower", accounts.getAccountByCode("D-01"), p.getId());
        Account bank = accounts.createLeaf("Emirates Islamic - Marina Tower", accounts.getAccountByCode("A-02-02"), null);
        Vendor v = new Vendor();
        v.setNameEn("Gulf AC Services LLC");
        v.setTrn("100123456700003");
        v = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 8, 1));

        Voucher payment = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV,
                LocalDate.of(2026, 8, 15), v.getId(), null, "pay", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(v.getPayableAccount().getId(), "pay",
                        new BigDecimal("1000.00"), BigDecimal.ZERO, null, null)),
                null, null, VoucherPaymentMethod.TRANSFER, "TRF-1")).getId());
        // An invoice whose id sorts before the payment's (Postgres compares uuids byte-wise,
        // which is the lower-case hex string order), so the allocation locks it first.
        Voucher invoice = null;
        for (int n = 0; invoice == null; n++) {
            Voucher candidate = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR,
                    LocalDate.of(2026, 8, 1), v.getId(), "INV-" + n, "inv", null, null, null, null, null,
                    List.of(new VoucherService.VoucherLineInput(rm.getId(), "AC", new BigDecimal("1000.00"),
                            BigDecimal.ZERO, p.getId(), null)))).getId());
            if (candidate.getId().toString().compareTo(payment.getId().toString()) < 0) invoice = candidate;
        }
        // The payment already settles part of it, so the amend must lock the invoice too.
        allocations.allocate(payment.getId(), invoice.getId(), null, new BigDecimal("100.00"), null);

        User accountant = new User();
        accountant.setEmail("acct-" + UUID.randomUUID() + "@t.io");
        accountant.setName("Accountant");
        accountant.setRole(UserRole.ACCOUNTANT);
        accountant.setStatus(UserStatus.ACTIVE);
        accountant.setPasswordHash("x");
        accountant.setTenantId(tenantId);
        User caller = userRepo.save(accountant);
        TenantContextHolder.clear();

        String allocateBody = json.writeValueAsString(Map.of("paymentId", payment.getId().toString(),
                "invoiceId", invoice.getId().toString(), "amount", new BigDecimal("50.00")));
        String amendBody = json.writeValueAsString(Map.of("reversalDate", "2026-09-10", "reason", "reference",
                "replacement", Map.of("docType", "BPV", "docDate", "2026-08-15", "vendorId", v.getId().toString(),
                        "paymentAccountId", bank.getId().toString(), "paymentMethod", "TRANSFER",
                        "paymentReference", "TRF-2", "lines", List.of(Map.of(
                                "accountId", v.getPayableAccount().getId().toString(),
                                "amount", new BigDecimal("1000.00"), "vatRate", BigDecimal.ZERO)))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection c1 = dataSource.getConnection(); Connection c2 = dataSource.getConnection()) {
            c1.setAutoCommit(false);
            c2.setAutoCommit(false);
            lockRow(c1, invoice.getId());
            lockRow(c2, payment.getId());
            int base = waiting();

            Future<ResponseEntity<String>> allocate = pool.submit(() ->
                    call(HttpMethod.POST, "/api/v1/finance/voucher-allocations", caller, allocateBody));
            awaitWaiting(base + 1);   // the allocation waits on the invoice (c1)
            Future<ResponseEntity<String>> amend = pool.submit(() ->
                    call(HttpMethod.POST, "/api/v1/finance/vouchers/" + payment.getId() + "/amend", caller, amendBody));
            awaitWaiting(base + 2);   // the amend waits on the payment (c2)

            c1.commit();              // the allocation takes the invoice and queues on the payment
            Thread.sleep(400);
            awaitWaiting(base + 2);
            c2.commit();              // the amend takes the payment and asks for the invoice: a cycle

            List<Integer> statuses = List.of(allocate.get(60, TimeUnit.SECONDS).getStatusCode().value(),
                    amend.get(60, TimeUnit.SECONDS).getStatusCode().value());
            assertThat(statuses).as("one side broken with 409, the other completes").contains(409);
            assertThat(statuses.stream().filter(s -> s >= 200 && s < 300).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // Whoever won, the invoice is never settled beyond its gross.
        BigDecimal live = jdbc.queryForObject("select coalesce(sum(amount), 0) from voucher_allocations"
                + " where invoice_voucher_id = ? and released_on is null", BigDecimal.class, invoice.getId());
        assertThat(live).isLessThanOrEqualTo(new BigDecimal("1000.00"));
    }

    private static void lockRow(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select id from vouchers where id = ? for update")) {
            ps.setObject(1, id);
            ps.executeQuery().close();
        }
    }

    /** Backends of this database currently blocked on a lock. */
    private int waiting() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database()"
                + " and wait_event_type = 'Lock'", Integer.class);
    }

    private void awaitWaiting(int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (waiting() < n) {
            if (System.currentTimeMillis() > until) throw new AssertionError("expected " + n + " sessions waiting on a lock");
            Thread.sleep(50);
        }
    }

    private ResponseEntity<String> call(HttpMethod method, String path, User caller, String body) {
        return RestClient.builder().build().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }
}
