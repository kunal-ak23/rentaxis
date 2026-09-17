package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Input to PostingService.post(). Callers name roles (resolved against dims.propertyId) or explicit account ids. */
public record PostingRequest(
        JournalDocType docType,
        LocalDate entryDate,
        String narration,
        Dimensions dims,
        JournalSourceType sourceType,
        UUID sourceId,
        UUID importBatchId,
        List<Line> lines) {

    /** A line that belongs to no pair, so PostingService leaves its contra account unset. */
    public static final int NO_PAIR = -1;

    public record Dimensions(UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId) {
        public static Dimensions none() { return new Dimensions(null, null, null, null, null); }
        public static Dimensions ofProperty(UUID propertyId) { return new Dimensions(propertyId, null, null, null, null); }
        /** Line dims override header dims field by field. */
        Dimensions mergedOver(Dimensions header) {
            if (header == null) return this;
            return new Dimensions(
                    propertyId != null ? propertyId : header.propertyId,
                    unitId != null ? unitId : header.unitId,
                    leaseId != null ? leaseId : header.leaseId,
                    renterId != null ? renterId : header.renterId,
                    chequeId != null ? chequeId : header.chequeId);
        }
    }

    public sealed interface AccountRef permits ByRole, ById {}
    public record ByRole(AccountRole role) implements AccountRef {}
    public record ById(UUID accountId) implements AccountRef {}

    public enum Side { DR, CR }

    /**
     * {@code pairKey} groups a debit with the credit it was raised against so the
     * ledger can print one counter-account per row (Addendum A). {@link #NO_PAIR}
     * means "not paired"; only {@link #ofPairs} hands out real keys.
     */
    public record Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration, int pairKey) {
        public Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration) {
            this(account, side, amount, dims, narration, NO_PAIR);
        }
        public Line withDims(Dimensions d) { return new Line(account, side, amount, d, narration, pairKey); }
        public Line withNarration(String n) { return new Line(account, side, amount, dims, n, pairKey); }
        public Line withPairKey(int key) { return new Line(account, side, amount, dims, narration, key); }
    }

    /** A debit line and the credit line it is paired with; both get each other's account as contra. */
    public record Pair(Line debit, Line credit) {
        public Pair {
            if (debit == null || credit == null) throw new IllegalArgumentException("A pair needs both a debit and a credit line");
            if (debit.side() != Side.DR) throw new IllegalArgumentException("The first line of a pair must be a debit");
            if (credit.side() != Side.CR) throw new IllegalArgumentException("The second line of a pair must be a credit");
            if (debit.amount() == null || credit.amount() == null) throw new IllegalArgumentException("A pair needs an amount on both lines");
            // Compared at the scale the ledger stores, so a caller handing over an unrounded
            // computed amount is not punished for the digits post() would drop anyway. A pair
            // whose halves genuinely differ is a caller bug: the entry could still balance
            // against some other pair, and the two would then face the wrong contra accounts.
            BigDecimal dr = debit.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal cr = credit.amount().setScale(2, RoundingMode.HALF_UP);
            if (dr.compareTo(cr) != 0) throw new IllegalArgumentException("Pair amounts must match: " + dr + " vs " + cr);
        }
    }

    public static Pair pair(Line debit, Line credit) { return new Pair(debit, credit); }

    /**
     * Builds a request from debit/credit pairs rather than a flat list: the pairs are
     * flattened debit-then-credit in order, and each keeps the pairing so the posting
     * can record a per-line contra account. Use it wherever one entry raises the same
     * account against several different counter-accounts (a TCO's receivable against
     * advance rent, deposit and admin fee); n-to-1 entries keep the flat constructor.
     */
    public static PostingRequest ofPairs(JournalDocType docType, LocalDate entryDate, String narration, Dimensions dims,
                                         JournalSourceType sourceType, UUID sourceId, UUID importBatchId, List<Pair> pairs) {
        List<Line> flat = new ArrayList<>();
        int key = 0;
        for (Pair p : pairs) {
            flat.add(p.debit().withPairKey(key));
            flat.add(p.credit().withPairKey(key));
            key++;
        }
        return new PostingRequest(docType, entryDate, narration, dims, sourceType, sourceId, importBatchId, List.copyOf(flat));
    }

    public static Line dr(AccountRole role, BigDecimal amount) { return new Line(new ByRole(role), Side.DR, amount, null, null); }
    public static Line cr(AccountRole role, BigDecimal amount) { return new Line(new ByRole(role), Side.CR, amount, null, null); }
    public static Line dr(UUID accountId, BigDecimal amount) { return new Line(new ById(accountId), Side.DR, amount, null, null); }
    public static Line cr(UUID accountId, BigDecimal amount) { return new Line(new ById(accountId), Side.CR, amount, null, null); }
}
