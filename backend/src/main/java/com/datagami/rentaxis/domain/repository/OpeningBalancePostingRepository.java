package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OpeningBalancePosting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpeningBalancePostingRepository extends JpaRepository<OpeningBalancePosting, UUID> {

    /** At most one row per tenant; the tenant filter makes this the tenant's marker. */
    Optional<OpeningBalancePosting> findFirstByOrderByCreatedAtAsc();

    /**
     * Creates the tenant's opening-balance marker if it has none, and yields to the
     * winner if two callers create it at once.
     *
     * <p>Not {@code save()}, and not a read-then-insert: the marker row is what
     * {@code OpeningBalanceService} takes its row lock on, and a lock can only be
     * taken on a row that exists. Two first posts racing would otherwise both find
     * nothing, both insert, and one would die on
     * {@code ux_opening_balance_postings_tenant} with a raw constraint violation
     * instead of the clean "already posted" refusal.</p>
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the loser <em>block</em> on the
     * winner's uncommitted row and then find it — which is exactly the
     * serialisation the lock wants, one step earlier. Same pattern, and the same
     * reasoning, as {@code TenantFiscalSettingsRepository.insertDefaultIfAbsent};
     * it deliberately joins the caller's transaction rather than running in its own,
     * so a post that rolls back leaves no marker behind.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        insert into opening_balance_postings (id, tenant_id, as_of, created_at)
        values (gen_random_uuid(), :tenantId, :asOf, now())
        on conflict (tenant_id) do nothing
        """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("asOf") LocalDate asOf);
}
