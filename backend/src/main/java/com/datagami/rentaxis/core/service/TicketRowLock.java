package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Break-it round 2 (money2) F6: the one lock that serialises a maintenance ticket's
 * money — recharging it, unlinking a bill from it, and reversing (voiding or
 * amending) a bill linked to it. Each of those changes "posted bills net" or
 * "live recharges", and a recharge is only safe against both at once.
 *
 * <p>{@code FOR NO KEY UPDATE} on the ticket row: it excludes the other holders but
 * not inserts that reference the ticket (comments, attachments take {@code FOR KEY
 * SHARE} through their foreign key). Always taken <em>before</em> any voucher row
 * lock, so recharge (ticket only), unlink (ticket → voucher) and a bill reversal
 * (ticket → voucher) all lock in the same order. Native SQL binds {@code tenant_id}.</p>
 */
@Component
public class TicketRowLock {

    private final NamedParameterJdbcTemplate jdbc;

    public TicketRowLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the ticket row until the caller's transaction ends. */
    public void lock(UUID ticketId) {
        if (ticketId == null) return;
        jdbc.queryForList("select id from maintenance_tickets where tenant_id = :t and id = :ticket for no key update",
                new MapSqlParameterSource("t", TenantContextHolder.getTenantId()).addValue("ticket", ticketId));
    }

    /** 409 code: a bill still backs a live recharge (break-it R3 money3 N3). */
    public static final String RECHARGE_LIVE = "ticket.rechargeLive";

    /**
     * Break-it R3 money3 N3: a bill cannot leave its ticket — unlinked, voided or
     * amended — while the ticket's live recharges (proposed, approved or written off)
     * would then exceed what its other posted bills cover. Unlinking a recharged bill
     * reset the per-ticket cap (the same 750 bill recharged twice on two tickets), and
     * voiding one left the renter charged for a bill that no longer existed. The
     * caller holds the ticket row lock ({@link #lock}), so no recharge can be proposed
     * in between. Native SQL binds {@code tenant_id}.
     */
    public void requireBillReleasable(UUID ticketId, UUID voucherId) {
        if (ticketId == null || voucherId == null) return;
        MapSqlParameterSource p = new MapSqlParameterSource("t", TenantContextHolder.getTenantId())
                .addValue("ticket", ticketId).addValue("v", voucherId);
        java.math.BigDecimal live = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from penalty_assessments
                where tenant_id = :t and source_type = 'TICKET' and source_id = :ticket
                  and status in ('PROPOSED', 'APPROVED', 'WRITTEN_OFF')""", p, java.math.BigDecimal.class);
        if (live == null || live.signum() <= 0) return;
        java.math.BigDecimal others = jdbc.queryForObject("""
                select coalesce(sum(l.amount), 0) from vouchers v join voucher_lines l on l.voucher_id = v.id
                where v.tenant_id = :t and v.maintenance_ticket_id = :ticket and v.id <> :v
                  and v.doc_type = 'PISR' and v.status = 'POSTED'""", p, java.math.BigDecimal.class);
        if (others != null && others.compareTo(live) >= 0) return;
        String shown = live.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        throw new com.datagami.rentaxis.api.exception.FiguresChangedException(RECHARGE_LIVE,
                "This bill backs a live recharge to the renter (" + shown + " on the ticket). Cancel the recharge first"
                        + " (waive it in the penalties queue, or reverse it if approved); nothing was changed.");
    }

    /** {@link #requireBillReleasable} for the ticket the voucher is linked to now (read under the caller's locks). */
    public void requireBillReleasable(UUID voucherId) {
        if (voucherId == null) return;
        List<UUID> ids = jdbc.queryForList(
                "select maintenance_ticket_id from vouchers where tenant_id = :t and id = :v and maintenance_ticket_id is not null",
                new MapSqlParameterSource("t", TenantContextHolder.getTenantId()).addValue("v", voucherId), UUID.class);
        if (!ids.isEmpty()) requireBillReleasable(ids.get(0), voucherId);
    }

    /**
     * Locks the ticket a voucher (a vendor bill) is linked to, if any, and returns it.
     * Read without a voucher lock, so it can be taken first; a caller that then locks
     * the voucher re-reads the link and locks again if it moved in between.
     */
    public UUID lockTicketOfVoucher(UUID voucherId) {
        if (voucherId == null) return null;
        List<UUID> ids = jdbc.queryForList(
                "select maintenance_ticket_id from vouchers where tenant_id = :t and id = :v and maintenance_ticket_id is not null",
                new MapSqlParameterSource("t", TenantContextHolder.getTenantId()).addValue("v", voucherId), UUID.class);
        UUID ticket = ids.isEmpty() ? null : ids.get(0);
        lock(ticket);
        return ticket;
    }
}
