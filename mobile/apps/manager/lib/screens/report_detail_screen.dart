import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class ReportDetailScreen extends ConsumerWidget {
  final String reportType;
  final dynamic reportData;

  const ReportDetailScreen({
    super.key,
    required this.reportType,
    required this.reportData,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(reportType)),
      body: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        child: _buildReportBody(context),
      ),
    );
  }

  Widget _buildReportBody(BuildContext context) {
    switch (reportType) {
      case 'Organisation Summary':
      case 'Property Report':
      case 'Unit Report':
        return _buildIncomeExpenseReport(context);
      case 'Trial Balance':
        return _buildTrialBalanceReport(context);
      case 'VAT Return':
        return _buildVatReturnReport(context);
      case 'Vendor Ledger':
        return _buildVendorLedgerReport(context);
      default:
        return _buildGenericReport(context);
    }
  }

  Widget _buildIncomeExpenseReport(BuildContext context) {
    final data = reportData as Map<String, dynamic>;
    final totalIncome = (data['totalIncome'] ?? 0).toDouble();
    final totalExpense = (data['totalExpense'] ?? 0).toDouble();
    final netIncome =
        (data['netIncome'] ?? totalIncome - totalExpense).toDouble();
    final incomeAccounts =
        List<Map<String, dynamic>>.from(data['incomeAccounts'] ?? []);
    final expenseAccounts =
        List<Map<String, dynamic>>.from(data['expenseAccounts'] ?? []);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Summary cards row
        Row(
          children: [
            _SummaryCard(
              label: 'Total Income',
              amount: Formatters.currency(totalIncome),
              color: AppColors.success,
              icon: Icons.trending_up_rounded,
            ),
            const SizedBox(width: 10),
            _SummaryCard(
              label: 'Total Expense',
              amount: Formatters.currency(totalExpense),
              color: AppColors.danger,
              icon: Icons.trending_down_rounded,
            ),
          ],
        ),
        const SizedBox(height: 10),
        // Net income card
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: (netIncome >= 0 ? AppColors.success : AppColors.danger)
                .withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: (netIncome >= 0 ? AppColors.success : AppColors.danger)
                  .withValues(alpha: 0.2),
            ),
          ),
          child: Column(
            children: [
              Text(
                'Net ${reportType == 'Organisation Summary' ? 'Income' : 'Amount'}',
                style: const TextStyle(
                  fontSize: 13,
                  color: AppColors.textSecondary,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                Formatters.currency(netIncome),
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w700,
                  color:
                      netIncome >= 0 ? AppColors.success : AppColors.danger,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),

        // Income breakdown
        if (incomeAccounts.isNotEmpty) ...[
          Text('Income Breakdown',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...incomeAccounts.map((a) => _LineItem(
                name: a['name'] ?? '-',
                amount: (a['amount'] ?? 0).toDouble(),
                color: AppColors.success,
              )),
          const Divider(height: 24),
        ],

        // Expense breakdown
        if (expenseAccounts.isNotEmpty) ...[
          Text('Expense Breakdown',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...expenseAccounts.map((a) => _LineItem(
                name: a['name'] ?? '-',
                amount: (a['amount'] ?? 0).toDouble(),
                color: AppColors.danger,
              )),
        ],

        // Generic line items
        if (data.containsKey('lineItems')) ...[
          const Divider(height: 24),
          Text('Details', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...List<Map<String, dynamic>>.from(data['lineItems']).map(
            (item) => _TransactionRow(item: item),
          ),
        ],

        if (data.containsKey('entries')) ...[
          const Divider(height: 24),
          Text('Entries', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...List<Map<String, dynamic>>.from(data['entries']).map(
            (item) => _TransactionRow(item: item),
          ),
        ],
      ],
    );
  }

  Widget _buildTrialBalanceReport(BuildContext context) {
    final data = reportData as Map<String, dynamic>;
    final accounts =
        List<Map<String, dynamic>>.from(data['accounts'] ?? data['rows'] ?? []);
    final totalDebit = (data['totalDebit'] ?? 0).toDouble();
    final totalCredit = (data['totalCredit'] ?? 0).toDouble();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header row
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.primary.withValues(alpha: 0.08),
            borderRadius:
                const BorderRadius.vertical(top: Radius.circular(12)),
          ),
          child: const Row(
            children: [
              Expanded(
                flex: 3,
                child: Text(
                  'Account',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                  ),
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  'Debit',
                  textAlign: TextAlign.right,
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                  ),
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  'Credit',
                  textAlign: TextAlign.right,
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                  ),
                ),
              ),
            ],
          ),
        ),

        // Account rows
        ...accounts.map((a) {
          final debit = (a['debit'] ?? a['debitBalance'] ?? 0).toDouble();
          final credit = (a['credit'] ?? a['creditBalance'] ?? 0).toDouble();
          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: const BoxDecoration(
              border: Border(
                bottom: BorderSide(color: AppColors.border, width: 0.5),
              ),
            ),
            child: Row(
              children: [
                Expanded(
                  flex: 3,
                  child: Text(
                    a['accountName'] ?? a['name'] ?? '-',
                    style: const TextStyle(fontSize: 13),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: Text(
                    debit > 0 ? Formatters.currency(debit) : '-',
                    textAlign: TextAlign.right,
                    style: const TextStyle(
                      fontSize: 13,
                      color: AppColors.danger,
                    ),
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: Text(
                    credit > 0 ? Formatters.currency(credit) : '-',
                    textAlign: TextAlign.right,
                    style: const TextStyle(
                      fontSize: 13,
                      color: AppColors.success,
                    ),
                  ),
                ),
              ],
            ),
          );
        }),

        // Footer totals
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.primary.withValues(alpha: 0.08),
            borderRadius:
                const BorderRadius.vertical(bottom: Radius.circular(12)),
          ),
          child: Row(
            children: [
              const Expanded(
                flex: 3,
                child: Text(
                  'Total',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                  ),
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  Formatters.currency(totalDebit),
                  textAlign: TextAlign.right,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    color: AppColors.danger,
                  ),
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  Formatters.currency(totalCredit),
                  textAlign: TextAlign.right,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    color: AppColors.success,
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildVatReturnReport(BuildContext context) {
    final data = reportData as Map<String, dynamic>;
    final outputVat = (data['outputVat'] ?? data['vatOutput'] ?? 0).toDouble();
    final inputVat = (data['inputVat'] ?? data['vatInput'] ?? 0).toDouble();
    final netVat =
        (data['netVat'] ?? data['netVatPayable'] ?? outputVat - inputVat)
            .toDouble();
    final details =
        List<Map<String, dynamic>>.from(data['details'] ?? data['rows'] ?? []);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Summary cards
        _VatSummaryCard(
          label: 'Output VAT',
          amount: outputVat,
          color: AppColors.danger,
        ),
        const SizedBox(height: 10),
        _VatSummaryCard(
          label: 'Input VAT',
          amount: inputVat,
          color: AppColors.success,
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: AppColors.primary.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
                color: AppColors.primary.withValues(alpha: 0.2)),
          ),
          child: Column(
            children: [
              const Text(
                'Net VAT Payable',
                style: TextStyle(
                  fontSize: 13,
                  color: AppColors.textSecondary,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                Formatters.currency(netVat),
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w700,
                  color: AppColors.primary,
                ),
              ),
            ],
          ),
        ),

        if (details.isNotEmpty) ...[
          const SizedBox(height: 24),
          Text('Details', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...details.map((item) => _TransactionRow(item: item)),
        ],
      ],
    );
  }

  Widget _buildVendorLedgerReport(BuildContext context) {
    final entries = reportData is List
        ? List<Map<String, dynamic>>.from(reportData)
        : <Map<String, dynamic>>[];

    if (entries.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(40),
          child: EmptyState(
            icon: Icons.receipt_long_outlined,
            title: 'No transactions found',
          ),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: entries.map((tx) {
        final debit = (tx['debit'] ?? tx['debitAmount'] ?? 0).toDouble();
        final credit = (tx['credit'] ?? tx['creditAmount'] ?? 0).toDouble();
        final balance =
            (tx['balance'] ?? tx['runningBalance'] ?? 0).toDouble();
        final description =
            (tx['description'] ?? tx['narration'] ?? '-').toString();
        final date = tx['date'] ?? tx['transactionDate'] ?? tx['createdAt'];

        return Container(
          margin: const EdgeInsets.only(bottom: 8),
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppColors.border),
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
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    Formatters.date(date),
                    style: const TextStyle(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  if (debit > 0)
                    _LedgerAmountChip(
                      label: 'Debit',
                      amount: debit,
                      color: AppColors.danger,
                    ),
                  if (debit > 0 && credit > 0) const SizedBox(width: 8),
                  if (credit > 0)
                    _LedgerAmountChip(
                      label: 'Credit',
                      amount: credit,
                      color: AppColors.success,
                    ),
                  const Spacer(),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      const Text(
                        'Balance',
                        style: TextStyle(
                          fontSize: 10,
                          color: AppColors.textMuted,
                        ),
                      ),
                      Text(
                        Formatters.currency(balance),
                        style: const TextStyle(
                          fontWeight: FontWeight.w700,
                          fontSize: 13,
                          color: AppColors.primary,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ],
          ),
        );
      }).toList(),
    );
  }

  Widget _buildGenericReport(BuildContext context) {
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
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppColors.textMuted,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  entry.value.toString(),
                  style: const TextStyle(fontSize: 14),
                ),
              ],
            ),
          );
        }).toList(),
      );
    }

    return const EmptyState(
      icon: Icons.article_outlined,
      title: 'No data available',
    );
  }
}

