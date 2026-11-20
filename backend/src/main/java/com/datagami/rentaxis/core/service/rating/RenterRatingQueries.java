package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.config.AppTimeZone;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Native, tenant-scoped reads behind {@link RenterHistoryLoader}: one method per source, each a single
 * statement for the whole batch. Every statement carries an explicit {@code tenant_id} predicate (the
 * Hibernate tenant filter does not apply to native SQL) and returns raw {@code Object[]} rows.
 *
 * <p>Each lease-derived query reaches leases through a sub-select of the renters' leases (same tenant), so
 * no statement carries an unbounded id list and the sources always agree with the loaded leases.</p>
 */
@Component
class RenterRatingQueries {

    /** The renters' leases, tenant-scoped; binds :tenantId and :renterIds. */
    private static final String RENTER_LEASE_IDS =
            "(select ls.id from leases ls where ls.tenant_id = :tenantId and ls.renter_id in (:renterIds))";

    /**
     * A penalty's scored date: its incident date, else the day it was approved, else the day it was proposed,
     * each as the app's zone sees the instant (the DB session runs in UTC). Used in the select and the filter.
     */
    private static final String PENALTY_DATE = "coalesce(p.incident_date, "
            + "cast(p.approved_at at time zone '" + AppTimeZone.DEFAULT.getId() + "' as date), "
            + "cast(p.proposed_at at time zone '" + AppTimeZone.DEFAULT.getId() + "' as date))";

    @PersistenceContext
    private EntityManager em;

    /** id, renter_id, status, start_date, end_date, renewed_from_lease_id, notice_date. Whole history, not date-bounded. */
    List<Object[]> leases(UUID tenantId, Collection<UUID> renterIds) {
        return rows("select l.id, l.renter_id, l.status, l.start_date, l.end_date, l.renewed_from_lease_id, l.notice_date "
                        + "from leases l where l.tenant_id = :tenantId and l.renter_id in (:renterIds)",
                tenantId, renterIds, null);
    }

    /**
     * id, renter_id, lease_id, status, cheque_date, cleared (coalesce cleared_at, imported_cleared_on),
     * bounced_at, imported_bounced_on, failure_reason.
     *
     * <p>Bound: a cheque is loaded when its own date is in the window (it counts toward the NEW
     * thin-history rule) or when any event date the engine scores it by (clear or bounce) is in the window.
     * That keeps an in-window on-time clear, and an old-dated cheque that cleared or bounced late.</p>
     *
     * <p>Only the renter's own instalments are scored. Collection rows the system adds for a charge through
     * {@code ChequeService.addCollectionRow} are left out: an approved fine's row
     * ({@code penalty_assessment_id} set, also on a write-off-restored fine), a settlement's balance-due row
     * ({@code lease_settlements.collection_cheque_id}) and a write-off reversal's restored row, which has no
     * link column and is recognised by BadDebtService's narration "Bad debt write-off reversed".</p>
     *
     * <p>A transferred copy of such a row is left out too (round 3 M-c): LeaseTransferService copies each open
     * row onto the successor lease and names the original in {@code transferred_from_id}, but the copy does not
     * carry {@code penalty_assessment_id}. The copy is excluded when its original is a charge row by the same
     * three tests.</p>
     */
    List<Object[]> cheques(UUID tenantId, Collection<UUID> renterIds, LocalDate since) {
        return rows("select c.id, c.renter_id, c.lease_id, c.status, c.cheque_date, "
                        + "coalesce(c.cleared_at, c.imported_cleared_on), c.bounced_at, c.imported_bounced_on, "
                        + "c.failure_reason from cheques c "
                        + "where c.tenant_id = :tenantId and c.renter_id in (:renterIds) "
                        + "and c.penalty_assessment_id is null "
                        + "and not exists (select 1 from lease_settlements s where s.tenant_id = :tenantId "
                        + "and s.collection_cheque_id = c.id) "
                        + "and coalesce(c.narration, '') not like 'Bad debt write-off reversed%' "
                        + "and not exists (select 1 from cheques o where o.tenant_id = :tenantId "
                        + "and o.id = c.transferred_from_id and (o.penalty_assessment_id is not null "
                        + "or coalesce(o.narration, '') like 'Bad debt write-off reversed%' "
                        + "or exists (select 1 from lease_settlements os where os.tenant_id = :tenantId "
                        + "and os.collection_cheque_id = o.id))) and ("
                        + "c.cheque_date >= :since or c.cleared_at >= :since or c.imported_cleared_on >= :since "
                        + "or c.bounced_at >= :since or c.imported_bounced_on >= :since)",
                tenantId, renterIds, since);
    }

