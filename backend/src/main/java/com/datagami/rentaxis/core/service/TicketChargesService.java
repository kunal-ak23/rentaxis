package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F14-49: a maintenance ticket's money — the vendor bill(s) that paid for the work
 * (purchase invoices linked to the ticket) and the recharge to the renter (a
 * MAINTENANCE_RECHARGE charge on the ticket's lease, source TICKET). Nothing is
 * recharged automatically: the recharge is proposed from the ticket, defaulting to
 * the bills' net amount, and approved in the penalties queue like any charge (VAT
 * per F14-30). Native SQL binds {@code tenant_id}.
 */
@Service
public class TicketChargesService {

    public static final String SOURCE = "TICKET";

    public record Bill(UUID voucherId, String voucherNumber, String invoiceNumber, LocalDate date, String vendor,
                       String vendorAr, BigDecimal net, BigDecimal vat, String status) { }

    public record TicketCharges(UUID ticketId, String reference, UUID leaseId, List<Bill> bills, List<Bill> candidates,
                                BigDecimal billsNet, List<PenaltyAssessmentDTO> recharges) { }

    public record RechargeRequest(BigDecimal amount, Boolean vatable, String description) { }

    private static final EnumSet<LeaseStatus> LIVE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN,
            LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    private final MaintenanceTicketRepository tickets;
    private final LeaseRepository leases;
    private final PropertyScope scope;
    private final NamedParameterJdbcTemplate jdbc;
    private final PenaltyAssessmentService charges;

    public TicketChargesService(MaintenanceTicketRepository tickets, LeaseRepository leases, PropertyScope scope,
                                NamedParameterJdbcTemplate jdbc, PenaltyAssessmentService charges) {
        this.tickets = tickets;
        this.leases = leases;
        this.scope = scope;
        this.jdbc = jdbc;
        this.charges = charges;
    }

    @Transactional(readOnly = true)
    public TicketCharges get(UUID ticketId) {
        MaintenanceTicket t = ticket(ticketId);
        List<Bill> bills = bills(t, true);
        BigDecimal net = bills.stream().filter(b -> "POSTED".equals(b.status())).map(Bill::net)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Lease lease = leaseOf(t);
        return new TicketCharges(t.getId(), t.getReference(), lease == null ? null : lease.getId(), bills, bills(t, false),
                net, charges.forSource(SOURCE, t.getId()));
    }

    /** Links a purchase invoice to the ticket (one ticket per invoice). */
    @Transactional
    public TicketCharges link(UUID ticketId, UUID voucherId) {
        MaintenanceTicket t = ticket(ticketId);
        int n = jdbc.update("""
                update vouchers set maintenance_ticket_id = :ticket
                where tenant_id = :t and id = :v and doc_type = 'PISR' and status = 'POSTED'
                  and (maintenance_ticket_id is null or maintenance_ticket_id = :ticket)
                  and (property_id = :property or exists (select 1 from voucher_lines x
                        where x.voucher_id = vouchers.id and x.property_id = :property))
                """, params().addValue("ticket", t.getId()).addValue("v", voucherId)
                .addValue("property", t.getProperty() == null ? new UUID(0, 0) : t.getProperty().getId()));
        if (n == 0) {
            throw new BusinessRuleViolationException("Only a posted purchase invoice not linked to another ticket can be"
                    + " linked.", "ticket.billNotLinkable", Map.of());
        }
        return get(ticketId);
    }

    @Transactional
    public TicketCharges unlink(UUID ticketId, UUID voucherId) {
        MaintenanceTicket t = ticket(ticketId);
        jdbc.update("update vouchers set maintenance_ticket_id = null where tenant_id = :t and id = :v and maintenance_ticket_id = :ticket",
                params().addValue("ticket", t.getId()).addValue("v", voucherId));
        return get(ticketId);
    }