class _SummaryCard extends StatelessWidget {
  final String label;
  final String amount;
  final Color color;
  final IconData icon;

  const _SummaryCard({
    required this.label,
    required this.amount,
    required this.color,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color, size: 20),
                const SizedBox(width: 8),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 13,
                    color: color.withValues(alpha: 0.8),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              amount,
              style: TextStyle(
                fontSize: 18,
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

  const _LineItem({
    required this.name,
    required this.amount,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(name, style: const TextStyle(fontSize: 14)),
          ),
          Text(
            Formatters.currency(amount),
            style: TextStyle(
              fontWeight: FontWeight.w600,
              fontSize: 14,
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

  const _TransactionRow({required this.item});

  @override
  Widget build(BuildContext context) {
    final description =
        (item['description'] ?? item['name'] ?? '-').toString();
    final amount = (item['amount'] ?? 0).toDouble();
    final date = item['date'] ?? item['transactionDate'];

    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  description,
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (date != null)
                  Text(
                    Formatters.date(date),
                    style: const TextStyle(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
              ],
            ),
          ),
          Text(
            Formatters.currency(amount),
            style: TextStyle(
              fontWeight: FontWeight.w600,
              fontSize: 13,
              color: amount >= 0 ? AppColors.success : AppColors.danger,
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

  const _VatSummaryCard({
    required this.label,
    required this.amount,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.2)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color: color,
            ),
          ),
          Text(
            Formatters.currency(amount),
            style: TextStyle(
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

class _LedgerAmountChip extends StatelessWidget {
  final String label;
  final double amount;
  final Color color;

  const _LedgerAmountChip({
    required this.label,
    required this.amount,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        '$label: ${Formatters.currency(amount)}',
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }
}
