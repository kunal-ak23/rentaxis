package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Bank-only items booked from statement lines (finance-ops spec §3): charges,
 * interest, unidentified receipts and "other", each a {@code BNK} journal posted
 * through {@link PostingService}, the only journal writer.
 */
@Service
public class BankStatementPostingService {

    public enum Kind { CHARGE, INTEREST, SUSPENSE, OTHER }

    /** UAE standard rate, for a charge line that includes its VAT. */
    static final BigDecimal VAT_RATE = new BigDecimal("0.05");

    /** Sub-types with their own documents: never the other side of an "Other" posting. */
    static final Set<String> CONTROL_SUB_TYPES = Set.of("RECEIVABLE", "PDC_RECEIVABLE", "DEPOSIT_HELD", "PAYABLE",
            "PDC_PAYABLE", "ADVANCE");

    private final PostingService posting;
    private final NamedParameterJdbcTemplate jdbc;
    private final com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

    public BankStatementPostingService(PostingService posting, NamedParameterJdbcTemplate jdbc,
                                       com.datagami.rentaxis.core.service.ledger.BankLockService bankLock) {
        this.bankLock = bankLock;
        this.posting = posting;
        this.jdbc = jdbc;
    }

    /** A statement line as the posting needs it. */
    public record Line(UUID id, BigDecimal amount, String description, String reference) { }

    /** The figures a charge would post (also the dialog's preview). */
    public record ChargeSplit(BigDecimal net, BigDecimal vat, BigDecimal gross) { }

