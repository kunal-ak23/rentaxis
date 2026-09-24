package com.datagami.rentaxis.core.service.report.statement;

import java.util.HashMap;
import java.util.Map;

/**
 * English and Arabic labels for the statement pack's PDF and CSV. The web
 * translates the same keys from its own message files (namespace
 * {@code PropertyReports}); a key missing here prints as itself.
 */
public final class StatementLabels {

    private StatementLabels() { }

    private static final Map<String, String[]> L = new HashMap<>();

    private static void put(String key, String en, String ar) {
        L.put(key, new String[]{en, ar});
    }

    static {
        put("title", "Property statement", "كشف حساب العقار");
        put("period", "Period", "الفترة");
        put("property", "Property", "العقار");
        put("final", "Figures final", "أرقام نهائية");
        put("provisional", "Provisional — period not locked", "مبدئي — الفترة غير مقفلة");
        put("generated", "Generated", "تاريخ الإنشاء");
        put("by", "by", "بواسطة");

        put("source.LEDGER", "Ledger", "دفتر الأستاذ");
        put("source.REGISTER", "Cheque register", "سجل الشيكات");
        put("source.MIXED", "Register and ledger", "السجل ودفتر الأستاذ");
        put("source.SUBLEDGER", "Supplier sub-ledger", "دفتر الموردين");
        put("source.DERIVED", "Derived", "محسوب");

        put("section.pnl", "Income, expenses and NOI", "الإيرادات والمصروفات وصافي الدخل التشغيلي");
        put("section.instalments", "Instalments due", "الأقساط المستحقة");
        put("section.collected", "Collected", "المحصّل");
        put("section.outstanding", "Outstanding", "المستحقات القائمة");
        put("section.deposits", "Deposits held", "التأمينات المحتفظ بها");
        put("section.expensesIncurred", "Expenses incurred", "المصروفات المتكبدة");
        put("section.expensesPaid", "Expenses paid", "المصروفات المدفوعة");
        put("section.vat", "VAT", "ضريبة القيمة المضافة");
        put("section.netCash", "Net property cash movement", "صافي حركة النقد للعقار");

        put("figure.income", "Income", "الإيرادات");
        put("figure.expenses", "Expenses", "المصروفات");
        put("figure.noi", "NOI", "صافي الدخل التشغيلي");
        put("figure.priorIncome", "Income, prior period", "الإيرادات، الفترة السابقة");
        put("figure.priorExpenses", "Expenses, prior period", "المصروفات، الفترة السابقة");
        put("figure.priorNoi", "NOI, prior period", "صافي الدخل، الفترة السابقة");
        put("figure.gross", "Instalments (gross)", "الأقساط (الإجمالي)");
        put("figure.vat", "of which VAT", "منها ضريبة القيمة المضافة");
        put("figure.cleared", "Cleared", "مُحصّل");
        put("figure.pending", "Pending", "قيد الانتظار");
        put("figure.bounced", "Bounced", "مرتجع");
        put("figure.bouncedAfterClearing", "Less: bounced after clearing", "ناقص: مرتجع بعد التحصيل");
        put("figure.collected", "Collected", "المحصّل");
        put("figure.registerOverdue", "Overdue (register)", "متأخر (السجل)");
        put("figure.ledgerReceivable", "Rent receivable (ledger)", "إيجارات مستحقة (دفتر الأستاذ)");
        put("figure.opening", "Opening balance", "الرصيد الافتتاحي");
        put("figure.received", "Received", "المستلم");
        put("figure.applied", "Applied to deductions and arrears", "المخصوم مقابل الاستقطاعات والمتأخرات");
        put("figure.refunded", "Refunded in cash", "المسترد نقداً");
        put("figure.carried", "Carried to another lease", "المرحّل إلى عقد آخر");
        put("figure.closing", "Closing balance", "الرصيد الختامي");
        put("figure.net", "Net", "الصافي");
        put("figure.paid", "Paid", "المدفوع");
        put("figure.allocatedPaid", "Paid against this property's invoices", "المدفوع مقابل فواتير هذا العقار");
        put("figure.directPaid", "Paid directly to expenses", "المدفوع مباشرة للمصروفات");
        put("figure.unallocatedPayments", "Supplier payments not yet allocated (not included)", "دفعات موردين غير مخصصة بعد (غير مشمولة)");
        put("figure.outputVat", "Output VAT", "ضريبة المخرجات");
        put("figure.inputVat", "Input VAT", "ضريبة المدخلات");
        put("figure.expensesPaid", "Less: expenses paid", "ناقص: المصروفات المدفوعة");
        put("figure.depositsRefunded", "Less: deposits refunded", "ناقص: التأمينات المستردة");
        put("figure.netCash", "Net property cash movement", "صافي حركة النقد للعقار");

        put("column.type", "Type", "النوع");
        put("column.line", "Line", "البند");
        put("column.lineAr", "Line (AR)", "البند");
        put("column.amount", "Amount", "المبلغ");
        put("column.prior", "Prior", "السابق");
        put("column.delta", "Change", "التغير");
        put("column.mode", "Mode", "طريقة الدفع");
        put("column.renter", "Renter", "المستأجر");
        put("column.unit", "Unit", "الوحدة");
        put("column.chequeNumber", "Cheque no.", "رقم الشيك");
        put("column.chequeDate", "Cheque date", "تاريخ الشيك");
        put("column.daysOverdue", "Days overdue", "أيام التأخير");
        put("column.entryNumber", "Entry", "القيد");
        put("column.date", "Date", "التاريخ");
        put("column.docType", "Doc", "المستند");
        put("column.voucherNumber", "Voucher", "السند");
        put("column.vendor", "Vendor", "المورد");
        put("column.invoiceNumber", "Invoice", "الفاتورة");
        put("column.net", "Net", "الصافي");
        put("column.vat", "VAT", "الضريبة");
        put("column.emirate", "Emirate", "الإمارة");
        put("column.outputVat", "Output VAT", "ضريبة المخرجات");
        put("column.basis", "Basis", "الأساس");

        put("basis.ALLOCATED", "Allocated", "مخصص");
        put("basis.RELEASED", "Released", "ملغى التخصيص");
        put("basis.DIRECT", "Direct", "مباشر");

        put("note.register", "Operational figures from the cheque register; not a ledger balance.",
                "أرقام تشغيلية من سجل الشيكات، وليست رصيداً دفترياً.");
        put("note.differsByDesign", "The register and the ledger differ by design: the contract bills rent in advance.",
                "يختلف السجل عن دفتر الأستاذ عن قصد: العقد يفوتر الإيجار مقدماً.");
        put("note.directPaymentsOnly", "Only payment vouchers that debit an expense directly are shown until supplier allocation lands.",
                "تظهر فقط سندات الصرف التي تقيد المصروف مباشرة إلى أن يتوفر تخصيص الموردين.");
        put("note.paidBySubledger", "Supplier payments count when allocated to an invoice of this property, at the property's share of the invoice; a released allocation comes back off.",
                "تُحتسب دفعات الموردين عند تخصيصها لفاتورة تخص هذا العقار، بحصة العقار من الفاتورة؛ ويُخصم التخصيص الملغى.");
        put("note.unallocatedNotAttributable", "Payments below are not allocated to an invoice yet, so they are not attributable to a property and are not included above.",
                "الدفعات أدناه غير مخصصة لفاتورة بعد، لذا لا تُنسب إلى عقار ولا تدخل في الأرقام أعلاه.");
        put("note.pdcCountedWhenIssued", "Some payments above are post-dated cheques the bank had not paid by the end of the period; they count when allocated, and leave the bank when presented.",
                "بعض الدفعات أعلاه شيكات مؤجلة لم يصرفها البنك حتى نهاية الفترة؛ تُحتسب عند تخصيصها، وتخرج من البنك عند تقديمها.");
        put("note.inputVatHeaderProperty", "Input VAT on a voucher with no header property is under Unassigned.",
                "ضريبة المدخلات على سند بلا عقار في رأسه تظهر ضمن غير المخصص.");
        put("note.notCashAtBank", "Net property cash movement, not cash at bank: a bank account can serve several properties.",
                "صافي حركة النقد للعقار وليس رصيد البنك: قد يخدم الحساب البنكي عدة عقارات.");

        put("mode.PDC", "Cheque", "شيك");
        put("mode.CASH", "Cash", "نقداً");
        put("mode.TRANSFER", "Transfer", "تحويل");
        put("mode.ONLINE", "Online", "عبر الإنترنت");
        put("mode.OTHER", "Other", "أخرى");
        put("type.INCOME", "Income", "الإيرادات");
        put("type.EXPENSE", "Expense", "المصروفات");

        put("pnl.title", "Property P&L", "قائمة دخل العقارات");
        put("pnl.group", "Group", "المجموعة");
        put("pnl.line", "Line", "البند");
        put("pnl.income", "Income", "الإيرادات");
        put("pnl.expenses", "Expenses", "المصروفات");
        put("pnl.noi", "NOI", "صافي الدخل التشغيلي");
        put("pnl.prior", "prior", "السابق");
        put("pnl.delta", "Δ", "Δ");
        put("pnl.deltaPct", "Δ%", "Δ%");
        put("pnl.allocated", "Shared costs allocated (report only, not posted)", "تكاليف مشتركة موزعة (للتقرير فقط، غير مرحّلة)");
        put("pnl.allocatedOthers", "Allocated to properties not on this report", "موزع على عقارات خارج هذا التقرير");
        put("pnl.noiAfter", "NOI after allocation", "صافي الدخل بعد التوزيع");
        put("pnl.check", "Check: ledger movement less report total", "التحقق: حركة الدفتر ناقص إجمالي التقرير");
        put("pnl.UNASSIGNED", "Unassigned", "غير مخصص");
        put("pnl.TOTAL", "Total", "الإجمالي");
    }

    /** The label in {@code lang} ("ar" or anything else for English); the key itself when unknown. */
    public static String of(String key, String lang) {
        String[] l = L.get(key);
        if (l == null) return key;
        return "ar".equals(lang) && l[1] != null ? l[1] : l[0];
    }
}
