package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.JournalEntryDTO;
import com.datagami.rentaxis.api.dto.ledger.JournalLineDTO;
import com.datagami.rentaxis.api.dto.ledger.ManualJournalRequest;
import com.datagami.rentaxis.api.dto.ledger.ReverseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;

/**
 * The read/write face of the journal for the API. Writing still goes through
 * {@link PostingService} — this only translates a submitted voucher into a
 * {@link PostingRequest} and shapes entries into DTOs. Entries are immutable:
 * the only mutation offered is {@link #reverse}.
 */
@Service
public class JournalService {

    private final PostingService posting;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final PropertyRepository properties;
    private final UnitRepository units;
    private final LeaseRepository leases;
    private final RenterRepository renters;

    public JournalService(PostingService posting, JournalEntryRepository entries, JournalLineRepository lines,
                          PropertyRepository properties, UnitRepository units, LeaseRepository leases,
                          RenterRepository renters) {
        this.posting = posting;
        this.entries = entries;
        this.lines = lines;
        this.properties = properties;
        this.units = units;
        this.leases = leases;
        this.renters = renters;
    }

    @Transactional
    public JournalEntryDTO postManual(ManualJournalRequest r) {
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new BusinessRuleViolationException("At least two lines are required");
        }
        assertKnownProperty(r.propertyId());
        List<Line> out = new ArrayList<>();
        for (ManualJournalRequest.Line l : r.lines()) {
            // A JSON array may hold a literal null; without this the next deref is a 500.
            if (l == null) throw new BusinessRuleViolationException("A journal line is missing");
            if (l.accountId() == null) throw new BusinessRuleViolationException("Each line needs an account");
            BigDecimal debit = l.debit() == null ? BigDecimal.ZERO : l.debit();
            BigDecimal credit = l.credit() == null ? BigDecimal.ZERO : l.credit();
            if (debit.signum() > 0 && credit.signum() > 0) {
                throw new BusinessRuleViolationException("A line is either debit or credit, not both");
            }
            if (debit.signum() <= 0 && credit.signum() <= 0) {
                throw new BusinessRuleViolationException("Each line needs a positive debit or credit");
            }
            assertKnownDimensions(l);
            // A manual voucher is n-to-m by nature, so its lines stay unpaired: the
            // ledger prints every other account on the entry as the Particular.
            Dimensions dims = new Dimensions(r.propertyId(), l.unitId(), l.leaseId(), l.renterId(), null);
            Line line = debit.signum() > 0 ? dr(l.accountId(), debit) : cr(l.accountId(), credit);
            out.add(line.withDims(dims).withNarration(l.narration()));
        }
        JournalEntry e = posting.post(new PostingRequest(JournalDocType.JV, r.entryDate(), r.narration(),
                Dimensions.ofProperty(r.propertyId()), JournalSourceType.MANUAL, null, null, out));
        return toDto(e, true);
    }

    /**
     * Dimensions are stored as raw ids on the entry, with no foreign key behind them
     * (they are nullable analytics columns, not relations). An unchecked id is
     * therefore accepted silently and then reads back as an orphan — or, worse, as
     * another tenant's id on a ledger this tenant can see. Every exists* call below
     * runs inside this @Transactional method, so the Hibernate tenant filter is
     * active and a foreign id is simply "unknown".
     */
    private void assertKnownProperty(UUID propertyId) {
        if (propertyId != null && !properties.existsById(propertyId)) {
            throw new BusinessRuleViolationException("Unknown property id");
        }
    }

    private void assertKnownDimensions(ManualJournalRequest.Line l) {
        if (l.unitId() != null && !units.existsById(l.unitId())) {
            throw new BusinessRuleViolationException("Unknown unit id");
        }
        if (l.leaseId() != null && !leases.existsById(l.leaseId())) {
            throw new BusinessRuleViolationException("Unknown lease id");
        }
        if (l.renterId() != null && !renters.existsById(l.renterId())) {
            throw new BusinessRuleViolationException("Unknown renter id");
        }
    }

    @Transactional
    public JournalEntryDTO reverse(UUID id, ReverseRequest r) {
        ReverseRequest req = r == null ? new ReverseRequest(null, null) : r;
        LocalDate date = req.date() == null ? LocalDate.now() : req.date();
        return toDto(posting.reverse(id, date, req.reason()), true);
    }

    @Transactional(readOnly = true)
    public JournalEntryDTO get(UUID id) {
        return toDto(entries.findById(id).orElseThrow(() -> new NotFoundException("Journal entry not found")), true);
    }

    @Transactional(readOnly = true)
    public Page<JournalEntryDTO> search(JournalDocType docType, LocalDate from, LocalDate to,
                                        UUID propertyId, UUID leaseId, Pageable pageable) {
        return entries.search(docType, from, to, propertyId, leaseId, pageable).map(e -> toDto(e, false));
    }

    JournalEntryDTO toDto(JournalEntry e, boolean withLines) {
        List<JournalLineDTO> ls = List.of();
        BigDecimal total;
        if (withLines) {
            ls = new ArrayList<>();
            total = BigDecimal.ZERO;
            for (JournalLine l : lines.findByEntry_IdOrderByLineNoAsc(e.getId())) {
                total = total.add(l.getDebit());
                ls.add(new JournalLineDTO(l.getLineNo(), l.getAccount().getId(), l.getAccount().getCode(),
                        l.getAccount().getName(), l.getDebit(), l.getCredit(), l.getNarration(),
                        l.getPropertyId(), l.getUnitId(), l.getLeaseId(), l.getRenterId(), l.getChequeId()));
            }
        } else {
            // A list row still shows an amount, and one aggregate beats loading every line.
            total = lines.totalDebit(e.getId());
        }
        return new JournalEntryDTO(e.getId(), e.getEntryNumber(), e.getDocType().name(), e.getEntryDate(),
                e.getNarration(), e.getStatus().name(),
                e.getPropertyId(), e.getUnitId(), e.getLeaseId(), e.getRenterId(),
                e.getSourceType() == null ? null : e.getSourceType().name(), e.getSourceId(),
                e.getReversalOfId(), e.getReversedById(), e.getImportBatchId(), e.getPostedBy(), e.getPostedAt(),
                total, ls);
    }
}