    /**
     * Spec §3 "Bank charge", as the PR #353 review (P2-3) ruled.
     *
     * <ul>
     *   <li>One line: the whole amount is the charge, or with {@code vatIncluded}
     *       VAT = gross × 5/105. A stated split is accepted too.</li>
     *   <li>Several lines (a charge and its VAT line): the accountant states the
     *       net and the VAT. Nothing is inferred from which line is smaller.</li>
     *   <li>A stated split must add up to the lines, and its VAT may not exceed
     *       5% of the net (one fils of rounding allowed).</li>
     *   <li>Input VAT only when the bank's TRN is on file; without it, a stated VAT
     *       is refused and the whole amount is a charge.</li>
     * </ul>
     */
    public static ChargeSplit chargeSplit(List<BigDecimal> debits, boolean vatIncluded, boolean bankTrnSet,
                                          BigDecimal statedNet, BigDecimal statedVat) {
        if (debits.isEmpty()) throw new BusinessRuleViolationException("Select the charge line(s)");
        if (debits.stream().anyMatch(a -> a.signum() >= 0)) {
            throw new BusinessRuleViolationException("A bank charge is booked from debit lines");
        }
        BigDecimal gross = debits.stream().map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        boolean stated = statedNet != null || statedVat != null;
        if (!stated && debits.size() > 1) {
            throw new BusinessRuleViolationException("Several lines make one charge only with its split stated: "
                    + "give the net charge and the VAT (or book each line as its own charge)");
        }
        if (stated) {
            BigDecimal net = (statedNet == null ? gross.subtract(statedVat) : statedNet).setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = (statedVat == null ? gross.subtract(net) : statedVat).setScale(2, RoundingMode.HALF_UP);
            if (net.signum() <= 0 || vat.signum() < 0) throw new BusinessRuleViolationException("The net charge must be above zero and the VAT not below");
            if (net.add(vat).compareTo(gross) != 0) {
                throw new BusinessRuleViolationException("Net " + StatementValues.money(net) + " + VAT " + StatementValues.money(vat)
                        + " does not make the lines' " + StatementValues.money(gross));
            }
            if (vat.signum() > 0 && !bankTrnSet) {
                throw new BusinessRuleViolationException("No bank TRN on file, so no input VAT can be claimed; book the whole amount as the charge");
            }
            BigDecimal cap = net.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP).add(new BigDecimal("0.01"));
            if (vat.compareTo(cap) > 0) {
                throw new BusinessRuleViolationException("VAT " + StatementValues.money(vat) + " is more than 5% of the net "
                        + StatementValues.money(net) + "; book the lines as separate charges");
            }
            return new ChargeSplit(net, vat, gross);
        }
        if (!bankTrnSet || !vatIncluded) return new ChargeSplit(gross, BigDecimal.ZERO.setScale(2), gross);
        BigDecimal vat = gross.multiply(VAT_RATE).divide(BigDecimal.ONE.add(VAT_RATE), 2, RoundingMode.HALF_UP);
        return new ChargeSplit(gross.subtract(vat), vat, gross);
    }

    /**
     * Posts the {@code BNK}. {@code leafId}: the bank leaf in the set it moves;
     * {@code propertyId}: the dimension (null: shared / head office);
     * {@code accountId}: the other side of an OTHER posting.
     */
    @Transactional
    public JournalEntry post(Kind kind, List<Line> lines, LocalDate date, UUID leafId, UUID propertyId,
                             boolean vatIncluded, boolean bankTrnSet, UUID accountId, String narration,
                             BigDecimal statedNet, BigDecimal statedVat, java.util.Set<UUID> leafSet) {
        UUID t = BankAccountLedgerService.requireTenant();
        // Finance-ops spec §4: refused early inside a reconciled period of the leaf.
        bankLock.assertOpen(List.of(leafId), date);
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String first = lines.get(0).description();
        String text = narration != null && !narration.isBlank() ? narration.trim() : switch (kind) {
            case CHARGE -> "Bank charge — " + first;
            case INTEREST -> "Bank interest — " + first;
            case SUSPENSE -> "Unidentified receipt — " + first
                    + (lines.get(0).reference() == null ? "" : " (ref " + lines.get(0).reference() + ")");
            case OTHER -> first;
        };
        if (kind == Kind.SUSPENSE && narration != null && !narration.isBlank() && lines.get(0).reference() != null
                && !text.contains(lines.get(0).reference())) {
            text = text + " (ref " + lines.get(0).reference() + ")";
        }
        PostingRequest.Dimensions dims = propertyId == null ? null : PostingRequest.Dimensions.ofProperty(propertyId);
        List<PostingRequest.Line> jl = new ArrayList<>();
        switch (kind) {
            case CHARGE -> {
                ChargeSplit s = chargeSplit(lines.stream().map(Line::amount).toList(), vatIncluded, bankTrnSet,
                        statedNet, statedVat);
                jl.add(PostingRequest.dr(AccountRole.BANK_CHARGES, s.net()).withNarration(text));
                if (s.vat().signum() > 0) jl.add(PostingRequest.dr(AccountRole.INPUT_VAT, s.vat()).withNarration(text));
                jl.add(PostingRequest.cr(leafId, s.gross()).withNarration(text));
            }
            case INTEREST, SUSPENSE -> {
                if (lines.size() != 1 || total.signum() <= 0) {
                    throw new BusinessRuleViolationException((kind == Kind.INTEREST ? "Interest" : "An unidentified receipt")
                            + " is booked from one credit line");
                }
                jl.add(PostingRequest.dr(leafId, total).withNarration(text));
                jl.add(PostingRequest.cr(kind == Kind.INTEREST ? AccountRole.BANK_INTEREST_INCOME : AccountRole.BANK_SUSPENSE,
                        total).withNarration(text));
            }
            case OTHER -> {
                if (total.signum() == 0) throw new BusinessRuleViolationException("The selected lines net to zero");
                requireOtherAccount(t, accountId);
                // PR #353 review: "Other" into the same real account moves nothing.
                if (leafSet.contains(accountId)) {
                    throw new BusinessRuleViolationException("That ledger account belongs to this same bank account; "
                            + "choose the account on the other side of the movement");
                }
                BigDecimal a = total.abs();
                if (total.signum() > 0) {
                    jl.add(PostingRequest.dr(leafId, a).withNarration(text));
                    jl.add(PostingRequest.cr(accountId, a).withNarration(text));
                } else {
                    jl.add(PostingRequest.dr(accountId, a).withNarration(text));
                    jl.add(PostingRequest.cr(leafId, a).withNarration(text));
                }
            }
        }
        if (dims != null) jl = jl.stream().map(l -> l.withDims(dims)).toList();
        return posting.post(new PostingRequest(JournalDocType.BNK, date, text, dims,
                JournalSourceType.BANK_STATEMENT, lines.get(0).id(), null, jl));
    }

    /** "Other": an active leaf of this tenant that is not a control account nor the suspense leaf. */
    private void requireOtherAccount(UUID t, UUID accountId) {
        if (accountId == null) throw new BusinessRuleViolationException("Choose the account to post to");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.code, a.name, a.account_sub_type, a.is_group, a.is_active,
                       exists (select 1 from tenant_default_account_mappings m where m.tenant_id = a.tenant_id
                                 and m.account_id = a.id and m.role in ('BANK_SUSPENSE', 'PDC_PAYABLE', 'RENT_RECEIVABLE',
                                 'PDC_RECEIVABLE', 'SECURITY_DEPOSIT', 'PARKING_DEPOSIT')) as control_role,
                       exists (select 1 from property_account_mappings m where m.tenant_id = a.tenant_id
                                 and m.account_id = a.id and m.role in ('RENT_RECEIVABLE', 'PDC_RECEIVABLE',
                                 'SECURITY_DEPOSIT', 'PARKING_DEPOSIT')) as property_control
                from accounts a where a.id = :a and a.tenant_id = :t""",
                new MapSqlParameterSource("t", t).addValue("a", accountId));
        if (rows.isEmpty()) throw new com.datagami.rentaxis.api.exception.NotFoundException("Account not found");
        Map<String, Object> r = rows.get(0);
        String label = r.get("code") + " " + r.get("name");
        if (Boolean.TRUE.equals(r.get("is_group")) || !Boolean.TRUE.equals(r.get("is_active"))) {
            throw new BusinessRuleViolationException(label + " is not an active ledger leaf");
        }
        if (CONTROL_SUB_TYPES.contains((String) r.get("account_sub_type")) || Boolean.TRUE.equals(r.get("control_role"))
                || Boolean.TRUE.equals(r.get("property_control"))) {
            throw new BusinessRuleViolationException(label + " is a control account with its own documents; "
                    + "use the matching action (receive, clear, present) instead");
        }
    }
}
