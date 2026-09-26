package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.repository.ImportBatchEntityRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * S16-10 (PR #369 R1 P2-2): whether a cut-over workbook may attach a contract to a
 * renter the organisation already holds — the one rule the validator and the persist
 * phase both ask, so the renter the validator approved is the renter persist attaches.
 *
 * <p><b>Only an unambiguous match.</b> {@code renters.email} is not unique (a group's
 * {@code accounts@} address, a family's shared inbox), so the email alone never picks a
 * renter:</p>
 * <ul>
 *   <li>exactly one renter of the organisation holds the email — compared trimmed and
 *       case-insensitive;</li>
 *   <li>and the workbook names them: the Renters sheet row's Name, or for a renter named
 *       only on Contracts, that sheet's optional {@code RenterName} column — compared
 *       with case, repeated and surrounding spaces ignored;</li>
 *   <li>and they were made outside any import or by a POSTED batch (a DRAFT batch is the
 *       same data loaded twice; a REVERSED one may yet be discarded).</li>
 * </ul>
 * <p>Anything else is refused with the row's error — a second record is never created
 * silently, and a contract is never attached to a guess.</p>
 */
@Component
public class CutoverRenterMatcher {

    /**
     * @param renterId the renter to attach, when {@code problem} is null
     * @param problem  why the existing renter may not be used, phrased for the row
     * @param nameProblem the problem is the name (the row names somebody else), not the email
     */
    public record Match(UUID renterId, String name, String problem, boolean nameProblem) {
        public Match(UUID renterId, String name, String problem) {
            this(renterId, name, problem, false);
        }

        public boolean reusable() {
            return problem == null;
        }
    }

    private final RenterRepository renters;
    private final ImportBatchEntityRepository batchEntities;
    private final ImportBatchRepository batches;

    public CutoverRenterMatcher(RenterRepository renters, ImportBatchEntityRepository batchEntities,
                                ImportBatchRepository batches) {
        this.renters = renters;
        this.batchEntities = batchEntities;
        this.batches = batches;
    }

    /**
     * The organisation's renter for this email, or null when it has none.
     *
     * @param sheetName the name the workbook gives this renter; blank when none is given
     */
    public Match match(UUID tenantId, String email, String sheetName) {
        if (tenantId == null) throw new IllegalStateException("Renter match without a tenant");
        if (email == null || email.isBlank()) return null;
        List<Renter> held = renters.findByTenantIdAndEmailNormalised(tenantId, email.trim());
        if (held.isEmpty()) return null;
        String shown = email.trim();
        if (held.size() > 1) {
            return new Match(null, null, held.size() + " renters of this organisation share the email '" + shown + "' ("
                    + held.stream().map(Renter::getNameEn).collect(Collectors.joining(", "))
                    + "), so the contract cannot be attached to one of them; give this renter an email of their own.");
        }
        Renter r = held.get(0);
        com.datagami.rentaxis.domain.entity.ImportBatch made = madeBy(tenantId, r.getId());
        if (made != null && made.getStatus() != ImportBatchStatus.POSTED) {
            return new Match(null, r.getNameEn(), "A renter with email '" + shown + "' already exists in this organisation ("
                    + "import batch '" + made.getLabel() + "', " + made.getStatus()
                    + "). Post or discard that batch first, or correct its draft leases instead of re-importing.");
        }
        if (sheetName == null || sheetName.isBlank()) {
            return new Match(null, r.getNameEn(), "The email '" + shown + "' belongs to the existing renter '"
                    + r.getNameEn() + "'. List them on the Renters sheet (or fill the contract's RenterName) to attach"
                    + " this contract to them.");
        }
        if (!sameName(r.getNameEn(), sheetName)) {
            return new Match(null, r.getNameEn(), "The email '" + shown + "' belongs to the existing renter '"
                    + r.getNameEn() + "', but this row names '" + sheetName.trim() + "'. Correct the email or the name.", true);
        }
        return new Match(r.getId(), r.getNameEn(), null);
    }

    /** Names compared as a person would: case, repeated spaces and surrounding blanks ignored. */
    public static boolean sameName(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The batch of this tenant that made this renter, or null when none did (#369 R1 nit: the typed status, not its text). */
    private com.datagami.rentaxis.domain.entity.ImportBatch madeBy(UUID tenantId, UUID renterId) {
        return batchEntities.findByEntityTypeAndEntityId(ImportedEntityType.RENTER, renterId).stream()
                .map(link -> batches.findById(link.getBatchId()).orElse(null))
                .filter(b -> b != null && tenantId.equals(b.getTenantId()))
                .findFirst()
                .orElse(null);
    }
}
