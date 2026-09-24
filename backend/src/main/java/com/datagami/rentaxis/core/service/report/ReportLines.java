package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The P&L row keys ({@code accounts.report_line}, finance-ops spec §1).
 *
 * <p>Every property has its own leaf per role and per expense category, so a
 * summary grouped by account id never lines up across properties. A report line
 * is the key they share: the role name for a role leaf, {@code EXP_<CATEGORY>}
 * for a direct-expense leaf, and null for anything else (the row is then the
 * account itself).</p>
 *
 * <p>Changeset 109 backfills existing charts with the same rules as
 * {@link #forRoles} and {@link #expenseLineForLeafName}; a change here needs the
 * matching change in a new changeset, never in 109.</p>
 */
public final class ReportLines {

    private ReportLines() { }

    /** A direct-expense category: the leaf-name prefix it is generated with and its report line. */
    public record ExpenseCategory(String nameEn, String nameAr, String reportLine) {
        /** "Cleaning - ": with the separator, so "Security Deposit Refunds" is not "Security". */
        public String leafPrefix() { return nameEn + " - "; }
    }

    /**
     * The running costs a UAE residential building actually incurs, in the order
     * {@code PropertyAccountService} creates their leaves under {@code D-01}.
     */
    public static final List<ExpenseCategory> DIRECT_EXPENSE_CATEGORIES = List.of(
            new ExpenseCategory("Repairs & Maintenance", "الإصلاح والصيانة", "EXP_REPAIRS_MAINTENANCE"),
            new ExpenseCategory("Cleaning", "التنظيف", "EXP_CLEANING"),
            new ExpenseCategory("Security", "الأمن والحراسة", "EXP_SECURITY"),
            new ExpenseCategory("Utilities", "المرافق", "EXP_UTILITIES"),
            new ExpenseCategory("Insurance", "التأمين على المبنى", "EXP_INSURANCE"),
            new ExpenseCategory("Management Fees", "رسوم الإدارة", "EXP_MANAGEMENT_FEES"));

    /** EN / AR label per report line. Roles that never reach a P&L still get one, for the CoA picker. */
    private static final Map<String, String[]> LABELS = new LinkedHashMap<>();

    static {
        role(AccountRole.RENT_RECEIVABLE, "Rent receivable", "إيجارات مستحقة");
        role(AccountRole.ADVANCE_RENT, "Advance rent", "إيجار مقدم");
        role(AccountRole.RENTAL_INCOME, "Rental income", "إيرادات الإيجار");
        role(AccountRole.PDC_RECEIVABLE, "PDC receivable", "شيكات مؤجلة مستحقة");
        role(AccountRole.BANK, "Bank", "الحساب البنكي");
        role(AccountRole.SECURITY_DEPOSIT, "Security deposit", "تأمين الإيجار");
        role(AccountRole.ADMIN_FEE, "Admin fee", "الرسوم الإدارية");
        role(AccountRole.PARKING_INCOME, "Additional parking", "مواقف إضافية");
        role(AccountRole.PARKING_DEPOSIT, "Parking deposit", "تأمين المواقف");
        role(AccountRole.COOLING_CHARGES, "Cooling charges", "رسوم التبريد");
        role(AccountRole.MAINTENANCE_CHARGES, "Maintenance charges", "رسوم الصيانة");
        role(AccountRole.RENT_PENALTY, "Rent penalty", "غرامة تأخير الإيجار");
        role(AccountRole.CHEQUE_RETURN_PENALTY, "Cheque return penalty", "غرامة الشيكات المرتجعة");
        role(AccountRole.OTHER_INCOME, "Other income", "إيرادات أخرى");
        role(AccountRole.FORFEITED_INCOME, "Amount forfeited", "مبالغ مصادرة");
        role(AccountRole.DISCOUNT_ALLOWED, "Discount allowed", "خصم مسموح");
        role(AccountRole.ROUNDING_OFF, "Rounding off", "فروق التقريب");
        role(AccountRole.CASH, "Cash", "النقد");
        role(AccountRole.OUTPUT_VAT, "Output VAT", "ضريبة المخرجات");
        role(AccountRole.INPUT_VAT, "Input VAT", "ضريبة المدخلات");
        role(AccountRole.OPENING_BALANCE_DIFFERENCE, "Opening balance difference", "فرق الأرصدة الافتتاحية");
        role(AccountRole.OUTPUT_VAT_DEFERRED, "Output VAT – not yet due", "ضريبة المخرجات غير المستحقة بعد");
        role(AccountRole.PDC_PAYABLE, "PDC payable – issued cheques", "شيكات مؤجلة صادرة");
        role(AccountRole.BANK_CHARGES, "Bank charges", "رسوم بنكية");
        role(AccountRole.BANK_INTEREST_INCOME, "Bank interest", "فوائد بنكية");
        role(AccountRole.BANK_SUSPENSE, "Unidentified bank receipts", "مقبوضات بنكية غير محددة");
        for (ExpenseCategory c : DIRECT_EXPENSE_CATEGORIES) {
            LABELS.put(c.reportLine(), new String[]{c.nameEn(), c.nameAr()});
        }
    }

    private static void role(AccountRole r, String en, String ar) {
        LABELS.put(r.name(), new String[]{en, ar});
    }

    /**
     * The account type a leaf must have to carry this report line: income roles on
     * INCOME, expense categories and the two expense roles on EXPENSE, and the
     * balance-sheet roles on their own side. A line on the wrong type would net an
     * expense inside "Rental income" (spec review P3-2).
     */
    public static AccountType naturalType(String key) {
        if (key == null) return null;
        if (key.startsWith("EXP_")) return AccountType.EXPENSE;
        return switch (AccountRole.valueOf(key)) {
            case RENTAL_INCOME, ADMIN_FEE, PARKING_INCOME, COOLING_CHARGES, MAINTENANCE_CHARGES, RENT_PENALTY,
                 CHEQUE_RETURN_PENALTY, OTHER_INCOME, FORFEITED_INCOME, BANK_INTEREST_INCOME -> AccountType.INCOME;
            case DISCOUNT_ALLOWED, ROUNDING_OFF, BANK_CHARGES -> AccountType.EXPENSE;
            case RENT_RECEIVABLE, PDC_RECEIVABLE, BANK, CASH, INPUT_VAT -> AccountType.ASSET;
            case ADVANCE_RENT, SECURITY_DEPOSIT, PARKING_DEPOSIT, OUTPUT_VAT, OUTPUT_VAT_DEFERRED, PDC_PAYABLE,
                 BANK_SUSPENSE -> AccountType.LIABILITY;
            case OPENING_BALANCE_DIFFERENCE -> AccountType.EQUITY;
        };
    }

    /** Every key the Chart of Accounts picker offers, roles first. */
    public static Set<String> known() {
        return java.util.Collections.unmodifiableSet(LABELS.keySet());
    }

    public static boolean isKnown(String key) {
        return key != null && LABELS.containsKey(key);
    }

    public static String labelEn(String key) {
        String[] l = LABELS.get(key);
        return l == null ? key : l[0];
    }

    public static String labelAr(String key) {
        String[] l = LABELS.get(key);
        return l == null ? null : l[1];
    }

    /**
     * The report line a leaf mapped to these roles takes: the first by ordinal
     * (spec §1 decision), or empty when it is mapped to none.
     */
    public static Optional<String> forRoles(Collection<AccountRole> roles) {
        return roles.stream().min(Comparator.comparingInt(Enum::ordinal)).map(Enum::name);
    }

    /**
     * {@code EXP_<CATEGORY>} for a generated direct-expense leaf, matched on the
     * {@code "<category> - "} prefix. The prefix rather than the full name, so a
     * leaf that survived a property rename still matches; with the separator, so
     * a hand-made "Security Deposit Refunds" does not.
     */
    public static Optional<String> expenseLineForLeafName(String name) {
        if (name == null) return Optional.empty();
        return DIRECT_EXPENSE_CATEGORIES.stream()
                .filter(c -> name.startsWith(c.leafPrefix()))
                .map(ExpenseCategory::reportLine)
                .findFirst();
    }

    /** For error messages: the accepted keys, sorted. */
    public static String knownList() {
        return String.join(", ", new TreeSet<>(LABELS.keySet()));
    }
}
