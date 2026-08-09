import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class ReportDetailScreen extends ConsumerWidget {
  /// Stable machine key: orgSummary | trialBalance | vatReturn |
  /// propertyReport | unitReport | vendorLedger.
  final String reportType;
  final String reportTitle;
  final dynamic reportData;

  const ReportDetailScreen({
    super.key,
    required this.reportType,
    required this.reportTitle,
    required this.reportData,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(title: reportTitle, l: l),
          Expanded(
            child: SingleChildScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              child: _buildReportBody(context, l),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildReportBody(BuildContext context, _L l) {
    switch (reportType) {
      case 'orgSummary':
      case 'propertyReport':
      case 'unitReport':
        return _buildIncomeExpenseReport(context, l);
      case 'trialBalance':
        return _buildTrialBalanceReport(context, l);
      case 'vatReturn':
        return _buildVatReturnReport(context, l);
      case 'vendorLedger':
        return _buildVendorLedgerReport(context, l);
      default:
        return _buildGenericReport(context, l);
    }
  }

  Widget _buildIncomeExpenseReport(BuildContext context, _L l) {
    final m = context.miftah;
    final data = reportData as Map<String, dynamic>;
    final totalIncome = (data['totalIncome'] ?? 0).toDouble();
    final totalExpense = (data['totalExpenses'] ?? 0).toDouble();
    final netIncome = (data['netProfit'] ?? totalIncome - totalExpense)
        .toDouble();
    final incomeAccounts = _breakdownItems(data['incomeBreakdown']);
    final expenseAccounts = [
      ..._breakdownItems(data['directExpenseBreakdown']),
      ..._breakdownItems(data['indirectExpenseBreakdown']),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _SummaryCard(
              label: l.totalIncome,
              amount: Formatters.currency(totalIncome),
              color: m.success,
              icon: Icons.trending_up_rounded,
              l: l,
            ),
            const SizedBox(width: 10),
            _SummaryCard(
              label: l.totalExpense,
              amount: Formatters.currency(totalExpense),
              color: m.danger,
              icon: Icons.trending_down_rounded,
              l: l,
            ),
          ],
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: (netIncome >= 0 ? m.success : m.danger).withValues(
              alpha: 0.08,
            ),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: (netIncome >= 0 ? m.success : m.danger).withValues(
                alpha: 0.2,
              ),
            ),
          ),
          child: Column(
            children: [
              Text(
                reportType == 'orgSummary' ? l.netIncome : l.netAmount,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 13,
                        color: m.textSecondary,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 12,
                        letterSpacing: 1.2,
                        color: m.textSecondary,
                      ),
              ),
              const SizedBox(height: 6),
              Text(
                Formatters.currency(netIncome),
                style: GoogleFonts.cinzel(
                  fontSize: 24,
                  fontWeight: FontWeight.w700,
                  color: netIncome >= 0 ? m.success : m.danger,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),
        if (incomeAccounts.isNotEmpty) ...[
          _sectionLabel(l.incomeBreakdown, l.ar, m),
          const SizedBox(height: 8),
          ...incomeAccounts.map(
            (a) => _LineItem(
              name: a['name'] ?? '-',
              amount: (a['amount'] ?? 0).toDouble(),
              color: m.success,
              ar: l.ar,
            ),
          ),
          Divider(height: 24, color: m.divider),
        ],
        if (expenseAccounts.isNotEmpty) ...[
          _sectionLabel(l.expenseBreakdown, l.ar, m),
          const SizedBox(height: 8),
          ...expenseAccounts.map(
            (a) => _LineItem(
              name: a['name'] ?? '-',
              amount: (a['amount'] ?? 0).toDouble(),
              color: m.danger,
              ar: l.ar,
            ),
          ),
        ],
        if (data.containsKey('lineItems')) ...[
          Divider(height: 24, color: m.divider),
          _sectionLabel(l.details, l.ar, m),
          const SizedBox(height: 8),
          ...List<Map<String, dynamic>>.from(
            data['lineItems'],
          ).map((item) => _TransactionRow(item: item, ar: l.ar)),
        ],
        if (data.containsKey('entries')) ...[
          Divider(height: 24, color: m.divider),
          _sectionLabel(l.entries, l.ar, m),
          const SizedBox(height: 8),
          ...List<Map<String, dynamic>>.from(
            data['entries'],
          ).map((item) => _TransactionRow(item: item, ar: l.ar)),
        ],
      ],
    );
  }

  Widget _buildTrialBalanceReport(BuildContext context, _L l) {
    final m = context.miftah;
    final data = reportData as Map<String, dynamic>;
    // Backend TrialBalanceDTO serializes the per-account rows as `lines`.
    final accounts = List<Map<String, dynamic>>.from(data['lines'] ?? []);
    final totalDebit = (data['totalDebit'] ?? 0).toDouble();
    final totalCredit = (data['totalCredit'] ?? 0).toDouble();

    TextStyle headStyle() => l.ar
        ? GoogleFonts.notoNaskhArabic(
            fontWeight: FontWeight.w700,
            fontSize: 13,
            color: m.textPrimary,
          )
        : GoogleFonts.josefinSans(
            fontWeight: FontWeight.w700,
            fontSize: 12.5,
            letterSpacing: 0.6,
            color: m.textPrimary,
          );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.accentDark.withValues(alpha: 0.08),
            borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
          ),
          child: Row(
            children: [
              Expanded(flex: 3, child: Text(l.account, style: headStyle())),
              Expanded(
                flex: 2,
                child: Text(
                  l.debit,
                  textAlign: TextAlign.right,
                  style: headStyle(),
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  l.credit,
                  textAlign: TextAlign.right,
                  style: headStyle(),
                ),
              ),
            ],
          ),
        ),
        ...accounts.map((a) {
          final debit = (a['debit'] ?? a['debitBalance'] ?? 0).toDouble();
          final credit = (a['credit'] ?? a['creditBalance'] ?? 0).toDouble();
          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              border: Border(bottom: BorderSide(color: m.divider, width: 0.5)),
            ),
            child: Row(
              children: [
                Expanded(
                  flex: 3,
                  child: Text(
                    a['accountName'] ?? a['name'] ?? '-',
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13.5,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 13,
                            color: m.textPrimary,
                          ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: Text(
                    debit > 0 ? Formatters.currency(debit) : '-',
                    textAlign: TextAlign.right,
                    style: GoogleFonts.cinzel(fontSize: 13, color: m.danger),
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: Text(
                    credit > 0 ? Formatters.currency(credit) : '-',
                    textAlign: TextAlign.right,
                    style: GoogleFonts.cinzel(fontSize: 13, color: m.success),
                  ),
                ),
              ],
            ),
          );
        }),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.accentDark.withValues(alpha: 0.08),
            borderRadius: const BorderRadius.vertical(
              bottom: Radius.circular(12),
            ),
          ),
          child: Row(
            children: [
              Expanded(flex: 3, child: Text(l.total, style: headStyle())),
              Expanded(
                flex: 2,
                child: Text(
                  Formatters.currency(totalDebit),
                  textAlign: TextAlign.right,
                  style: GoogleFonts.cinzel(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    color: m.danger,
                  ),
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  Formatters.currency(totalCredit),
                  textAlign: TextAlign.right,
                  style: GoogleFonts.cinzel(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    color: m.success,
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildVatReturnReport(BuildContext context, _L l) {
    final m = context.miftah;
    final data = reportData as Map<String, dynamic>;
    // Backend VatReturnDTO field names: totalOutputVat / totalInputVat /
    // netVatPayable, with line items split into salesLines + purchaseLines.
    final outputVat = (data['totalOutputVat'] ?? 0).toDouble();
    final inputVat = (data['totalInputVat'] ?? 0).toDouble();
    final netVat = (data['netVatPayable'] ?? outputVat - inputVat).toDouble();
    final salesLines = List<Map<String, dynamic>>.from(
      data['salesLines'] ?? [],
    );
    final purchaseLines = List<Map<String, dynamic>>.from(
      data['purchaseLines'] ?? [],
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _VatSummaryCard(
          label: l.outputVat,
          amount: outputVat,
          color: m.danger,
          l: l,
        ),
        const SizedBox(height: 10),
        _VatSummaryCard(
          label: l.inputVat,
          amount: inputVat,
          color: m.success,
          l: l,
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: AppColors.primary.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: AppColors.primary.withValues(alpha: 0.2)),
          ),
          child: Column(
            children: [
              Text(
                l.netVatPayable,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 13,
                        color: m.textSecondary,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 12,
                        letterSpacing: 1.2,
                        color: m.textSecondary,
                      ),
              ),
              const SizedBox(height: 6),
              Text(
                Formatters.currency(netVat),
                style: GoogleFonts.cinzel(
                  fontSize: 24,
                  fontWeight: FontWeight.w700,
                  color: m.textPrimary,
                ),
              ),
            ],
          ),
        ),
        if (salesLines.isNotEmpty) ...[
          const SizedBox(height: 24),
          _sectionLabel(l.salesVatLines, l.ar, m),
          const SizedBox(height: 8),
          ...salesLines.map(
            (item) => _VatLineRow(item: item, color: m.danger, l: l),
          ),
        ],
        if (purchaseLines.isNotEmpty) ...[
          const SizedBox(height: 24),
          _sectionLabel(l.purchaseVatLines, l.ar, m),
          const SizedBox(height: 8),
          ...purchaseLines.map(
            (item) => _VatLineRow(item: item, color: m.success, l: l),
          ),
        ],
      ],
    );
  }

  Widget _buildVendorLedgerReport(BuildContext context, _L l) {
    final entries = reportData is List
        ? List<Map<String, dynamic>>.from(reportData)
        : <Map<String, dynamic>>[];

    if (entries.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(40),
          child: EmptyState(
            icon: Icons.receipt_long_outlined,
            title: l.noTransactions,
          ),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: entries.map((tx) {
        final m = context.miftah;
        final debit = (tx['debit'] ?? tx['debitAmount'] ?? 0).toDouble();
        final credit = (tx['credit'] ?? tx['creditAmount'] ?? 0).toDouble();
        final description = (tx['description'] ?? tx['narration'] ?? '-')
            .toString();
        final date = tx['date'] ?? tx['transactionDate'] ?? tx['createdAt'];

        return Container(
          margin: const EdgeInsets.only(bottom: 10),
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: m.surface,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: m.border),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Text(
                      description,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 13.5,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                              color: m.textPrimary,
                            ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    Formatters.date(date, ar: l.ar),
                    style: GoogleFonts.josefinSans(
                      fontSize: 11,
                      color: m.textMuted,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  if (debit > 0)
                    _LedgerAmountChip(
                      label: l.debit,
                      amount: debit,
                      color: m.danger,
                      ar: l.ar,
                    ),
                  if (debit > 0 && credit > 0) const SizedBox(width: 8),
                  if (credit > 0)
                    _LedgerAmountChip(
                      label: l.credit,
                      amount: credit,
                      color: m.success,
                      ar: l.ar,
                    ),
                ],
              ),
            ],
          ),
        );
      }).toList(),
    );
  }

  Widget _buildGenericReport(BuildContext context, _L l) {
    final m = context.miftah;
    if (reportData is Map<String, dynamic>) {
      final data = reportData as Map<String, dynamic>;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: data.entries.map((entry) {
          return Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.key,
                  style: GoogleFonts.josefinSans(
                    fontSize: 11.5,
                    color: m.textMuted,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  entry.value.toString(),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 14,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 14,
                          color: m.textPrimary,
                        ),
                ),
              ],
            ),
          );
        }).toList(),
      );
    }

    return EmptyState(icon: Icons.article_outlined, title: l.noData);
  }

  Widget _sectionLabel(String text, bool ar, MiftahColors m) {
    return Text(
      ar ? text : text.toUpperCase(),
      style: ar
          ? GoogleFonts.notoNaskhArabic(
              fontSize: 13.5,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            )
          : GoogleFonts.josefinSans(
              fontSize: 12,
              letterSpacing: 1.6,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            ),
    );
  }
}