    /**
     * id, renter_id, lease_id, reason, status, scored date ({@link #PENALTY_DATE}: incident date, else
     * approval, else proposal), linked cheque id (only if that cheque exists in this tenant).
     *
     * <p>The linked cheque is {@code cheque_id}: the cheque-return fine the system proposes on a bounce
     * (PenaltyRuleEngine.onBounce -> PenaltyAssessmentService.proposeBySystem) and a fine raised by hand
     * against a cheque both carry it there, and leave source_type/source_id null. No code path records a
     * cheque as {@code source_type}/{@code source_id} (those are JOURNAL, BOOKING, HOLDOVER, TICKET).</p>
     */
    List<Object[]> penalties(UUID tenantId, Collection<UUID> renterIds, LocalDate since) {
        return rows("select p.id, p.renter_id, p.lease_id, p.reason, p.status, " + PENALTY_DATE + ", sc.id "
                        + "from penalty_assessments p "
                        + "left join cheques sc on sc.id = p.cheque_id and sc.tenant_id = :tenantId "
                        + "where p.tenant_id = :tenantId and p.renter_id in (:renterIds) and " + PENALTY_DATE
                        + " >= :since",
                tenantId, renterIds, since);
    }

    /** id, renter (coalesce write-off renter, lease renter), lease_id, status, write_off_date. */
    List<Object[]> writeOffs(UUID tenantId, Collection<UUID> renterIds, LocalDate since) {
        return rows("select w.id, coalesce(w.renter_id, l.renter_id), w.lease_id, w.status, w.write_off_date "
                        + "from bad_debt_write_offs w "
                        + "join leases l on l.id = w.lease_id and l.tenant_id = :tenantId "
                        + "where w.tenant_id = :tenantId and coalesce(w.renter_id, l.renter_id) in (:renterIds) "
                        + "and w.write_off_date >= :since",
                tenantId, renterIds, since);
    }

    /**
     * lease_id, previous_state, new_state, created_at. The caller passes the window start minus the
     * long-hold margin, so a hold that began just before the window is still seen.
     */
    List<Object[]> leaseEvents(UUID tenantId, Collection<UUID> renterIds, Instant since) {
        return rowsInstant("select e.lease_id, e.previous_state, e.new_state, e.created_at from lease_events e "
                + "where e.tenant_id = :tenantId and e.lease_id in " + RENTER_LEASE_IDS
                + " and e.created_at >= :since", tenantId, renterIds, since);
    }

    /** id, lease_id, intent, intent_captured_at, outcome, closed_at; in window by either engine date. */
    List<Object[]> opportunities(UUID tenantId, Collection<UUID> renterIds, Instant since) {
        return rowsInstant("select o.id, o.lease_id, o.intent, o.intent_captured_at, o.outcome, o.closed_at "
                + "from renewal_opportunities o where o.tenant_id = :tenantId and o.lease_id in " + RENTER_LEASE_IDS
                + " and (o.intent_captured_at >= :since or o.closed_at >= :since)", tenantId, renterIds, since);
    }

    /** id, lease_id, status, slot_start. */
    List<Object[]> meetings(UUID tenantId, Collection<UUID> renterIds, Instant since) {
        return rowsInstant("select m.id, m.lease_id, m.status, m.slot_start from meetings m "
                + "where m.tenant_id = :tenantId and m.lease_id in " + RENTER_LEASE_IDS
                + " and m.slot_start >= :since", tenantId, renterIds, since);
    }

    /** Distinct renters with at least one started lease, after the keyset, ordered by id. */
    List<UUID> ratedRenterIds(UUID tenantId, UUID afterId, int limit) {
        String sql = "select l.renter_id from leases l where l.tenant_id = :tenantId "
                + "and l.status not in ('DRAFT', 'PENDING_SIGNATURE') "
                + (afterId != null ? "and l.renter_id > :afterId " : "")
                + "group by l.renter_id order by l.renter_id limit :lim";
        var q = em.createNativeQuery(sql, UUID.class).setParameter("tenantId", tenantId).setParameter("lim", limit);
        if (afterId != null) q.setParameter("afterId", afterId);
        @SuppressWarnings("unchecked")
        List<UUID> out = q.getResultList();
        return out;
    }

    private List<Object[]> rows(String sql, UUID tenantId, Collection<UUID> renterIds, LocalDate since) {
        var q = em.createNativeQuery(sql, Object[].class).setParameter("tenantId", tenantId)
                .setParameter("renterIds", renterIds);
        if (since != null) q.setParameter("since", since);
        @SuppressWarnings("unchecked")
        List<Object[]> out = q.getResultList();
        return out;
    }

    private List<Object[]> rowsInstant(String sql, UUID tenantId, Collection<UUID> renterIds, Instant since) {
        var q = em.createNativeQuery(sql, Object[].class).setParameter("tenantId", tenantId)
                .setParameter("renterIds", renterIds).setParameter("since", since);
        @SuppressWarnings("unchecked")
        List<Object[]> out = q.getResultList();
        return out;
    }
}
