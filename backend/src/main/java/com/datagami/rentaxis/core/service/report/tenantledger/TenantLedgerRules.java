package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.core.service.cheque.ChequeReturnClass;
import com.datagami.rentaxis.core.service.report.grid.ReportLabels;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The Tenant Ledger's per-row rules, in PACT's spelling (reports spec §2). Each takes what one journal line
 * and its cheque carry and answers one printed cell, or {@code null} for a blank one.
 */
public final class TenantLedgerRules {

    private TenantLedgerRules() {
    }

    /**
     * Chq No: the cheque's number; else PACT's token for the instrument ({@code TT} for a transfer, {@code CASH},
     * {@code ONLINE}); blank on a line without a cheque, or for an unnumbered cheque.
     *
     * @param mode a {@code ChequeMode} name
     */
    public static String chqNo(UUID chequeId, String number, String mode) {
        if (chequeId == null) {
            return null;
        }
        if (!blank(number)) {
            return number;
        }
        return mode == null ? null : switch (mode) {
            case "TRANSFER" -> "TT";
            case "CASH" -> "CASH";
            case "ONLINE" -> "ONLINE";
            default -> null;
        };
    }

    /**
     * Payee Bank: the bank on the cheque; else {@code TRANSFER}, {@code CASH} or {@code ONLINE} for those
     * instruments; blank on a line without a cheque.
     *
     * @param mode a {@code ChequeMode} name
     */
    public static String payeeBank(UUID chequeId, String bank, String mode) {
        if (chequeId == null) {
            return null;
        }
        if (!blank(bank)) {
            return bank;
        }
        return mode == null ? null : switch (mode) {
            case "TRANSFER" -> "TRANSFER";
            case "CASH" -> "CASH";
            case "ONLINE" -> "ONLINE";
            default -> null;
        };
    }

    /** ConvertedDate: the cheque's maturity date, printed only on its registration (PDR) row, as PACT does. */
    public static LocalDate convertedDate(UUID entryId, UUID pdrJournalId, LocalDate chequeDate) {
        return entryId != null && entryId.equals(pdrJournalId) ? chequeDate : null;
    }

    /** BDDays: the cheque's {@link ChequeReturnClass} code as of {@code to}, or blank. Never summed. */
    public static Integer bdDays(UUID cbrJournalId, LocalDate bouncedAt, LocalDate to) {
        ChequeReturnClass c = ChequeReturnClass.of(cbrJournalId, bouncedAt, to);
        return c == null ? null : c.code;
    }

    /**
     * Particular: the line's own counter-account (its Arabic name under {@code ar} when it has one); for a line
     * the posting left unpaired, the entry's other accounts ({@code fallback}, Arabic {@code fallbackAr}); blank
     * when there is neither.
     */
    public static String particular(String lang, String contraName, String contraNameAr, String fallback, String fallbackAr) {
        boolean ar = "ar".equals(ReportLabels.lang(lang));
        if (!blank(contraName) || !blank(contraNameAr)) {
            return pick(ar, contraName, contraNameAr);
        }
        if (!blank(fallback) || !blank(fallbackAr)) {
            return pick(ar, fallback, fallbackAr);
        }
        return null;
    }

    /** The Arabic name under {@code ar} when it is not blank, else the English one (else the Arabic one). */
    static String pick(boolean ar, String en, String arName) {
        if (ar && !blank(arName)) {
            return arName;
        }
        return blank(en) ? (blank(arName) ? en : arName) : en;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