/// Flattens a ReportDTO breakdown map (`{'code - name': amount}`) into
/// name/amount rows for [_LineItem].
List<Map<String, dynamic>> _breakdownItems(dynamic breakdown) {
  if (breakdown is! Map) return const [];
  return breakdown.entries
      .map((e) => <String, dynamic>{'name': e.key.toString(), 'amount': e.value})
      .toList();
}

class _ChromeHeader extends StatelessWidget {
  final String title;
  final _L l;
  const _ChromeHeader({required this.title, required this.l});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(8, 4, 20, 18),
      child: SafeArea(
        bottom: false,
        child: Row(
          children: [
            IconButton(
              onPressed: () => Navigator.of(context).maybePop(),
              icon: Icon(
                context.isAr ? Icons.chevron_right : Icons.chevron_left,
                color: AppColors.accent,
                size: 26,
              ),
            ),
            Flexible(
              child: Text(
                title,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.cinzel(
                        fontSize: 15,
                        letterSpacing: 1.6,
                        color: Colors.white,
                      ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  final String label;
  final String amount;
  final Color color;
  final IconData icon;
  final _L l;

  const _SummaryCard({
    required this.label,
    required this.amount,
    required this.color,
    required this.icon,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color, size: 20),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    label,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13,
                            color: color.withValues(alpha: 0.85),
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 12,
                            letterSpacing: 0.8,
                            color: color.withValues(alpha: 0.85),
                          ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              amount,
              style: GoogleFonts.cinzel(
                fontSize: 17,
                fontWeight: FontWeight.w700,
                color: color,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LineItem extends StatelessWidget {
  final String name;
  final double amount;
  final Color color;
  final bool ar;

  const _LineItem({
    required this.name,
    required this.amount,
    required this.color,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              name,
              style: ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 14.5,
                      color: m.textPrimary,
                    )
                  : GoogleFonts.josefinSans(fontSize: 14, color: m.textPrimary),
            ),
          ),
          Text(
            Formatters.currency(amount),
            style: GoogleFonts.cinzel(
              fontWeight: FontWeight.w600,
              fontSize: 13,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

class _TransactionRow extends StatelessWidget {
  final Map<String, dynamic> item;
  final bool ar;

  const _TransactionRow({required this.item, required this.ar});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final description = (item['description'] ?? item['name'] ?? '-').toString();
    final amount = (item['amount'] ?? 0).toDouble();
    final date = item['date'] ?? item['transactionDate'];

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: m.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  description,
                  style: ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (date != null)
                  Text(
                    Formatters.date(date, ar: ar),
                    style: GoogleFonts.josefinSans(
                      fontSize: 11,
                      color: m.textMuted,
                    ),
                  ),
              ],
            ),
          ),
          Text(
            Formatters.currency(amount),
            style: GoogleFonts.cinzel(
              fontWeight: FontWeight.w600,
              fontSize: 13,
              color: amount >= 0 ? m.success : m.danger,
            ),
          ),
        ],
      ),
    );
  }
}

class _VatSummaryCard extends StatelessWidget {
  final String label;
  final double amount;
  final Color color;
  final _L l;

  const _VatSummaryCard({
    required this.label,
    required this.amount,
    required this.color,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: color.withValues(alpha: 0.2)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: color,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: color,
                  ),
          ),
          Text(
            Formatters.currency(amount),
            style: GoogleFonts.cinzel(
              fontSize: 18,
              fontWeight: FontWeight.w700,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

/// One VatReturnDTO.VatLine: description, taxableAmount, vatAmount.
class _VatLineRow extends StatelessWidget {
  final Map<String, dynamic> item;
  final Color color;
  final _L l;

  const _VatLineRow({required this.item, required this.color, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final description = (item['description'] ?? '-').toString();
    final taxableAmount = (item['taxableAmount'] ?? 0).toDouble();
    final vatAmount = (item['vatAmount'] ?? 0).toDouble();

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: m.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  description,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                Text(
                  '${l.taxable}: ${Formatters.currency(taxableAmount)}',
                  style: GoogleFonts.josefinSans(
                    fontSize: 11,
                    color: m.textMuted,
                  ),
                ),
              ],
            ),
          ),
          Text(
            Formatters.currency(vatAmount),
            style: GoogleFonts.cinzel(
              fontWeight: FontWeight.w600,
              fontSize: 13,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

class _LedgerAmountChip extends StatelessWidget {
  final String label;
  final double amount;
  final Color color;
  final bool ar;

  const _LedgerAmountChip({
    required this.label,
    required this.amount,
    required this.color,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '$label: ${Formatters.currency(amount)}',
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.josefinSans(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: color,
              ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get totalIncome => ar ? 'إجمالي الدخل' : 'Total Income';
  String get totalExpense => ar ? 'إجمالي المصاريف' : 'Total Expense';
  String get netIncome => ar ? 'صافي الدخل' : 'Net Income';
  String get netAmount => ar ? 'صافي المبلغ' : 'Net Amount';
  String get incomeBreakdown => ar ? 'تفاصيل الدخل' : 'Income Breakdown';
  String get expenseBreakdown => ar ? 'تفاصيل المصاريف' : 'Expense Breakdown';
  String get details => ar ? 'التفاصيل' : 'Details';
  String get entries => ar ? 'القيود' : 'Entries';
  String get account => ar ? 'الحساب' : 'Account';
  String get debit => ar ? 'مدين' : 'Debit';
  String get credit => ar ? 'دائن' : 'Credit';
  String get total => ar ? 'الإجمالي' : 'Total';
  String get outputVat => ar ? 'ضريبة مخرجات' : 'Output VAT';
  String get inputVat => ar ? 'ضريبة مدخلات' : 'Input VAT';
  String get netVatPayable => ar ? 'صافي الضريبة المستحقة' : 'Net VAT Payable';
  String get salesVatLines =>
      ar ? 'المبيعات (ضريبة المخرجات)' : 'Sales (Output VAT)';
  String get purchaseVatLines =>
      ar ? 'المشتريات (ضريبة المدخلات)' : 'Purchases (Input VAT)';
  String get taxable => ar ? 'الخاضع للضريبة' : 'Taxable';
  String get noTransactions => ar ? 'لا توجد معاملات' : 'No transactions found';
  String get noData => ar ? 'لا توجد بيانات' : 'No data available';
}
