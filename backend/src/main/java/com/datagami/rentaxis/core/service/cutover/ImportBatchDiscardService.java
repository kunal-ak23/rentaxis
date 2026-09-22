package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BaseTenantEntity;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchEntity;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Throwing a cut-over import away (ruling I4) — the other half of the loop that
 * makes "correct the workbook and load it again" possible.
 *
 * <p>An import refuses a property name or a renter e-mail the organisation already
 * has, and refuses a contract reference it already holds, because
 * {@code PropertyAccountService} resolves leaves by <em>name</em> and a silent
 * merge would join two towers' ledgers (R10). That rule is right, and it is also
 * what makes a botched first import unrecoverable: the properties, units, renters
 * and contract references it left behind are exactly what the second attempt trips
 * over. Discard removes them.</p>
 *
 * <p><b>Exactly what the batch made, and nothing else.</b> The rows come from
 * {@code import_batch_entities}, written at the moment each one was created — a set
 * reconstructed later from timestamps would take somebody else's.</p>
 *
 * <p><b>Anything still referenced is kept and reported.</b> A unit somebody has
 * since put a lease on, a renter with a contract of their own: each is a row the
 * landlord now depends on, and a discard that deleted it would be a worse outcome
 * than the one it was fixing. The batch is still marked DISCARDED — the batch
 * really is finished with — and what is left is a list the accountant can act on.</p>
 *
 * <h2>A batch that has ever been posted cannot be fully discarded, and that is the
 * ledger working</h2>
 *
 * <p>{@code journal_entries} carries restricting foreign keys to {@code leases},
 * {@code units}, {@code properties} and {@code renters} (changeset 81), and its
 * rows can be neither deleted nor re-pointed — {@code trg_journal_entries_immutable}
 * refuses both, which is what makes the journal a journal. So once a batch has been
 * posted, every contract it created and every row those contracts name is in the
 * ledger's history <em>permanently</em>, reversal or no reversal: a reversed entry
 * is still an entry, with a number in a gapless series.</p>
 *
 * <p>The consequence is worth stating plainly, because it decides which recovery an
 * accountant should reach for:</p>
 * <ul>
 *   <li><b>A DRAFT batch</b> — imported, read on screen, found wrong, never posted —
 *       discards completely, and the corrected workbook then imports cleanly. This
 *       is the case the rule exists for and the overwhelmingly common one.</li>
 *   <li><b>A REVERSED batch</b> — posted, then taken back off — is <b>refused</b>.
 *       Its journals still name its contracts, so there is nothing here that could
 *       actually remove them, and marking it DISCARDED anyway used to leave the
 *       contracts standing while taking away the one thing that still worked. Its
 *       route back is <em>Post</em> again, which {@code ContractImportPostService}
 *       serves with a successor batch over the same leases (R12) — the imported
 *       statuses and dates survived the reverse precisely so that works.</li>
 * </ul>
 *
 * <p><b>Not one transaction, deliberately.</b> Each lease and each created row is
 * deleted in a transaction of its own, because a foreign key cannot see an
 * uncommitted delete: a unit whose lease was removed a moment ago in an <em>open</em>
 * transaction still has that lease pointing at it. The order is therefore leases,
 * then units, then buildings, then renters, then properties — children before
 * parents, all the way up.</p>
 */