    /** "Recharge to renter": a MAINTENANCE_RECHARGE charge on the ticket's lease, referencing the ticket. */
    @Transactional
    public TicketCharges recharge(UUID ticketId, RechargeRequest r) {
        MaintenanceTicket t = ticket(ticketId);
        Lease lease = leaseOf(t);
        if (lease == null) {
            throw new BusinessRuleViolationException("This ticket's unit has no live lease to recharge.",
                    "ticket.noLease", Map.of());
        }
        TicketCharges now = get(ticketId);
        BigDecimal amount = r != null && r.amount() != null ? r.amount() : now.billsNet();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleViolationException("Enter the amount to recharge.", "ticket.rechargeAmount", Map.of());
        }
        String ref = t.getReference() != null ? t.getReference() : t.getId().toString().substring(0, 8);
        String description = "Maintenance recharge – ticket " + ref + (t.getTitle() == null ? "" : ": " + t.getTitle())
                + (r != null && r.description() != null && !r.description().isBlank() ? " – " + r.description().trim() : "");
        charges.proposeFromSource(new ProposePenaltyRequest(lease.getId(), null, PenaltyReason.MAINTENANCE_RECHARGE,
                amount, description, null, r == null ? null : r.vatable()), currentUserId(), SOURCE, t.getId(), false);
        return get(ticketId);
    }

    // ------------------------------------------------------------------ helpers

    private MaintenanceTicket ticket(UUID id) {
        MaintenanceTicket t = tickets.findById(id).orElseThrow(() -> new NotFoundException("Ticket not found"));
        if (!Objects.equals(t.getTenantId(), TenantContextHolder.getTenantId())) throw new NotFoundException("Ticket not found");
        if (t.getProperty() != null) scope.requireCanAccessProperty(t.getProperty().getId(), "Ticket not found");
        return t;
    }

    /** The ticket's own lease, else the unit's live lease. */
    private Lease leaseOf(MaintenanceTicket t) {
        if (t.getLease() != null) return t.getLease();
        if (t.getUnit() == null) return null;
        return leases.findByUnitIdAndStatusIn(t.getUnit().getId(), LIVE).stream()
                .filter(l -> Objects.equals(l.getTenantId(), t.getTenantId()))
                .findFirst().orElse(null);
    }

    /** linked: the ticket's bills; otherwise posted, unlinked purchase invoices of the ticket's property. */
    private List<Bill> bills(MaintenanceTicket t, boolean linked) {
        MapSqlParameterSource p = params().addValue("ticket", t.getId())
                .addValue("property", t.getProperty() == null ? new UUID(0, 0) : t.getProperty().getId());
        String where = linked ? "v.maintenance_ticket_id = :ticket"
                : """
                  v.maintenance_ticket_id is null and v.doc_type = 'PISR' and v.status = 'POSTED'
                  and (v.property_id = :property or exists (select 1 from voucher_lines x
                        where x.voucher_id = v.id and x.property_id = :property))
                  """;
        return jdbc.query("""
                select v.id, v.voucher_number, v.invoice_number, v.doc_date, v.status, vd.name_en, vd.name_ar,
                       coalesce((select sum(l.amount) from voucher_lines l where l.voucher_id = v.id), 0) as net,
                       coalesce((select sum(l.vat_amount) from voucher_lines l where l.voucher_id = v.id), 0) as vat
                from vouchers v left join vendors vd on vd.id = v.vendor_id
                where v.tenant_id = :t and\s""" + where + " order by v.doc_date desc limit 50", p, (rs, i) -> new Bill(rs.getObject("id", UUID.class), rs.getString("voucher_number"),
                rs.getString("invoice_number"), rs.getObject("doc_date", LocalDate.class), rs.getString("name_en"),
                rs.getString("name_ar"), rs.getBigDecimal("net"), rs.getBigDecimal("vat"), rs.getString("status")));
    }

    private static MapSqlParameterSource params() {
        return new MapSqlParameterSource("t", TenantContextHolder.getTenantId());
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
