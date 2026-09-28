package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-49: the vendor bill links to the ticket, "Recharge to renter" raises a
 * MAINTENANCE_RECHARGE charge on the ticket's lease (amount defaulting to the bill,
 * VAT per F14-30), and both show on the ticket. Nothing is recharged by itself.
 */
@SpringBootTest
class TicketChargesIT extends AbstractPostgresIT {

    @Autowired TicketChargesService service;
    @Autowired com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService penalties;
    @Autowired TicketRowLock ticketRowLock;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired MaintenanceTicketRepository tickets;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
    @Autowired AccountRepository accountRepo;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;

    private LeaseTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID bill(String number, String amount) {
        Vendor v = new Vendor();
        v.setNameEn("Gulf Cool " + number);
        v.setTrn("100200300400003");
        UUID vendor = vendorService.createVendor(v).getId();
        UUID repairs = accountRepo.findAll().stream()
                .filter(a -> fixtures.property().getId().equals(a.getPropertyId()) && "EXP_REPAIRS_MAINTENANCE".equals(a.getReportLine()))
                .findFirst().orElseThrow().getId();
        UUID id = vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, LocalDate.of(2026, 6, 3), vendor,
                number, "Glass door", fixtures.property().getId(), null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(repairs, "glass", new BigDecimal(amount), new BigDecimal("5"),
                        fixtures.property().getId(), null)))).getId();
        vouchers.post(id);
        return id;
    }

    private UUID ticket(Lease lease) {
        MaintenanceTicket t = new MaintenanceTicket();
        t.setTenantId(fixtures.tenantId());
        t.setProperty(fixtures.property());
        t.setUnit(lease.getUnit());
        t.setReportedBy(UUID.randomUUID());
        t.setTitle("Broken glass door");
        t.setCategory(TicketCategory.PLUMBING);
        t.setPriority(TicketPriority.MEDIUM);
        t.setReference("TKT-26/" + (tickets.count() + 4));
        return tickets.save(t).getId();
    }

    @Test
    void theBillLinksAndTheRechargeIsAChargeOnTheLeaseReferencingTheTicket() {
        Lease lease = leaseRepo.findById(fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1),
                LocalDate.of(2027, 4, 30), List.of(vatLine("RENT", "120000")), 4, null).lease().getId()).orElseThrow();
        UUID ticketId = ticket(lease);
        UUID billId = bill("GC-16", "1200.00");

        var before = service.get(ticketId);
        assertThat(before.candidates()).extracting(TicketChargesService.Bill::voucherId).contains(billId);
        assertThat(before.recharges()).isEmpty();

        var linked = service.link(ticketId, billId);
        assertThat(linked.bills()).singleElement().satisfies(b -> {
            assertThat(b.invoiceNumber()).isEqualTo("GC-16");
            assertThat(b.net()).isEqualByComparingTo("1200.00");
        });
        assertThat(linked.billsNet()).isEqualByComparingTo("1200.00");
        assertThat(linked.leaseId()).isEqualTo(lease.getId());

        // The amount defaults to the bill; VAT follows the charge type on this VAT lease.
        var charged = service.recharge(ticketId, null);
        assertThat(charged.recharges()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(PenaltyReason.MAINTENANCE_RECHARGE);
            assertThat(c.amount()).isEqualByComparingTo("1200.00");
            assertThat(c.vatable()).isTrue();
            assertThat(c.description()).contains("TKT-26/");
            assertThat(c.sourceType()).isEqualTo("TICKET");
        });
        assertThat(charged.rechargeable()).isEqualByComparingTo("0.00");
        // Break-it R2 money2 F6: the bill is recharged once over, not again.
        assertThatThrownBy(() -> service.recharge(ticketId, new TicketChargesService.RechargeRequest(new BigDecimal("300"), false, "labour")))
                .satisfies(e -> {
                    BusinessRuleViolationException b = (BusinessRuleViolationException) e;
                    assertThat(b.getCode()).isEqualTo("ticket.rechargeExceedsBill");
                    assertThat(b.getMessage()).contains("Only 0.00");
                });
        assertThat(service.get(ticketId).recharges()).hasSize(1);

        // A bill linked to one ticket cannot be linked to another.
        UUID other = ticket(lease);
        assertThatThrownBy(() -> service.link(other, billId))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("ticket.billNotLinkable"));
        assertThat(service.unlink(ticketId, billId).bills()).isEmpty();
    }

    /**
     * Break-it R2 money2 F6: Σ live recharges ≤ the bills' net. Partial recharges add
     * up to the bill; a cent more is refused naming what is left; a waived recharge
     * gives its amount back; two tabs recharging the full bill at once land once.
     */
    @Test
    void rechargesAddUpToTheBillAndNoFurther() throws Exception {
        Lease lease = leaseRepo.findById(fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1),
                LocalDate.of(2027, 4, 30), List.of(vatLine("RENT", "120000")), 4, null).lease().getId()).orElseThrow();
        UUID ticketId = ticket(lease);
        service.link(ticketId, bill("GC-750", "750.00"));
        assertThat(service.get(ticketId).rechargeable()).isEqualByComparingTo("750.00");

        var first = service.recharge(ticketId, new TicketChargesService.RechargeRequest(new BigDecimal("450"), false, "part"));
        assertThat(first.rechargeable()).isEqualByComparingTo("300.00");
        assertThatThrownBy(() -> service.recharge(ticketId, new TicketChargesService.RechargeRequest(new BigDecimal("300.01"), false, null)))
                .satisfies(e -> {
                    BusinessRuleViolationException b = (BusinessRuleViolationException) e;
                    assertThat(b.getCode()).isEqualTo("ticket.rechargeExceedsBill");
                    assertThat(b.getArgs()).containsEntry("remaining", "300.00");
                });
        // No amount = what is left, not the whole bill again.
        var rest = service.recharge(ticketId, null);
        assertThat(rest.recharges()).extracting(c -> c.amount().stripTrailingZeros().toPlainString()).containsExactly("450", "300");
        assertThat(rest.rechargeable()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> service.recharge(ticketId, null))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("ticket.rechargeExceedsBill"));

        // A waived recharge no longer counts.
        penalties.waive(first.recharges().get(0).id(), "not the renter's fault");
        assertThat(service.get(ticketId).rechargeable()).isEqualByComparingTo("450.00");

        // TK3: two tabs recharge the remaining 450 at once — exactly one lands.
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        UUID tenant = TenantContextHolder.getTenantId();
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Boolean> tab = () -> {
            TenantContextHolder.setTenantId(tenant);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                service.recharge(ticketId, new TicketChargesService.RechargeRequest(new BigDecimal("450"), false, null));
                return true;
            } catch (BusinessRuleViolationException e) {
                return false;
            } finally {
                TenantContextHolder.clear();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(tab);
            var b = pool.submit(tab);
            assertThat(List.of(a.get(30, java.util.concurrent.TimeUnit.SECONDS), b.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.get(ticketId).rechargeable()).isEqualByComparingTo("0.00");
    }

    /**
     * Break-it R2 money2 round 2: voiding a bill linked to a ticket takes the ticket
     * row lock recharge takes. Held by another transaction (a recharge in flight),
     * the void waits for it; released, the void completes and the next recharge sees
     * no bill left. Sequential proof of the lock ordering.
     */
    @Test
    void voidingALinkedBillWaitsForTheTicketLockARechargeHolds() throws Exception {
        Lease lease = leaseRepo.findById(fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1),
                LocalDate.of(2027, 4, 30), List.of(vatLine("RENT", "120000")), 4, null).lease().getId()).orElseThrow();
        UUID ticketId = ticket(lease);
        UUID billId = bill("GC-VOID", "750.00");
        service.link(ticketId, billId);

        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        UUID tenant = TenantContextHolder.getTenantId();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    tx.executeWithoutResult(st -> {
                        ticketRowLock.lock(ticketId);
                        held.countDown();
                        try {
                            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    TenantContextHolder.clear();
                }
                return null;
            });
            assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var voiding = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenant);
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                try {
                    return vouchers.voidVoucher(billId, LocalDate.now(java.time.ZoneId.of("Asia/Dubai")), "wrong bill").getStatus();
                } finally {
                    TenantContextHolder.clear();
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            });
            Thread.sleep(1500);
            assertThat(voiding.isDone()).as("the void waits for the ticket lock").isFalse();
            release.countDown();
            holder.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(voiding.get(30, java.util.concurrent.TimeUnit.SECONDS).name()).isEqualTo("VOID");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(service.get(ticketId).rechargeable()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> service.recharge(ticketId, new TicketChargesService.RechargeRequest(new BigDecimal("1"), false, null)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("ticket.rechargeExceedsBill"));
    }
}