@Service
public class ImportBatchDiscardService {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchDiscardService.class);

    private final ImportBatchService batches;
    private final LeaseRepository leases;
    private final JournalEntryRepository journals;
    private final RecognitionEntryRepository recognitionEntries;
    private final RentSegmentRepository segments;
    private final UnitRepository units;
    private final BuildingRepository buildings;
    private final RenterRepository renters;
    private final PropertyRepository properties;
    private final PropertyAccountMappingRepository propertyMappings;
    private final AccountRepository accounts;
    private final LeaseService leaseService;

    /** The run's own transaction: it holds the batch row lock and nothing else. */
    private final TransactionTemplate tx;

    /**
     * One deletion, suspended out of the run's transaction. {@code REQUIRES_NEW}
     * because a foreign key cannot see an uncommitted delete, and because a
     * constraint violation poisons the transaction it happens in — which is what
     * makes {@link #deleteIfUnreferenced}'s catch safe.
     */
    private final TransactionTemplate ownTx;

    public ImportBatchDiscardService(ImportBatchService batches, LeaseRepository leases,
                                     JournalEntryRepository journals,
                                     RecognitionEntryRepository recognitionEntries, RentSegmentRepository segments,
                                     UnitRepository units, BuildingRepository buildings, RenterRepository renters,
                                     PropertyRepository properties,
                                     PropertyAccountMappingRepository propertyMappings, AccountRepository accounts,
                                     LeaseService leaseService, PlatformTransactionManager transactionManager) {
        this.batches = batches;
        this.leases = leases;
        this.journals = journals;
        this.recognitionEntries = recognitionEntries;
        this.segments = segments;
        this.units = units;
        this.buildings = buildings;
        this.renters = renters;
        this.properties = properties;
        this.propertyMappings = propertyMappings;
        this.accounts = accounts;
        this.leaseService = leaseService;
        this.tx = new TransactionTemplate(transactionManager);
        this.ownTx = new TransactionTemplate(transactionManager);
        this.ownTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * One row the discard did not remove, and why.
     *
     * @param type {@code LEASE}, or an {@link ImportedEntityType} name. A string
     *             rather than the enum because the enum is a database CHECK over
     *             what an import <em>creates</em>, and a lease is recorded
     *             separately in {@code import_batch_leases}.
     */
    public record Kept(String type, UUID id, String name, String reason) {
    }

    /** What the discard did. */
    public record DiscardResult(UUID batchId, ImportBatchStatus status, int leasesDeleted,
                                int unitsDeleted, int buildingsDeleted, int rentersDeleted,
                                int propertiesDeleted, List<Kept> kept) {
    }

    /**
     * Delete the batch's draft leases and the rows it created, then mark it
     * DISCARDED.
     *
     * @throws BusinessRuleViolationException when the batch is POSTED — reverse it
     *         first, its contracts are on the books — or already DISCARDED. Checked
     *         before anything is deleted.
     */
    public DiscardResult discard(UUID batchId) {
        return tx.execute(s -> runUnderBatchLock(batchId));
    }

    /**
     * The run, with the batch row held for its whole length.
     *
     * <p><b>The lock is the same one {@code post} takes</b> (review I2). Without it a
     * discard could start while a post was running: it would read DRAFT, delete the
     * contracts the post had not reached yet — which the post then reported as "this
     * lease no longer exists" — and finish by calling {@code markDiscarded} on a
     * batch the post had just marked POSTED, throwing <em>after</em> the rows were
     * gone. No ledger damage, because the foreign keys protect a posted contract, but
     * a half-dismantled batch and an opaque 400.</p>
     *
     * <p>The deletions still happen in transactions of their own, suspended out of
     * this one: a foreign key cannot see an uncommitted delete, so a unit whose lease
     * went a moment ago in an <em>open</em> transaction still has that lease pointing
     * at it. Holding the lock here and committing the rows there is the combination
     * that gives both properties.</p>
     */
    private DiscardResult runUnderBatchLock(UUID batchId) {
        ImportBatch batch = batches.lockForRun(batchId);
        ImportBatchService.requireDiscardableStatus(batch);

        List<UUID> leaseIds = batches.leaseIds(batchId);
        List<ImportBatchEntity> created = batches.createdEntities(batchId);

        List<Kept> kept = new ArrayList<>();
        int leasesDeleted = 0;
        for (UUID leaseId : leaseIds) {
            Outcome outcome = ownTx.execute(s -> deleteLease(leaseId));
            if (outcome.kept() == null) {
                leasesDeleted++;
            } else {
                kept.add(outcome.kept());
            }
        }

        int unitsDeleted = 0;
        int buildingsDeleted = 0;
        int rentersDeleted = 0;
        int propertiesDeleted = 0;
        for (ImportBatchEntity e : ordered(created)) {
            Outcome outcome = ownTx.execute(s -> deleteIfUnreferenced(e.getEntityType(), e.getEntityId()));
            if (outcome.kept() != null) {
                kept.add(outcome.kept());
                continue;
            }
            switch (e.getEntityType()) {
                case UNIT -> unitsDeleted++;
                case BUILDING -> buildingsDeleted++;
                case RENTER -> rentersDeleted++;
                case PROPERTY -> propertiesDeleted++;
            }
        }

        ImportBatch discarded = batches.markDiscarded(batchId);
        log.info("Discarded import batch {}: {} leases, {} units, {} buildings, {} renters, {} properties, {} kept",
                batchId, leasesDeleted, unitsDeleted, buildingsDeleted, rentersDeleted, propertiesDeleted,
                kept.size());
        return new DiscardResult(batchId, discarded.getStatus(), leasesDeleted, unitsDeleted, buildingsDeleted,
                rentersDeleted, propertiesDeleted, List.copyOf(kept));
    }

    /**
     * Children before parents. A unit cannot go while a lease points at it, a
     * building cannot go while a unit does, and a property cannot go while either
     * still exists; a renter is independent of all three and goes whenever its
     * leases have.
     */
    private static List<ImportBatchEntity> ordered(List<ImportBatchEntity> created) {
        List<ImportBatchEntity> out = new ArrayList<>(created);
        out.sort(Comparator.comparingInt(e -> switch (e.getEntityType()) {
            case UNIT -> 0;
            case BUILDING -> 1;
            case RENTER -> 2;
            case PROPERTY -> 3;
        }));
        return out;
    }

    /** Either the row went, or it stayed and here is why. */
    private record Outcome(Kept kept) {
        static final Outcome DELETED = new Outcome(null);
    }

    /**
     * One imported contract and everything hanging off it.
     *
     * <p>The recognition schedule goes first and by hand: {@code rent_segments} and
     * {@code recognition_entries} both carry a foreign key to the lease, and
     * {@code LeaseService.deleteDraftLease} — which owns every other rule about
     * taking a draft apart, including refusing one whose cheques are no longer
     * drafts — knows nothing about them.</p>
     */
    private Outcome deleteLease(UUID leaseId) {
        Lease lease = leases.findByIdScopedToTenant(leaseId).orElse(null);
        if (lease == null) return Outcome.DELETED;   // the link may outlive its lease; changeset 88 has no FK
        String name = lease.getExternalContractRef() == null ? leaseId.toString() : lease.getExternalContractRef();

        // The journal check comes FIRST because it is the reason nothing can fix. A
        // contract the ledger names cannot be deleted whatever its status is, and a
        // batch reaching here is DRAFT, so a contract of it that is ACTIVE was posted
        // by something other than this batch's own run — most likely a run that died
        // after committing it, or the ordinary "post this lease" door.
        long entries = journals.countByLeaseId(leaseId);
        if (entries > 0) {
            // The journals may net to zero but they are still entries with numbers in
            // a gapless series, and the ledger refuses both to delete them and to
            // unpoint them (changeset 81's immutability trigger).
            return new Outcome(new Kept("LEASE", leaseId, name,
                    entries + " journal entries permanently name this contract, so it cannot be deleted."
                            + " Post the batch again instead of re-importing it."));
        }
        if (lease.getStatus() != LeaseStatus.DRAFT) {
            // No journals and not a draft: not a state this code produces, and not one
            // to guess at either — LeaseService.deleteDraftLease would refuse it, and
            // saying so is better than an opaque 400 from inside the loop.
            return new Outcome(new Kept("LEASE", leaseId, name,
                    "the contract is " + lease.getStatus() + " rather than a draft, so it was left alone"));
        }

        for (RecognitionEntry e : recognitionEntries.findByLease_IdOrderByPeriodStartAsc(leaseId)) {
            recognitionEntries.delete(e);
        }
        for (RentSegment s : segments.findByLease_IdOrderByFromDateAsc(leaseId)) {
            segments.delete(s);
        }
        recognitionEntries.flush();
        segments.flush();
        leaseService.deleteDraftLease(leaseId);
        return Outcome.DELETED;
    }

    /**
     * Delete one created row, unless something still points at it.
     *
     * <p><b>Two layers, on purpose.</b> The explicit checks are for the cases that
     * actually happen and produce a sentence an accountant can act on — "a lease has
     * been created on it since". The {@code DataIntegrityViolationException} catch
     * underneath is for everything else this service does not know about: a booking,
     * a listing, a maintenance ticket, a journal entry naming the property, a column
     * a later release adds. Without it one unexpected foreign key would abort the
     * whole discard; with it, that row is kept and named. Its own transaction is what
     * makes the catch safe — a constraint violation poisons the transaction it
     * happens in, and this one has nothing else in it.</p>
     */
    private Outcome deleteIfUnreferenced(ImportedEntityType type, UUID id) {
        try {
            return switch (type) {
                case UNIT -> deleteUnit(id);
                case BUILDING -> deleteBuilding(id);
                case RENTER -> deleteRenter(id);
                case PROPERTY -> deleteProperty(id);
            };
        } catch (DataIntegrityViolationException e) {
            log.info("Discard kept {} {}: still referenced ({})", type, id,
                    e.getMostSpecificCause().getMessage());
            return new Outcome(new Kept(type.name(), id, null,
                    "something else still refers to it, so it was kept"));
        }
    }

    private Outcome deleteUnit(UUID id) {
        var unit = units.findById(id).filter(this::ours).orElse(null);
        if (unit == null) return Outcome.DELETED;   // already gone; nothing to keep
        List<Lease> on = leases.findByUnitId(id);
        if (!on.isEmpty()) {
            return new Outcome(new Kept(ImportedEntityType.UNIT.name(), id, unit.getUnitNumber(),
                    on.size() + " lease(s) still exist on this unit"));
        }
        units.delete(unit);
        units.flush();
        return Outcome.DELETED;
    }

    private Outcome deleteBuilding(UUID id) {
        var building = buildings.findById(id).filter(this::ours).orElse(null);
        if (building == null) return Outcome.DELETED;
        UUID propertyId = building.getProperty() == null ? null : building.getProperty().getId();
        long remaining = propertyId == null ? 0 : units.findByPropertyId(propertyId).stream()
                .filter(u -> u.getBuilding() != null && id.equals(u.getBuilding().getId())).count();
        if (remaining > 0) {
            return new Outcome(new Kept(ImportedEntityType.BUILDING.name(), id, building.getNameEn(),
                    remaining + " unit(s) are still in this building"));
        }
        buildings.delete(building);
        buildings.flush();
        return Outcome.DELETED;
    }

    private Outcome deleteRenter(UUID id) {
        var renter = renters.findById(id).filter(this::ours).orElse(null);
        if (renter == null) return Outcome.DELETED;
        List<Lease> held = leases.findByRenterId(id);
        if (!held.isEmpty()) {
            return new Outcome(new Kept(ImportedEntityType.RENTER.name(), id, renter.getNameEn(),
                    held.size() + " lease(s) still belong to this renter"));
        }
        renters.delete(renter);
        renters.flush();
        return Outcome.DELETED;
    }

    /**
     * A property, the role mappings the import wrote for it, and the property tag on
     * the leaves the account template generated for it.
     *
     * <p><b>The accounts themselves stay.</b> They are the tenant's chart, not this
     * batch's property: the accountant named half of them on the Properties sheet and
     * the template generated the rest into the chart's own tree, and either survives
     * one botched workbook. What goes is the <em>link</em> —
     * {@code accounts.property_id} is {@code ON DELETE SET NULL} (changeset 81), which
     * is the schema saying exactly this, and clearing it here rather than letting the
     * database do it keeps Hibernate's in-memory graph agreeing with the row it is
     * about to delete. A re-import re-generates nothing: {@code generateMissing}
     * finds the same-named leaf under the same parent, reuses it and puts the
     * property back on it.</p>
     */
    private Outcome deleteProperty(UUID id) {
        var property = properties.findById(id).filter(this::ours).orElse(null);
        if (property == null) return Outcome.DELETED;
        int remainingUnits = units.findByPropertyId(id).size();
        int remainingBuildings = buildings.findByPropertyId(id).size();
        if (remainingUnits > 0 || remainingBuildings > 0) {
            return new Outcome(new Kept(ImportedEntityType.PROPERTY.name(), id, property.getNameEn(),
                    remainingUnits + " unit(s) and " + remainingBuildings
                            + " building(s) still belong to this property"));
        }
        propertyMappings.deleteAll(propertyMappings.findByPropertyId(id));
        propertyMappings.flush();
        for (var account : accounts.findByProperty_Id(id)) {
            account.setProperty(null);
            accounts.save(account);
        }
        accounts.flush();
        properties.delete(property);
        properties.flush();
        return Outcome.DELETED;
    }

    /**
     * The row belongs to the organisation in context.
     *
     * <p>The ids arrive from {@code import_batch_entities}, which carries no tenant
     * column — {@code ImportBatchService.createdEntities} resolves the batch first,
     * so they cannot be another organisation's, and this is the second layer that
     * says so at the point of deletion.</p>
     */
    private boolean ours(BaseTenantEntity row) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null || tenantId.equals(row.getTenantId());
    }
}
