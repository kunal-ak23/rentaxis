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
