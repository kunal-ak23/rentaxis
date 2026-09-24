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

    public BankStatementPostingService(PostingService posting, NamedParameterJdbcTemplate jdbc) {
        this.posting = posting;
        this.jdbc = jdbc;
    }

    /** A statement line as the posting needs it. */
    public record Line(UUID id, BigDecimal amount, String description, String reference) { }

    /** The figures a charge would post (also the dialog's preview). */
    public record ChargeSplit(BigDecimal net, BigDecimal vat, BigDecimal gross) { }

    /**
     * Spec §3 "Bank charge": one debit line, or a charge line plus its VAT line.
     * Input VAT only when the bank's TRN is on file; otherwise the whole amount
     * is a charge. One line with {@code vatIncluded}: VAT = gross × 5/105.
     */
    public static ChargeSplit chargeSplit(List<BigDecimal> debits, boolean vatIncluded, boolean bankTrnSet) {
        if (debits.isEmpty() || debits.size() > 2) {
            throw new BusinessRuleViolationException("A bank charge is one line, or a charge line and its VAT line");
        }
        if (debits.stream().anyMatch(a -> a.signum() >= 0)) {
            throw new BusinessRuleViolationException("A bank charge is booked from debit lines");
        }
        BigDecimal gross = debits.stream().map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!bankTrnSet) return new ChargeSplit(gross, BigDecimal.ZERO.setScale(2), gross);
        BigDecimal vat;
        if (debits.size() == 2) {
            vat = debits.stream().map(BigDecimal::abs).min(Comparator.naturalOrder()).orElseThrow();
        } else if (vatIncluded) {
            vat = gross.multiply(VAT_RATE).divide(BigDecimal.ONE.add(VAT_RATE), 2, RoundingMode.HALF_UP);
        } else {
            vat = BigDecimal.ZERO.setScale(2);
        }
        return new ChargeSplit(gross.subtract(vat), vat, gross);
    }

    /**
     * Posts the {@code BNK}. {@code leafId}: the bank leaf in the set it moves;
     * {@code propertyId}: the dimension (null: shared / head office);
     * {@code accountId}: the other side of an OTHER posting.
     */
    @Transactional
    public JournalEntry post(Kind kind, List<Line> lines, LocalDate date, UUID leafId, UUID propertyId,
                             boolean vatIncluded, boolean bankTrnSet, UUID accountId, String narration) {
        UUID t = BankAccountLedgerService.requireTenant();
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
                ChargeSplit s = chargeSplit(lines.stream().map(Line::amount).toList(), vatIncluded, bankTrnSet);
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
