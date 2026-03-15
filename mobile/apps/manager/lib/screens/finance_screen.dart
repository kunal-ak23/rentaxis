import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _financeServiceProvider = Provider<FinanceService>((ref) {
  final client = ref.watch(apiClientProvider);
  return FinanceService(client.dio);
});

final _accountsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_financeServiceProvider);
  return service.getAccounts();
});

final _transactionsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_financeServiceProvider);
  return service.getTransactions();
});

final _reportProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(_financeServiceProvider);
  return service.getOrganisationReport();
});

class FinanceScreen extends ConsumerStatefulWidget {
  const FinanceScreen({super.key});

  @override
  ConsumerState<FinanceScreen> createState() => _FinanceScreenState();
}

class _FinanceScreenState extends ConsumerState<FinanceScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    ref.invalidate(_accountsProvider);
    ref.invalidate(_transactionsProvider);
    ref.invalidate(_reportProvider);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Finance'),
        bottom: TabBar(
          controller: _tabController,
          labelColor: AppColors.accent,
          unselectedLabelColor: Colors.white60,
          indicatorColor: AppColors.accent,
          tabs: const [
            Tab(text: 'Accounts'),
            Tab(text: 'Transactions'),
            Tab(text: 'Reports'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _AccountsTab(onRefresh: _refresh),
          _TransactionsTab(onRefresh: _refresh),
          _ReportsTab(onRefresh: _refresh),
        ],
      ),
    );
  }
}

// --- Accounts Tab ---
class _AccountsTab extends ConsumerWidget {
  final VoidCallback onRefresh;
  const _AccountsTab({required this.onRefresh});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accountsAsync = ref.watch(_accountsProvider);

    return accountsAsync.when(
      loading: () => const Center(
        child: CircularProgressIndicator(color: AppColors.primary),
      ),
      error: (e, _) => ErrorState(
        message: 'Failed to load accounts',
        onRetry: onRefresh,
      ),
      data: (accounts) {
        if (accounts.isEmpty) {
          return const EmptyState(
            icon: Icons.account_balance_outlined,
            title: 'No accounts found',
          );
        }

        return RefreshIndicator(
          onRefresh: () async => onRefresh(),
          color: AppColors.primary,
          child: ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            itemCount: accounts.length,
            itemBuilder: (context, index) {
              final account = accounts[index];
              final accountType = account['type'] ?? account['accountType'] ?? '';
              final typeColor = _accountTypeColor(accountType);

              return Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppColors.border),
                ),
                child: Row(
                  children: [
                    Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: typeColor.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Center(
                        child: Text(
                          account['code'] ?? '-',
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w700,
                            color: typeColor,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            account['name'] ?? '-',
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 14,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            account['description'] ?? '',
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                    StatusBadge(label: accountType, color: typeColor),
                  ],
                ),
              );
            },
          ),
        );
      },
    );
  }

  Color _accountTypeColor(String type) {
    return switch (type) {
      'ASSET' => AppColors.info,
      'LIABILITY' => AppColors.danger,
      'EQUITY' => AppColors.primary,
      'INCOME' => AppColors.success,
      'EXPENSE' => AppColors.warning,
      _ => AppColors.textMuted,
    };
  }
}

// --- Transactions Tab ---
class _TransactionsTab extends ConsumerWidget {
  final VoidCallback onRefresh;
  const _TransactionsTab({required this.onRefresh});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final transactionsAsync = ref.watch(_transactionsProvider);

    return transactionsAsync.when(
      loading: () => const Center(
        child: CircularProgressIndicator(color: AppColors.primary),
      ),
      error: (e, _) => ErrorState(
        message: 'Failed to load transactions',
        onRetry: onRefresh,
      ),
      data: (transactions) {
        if (transactions.isEmpty) {
          return const EmptyState(
            icon: Icons.swap_horiz_outlined,
            title: 'No transactions found',
          );
        }

        return RefreshIndicator(
          onRefresh: () async => onRefresh(),
          color: AppColors.primary,
          child: ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            itemCount: transactions.length,
            itemBuilder: (context, index) {
              final tx = transactions[index];
              final debit = (tx['debitAmount'] ?? 0).toDouble();
              final credit = (tx['creditAmount'] ?? 0).toDouble();
              final isDebit = debit > 0;

              return Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppColors.border),
                ),
                child: Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: (isDebit ? AppColors.danger : AppColors.success)
                            .withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Icon(
                        isDebit
                            ? Icons.arrow_upward_rounded
                            : Icons.arrow_downward_rounded,
                        color:
                            isDebit ? AppColors.danger : AppColors.success,
                        size: 18,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            tx['accountName'] ?? tx['description'] ?? '-',
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 2),
                          Text(
                            Formatters.date(tx['transactionDate'] ??
                                tx['createdAt']),
                            style: const TextStyle(
                              fontSize: 11,
                              color: AppColors.textMuted,
                            ),
                          ),
                          if (tx['description'] != null &&
                              tx['accountName'] != null)
                            Text(
                              tx['description'],
                              style: const TextStyle(
                                fontSize: 11,
                                color: AppColors.textSecondary,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                        ],
                      ),
                    ),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        if (debit > 0)
                          Text(
                            Formatters.currency(debit),
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                              color: AppColors.danger,
                            ),
                          ),
                        if (credit > 0)
                          Text(
                            Formatters.currency(credit),
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                              color: AppColors.success,
                            ),
                          ),
                        Text(
                          isDebit ? 'Debit' : 'Credit',
                          style: const TextStyle(
                            fontSize: 10,
                            color: AppColors.textMuted,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              );
            },
          ),
        );
      },
    );
  }
}

// --- Reports Tab ---
class _ReportsTab extends ConsumerWidget {
  final VoidCallback onRefresh;
  const _ReportsTab({required this.onRefresh});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportAsync = ref.watch(_reportProvider);

    return reportAsync.when(
      loading: () => const Center(
        child: CircularProgressIndicator(color: AppColors.primary),
      ),
      error: (e, _) => ErrorState(
        message: 'Failed to load report',
        onRetry: onRefresh,
      ),
      data: (report) {
        final totalIncome = (report['totalIncome'] ?? 0).toDouble();
        final totalExpense = (report['totalExpense'] ?? 0).toDouble();
        final netIncome = (report['netIncome'] ?? totalIncome - totalExpense).toDouble();
        final incomeAccounts =
            List<Map<String, dynamic>>.from(report['incomeAccounts'] ?? []);
        final expenseAccounts =
            List<Map<String, dynamic>>.from(report['expenseAccounts'] ?? []);

        return RefreshIndicator(
          onRefresh: () async => onRefresh(),
          color: AppColors.primary,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Organisation P&L',
                    style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 16),

                // Summary cards
                Row(
                  children: [
                    _ReportSummaryCard(
                      label: 'Income',
                      amount: Formatters.currency(totalIncome),
                      color: AppColors.success,
                      icon: Icons.trending_up_rounded,
                    ),
                    const SizedBox(width: 10),
                    _ReportSummaryCard(
                      label: 'Expenses',
                      amount: Formatters.currency(totalExpense),
                      color: AppColors.danger,
                      icon: Icons.trending_down_rounded,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: (netIncome >= 0 ? AppColors.success : AppColors.danger)
                        .withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: (netIncome >= 0
                              ? AppColors.success
                              : AppColors.danger)
                          .withValues(alpha: 0.2),
                    ),
                  ),
                  child: Column(
                    children: [
                      const Text('Net Income',
                          style: TextStyle(
                              fontSize: 13,
                              color: AppColors.textSecondary)),
                      const SizedBox(height: 4),
                      Text(
                        Formatters.currency(netIncome),
                        style: TextStyle(
                          fontSize: 24,
                          fontWeight: FontWeight.w700,
                          color: netIncome >= 0
                              ? AppColors.success
                              : AppColors.danger,
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
                  ...incomeAccounts.map((a) => _ReportLineItem(
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
                  ...expenseAccounts.map((a) => _ReportLineItem(
                        name: a['name'] ?? '-',
                        amount: (a['amount'] ?? 0).toDouble(),
                        color: AppColors.danger,
                      )),
                ],
              ],
            ),
          ),
        );
      },
    );
  }
}

class _ReportSummaryCard extends StatelessWidget {
  final String label;
  final String amount;
  final Color color;
  final IconData icon;

  const _ReportSummaryCard({
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
                Text(label,
                    style: TextStyle(
                      fontSize: 13,
                      color: color.withValues(alpha: 0.8),
                    )),
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

class _ReportLineItem extends StatelessWidget {
  final String name;
  final double amount;
  final Color color;

  const _ReportLineItem({
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
            child: Text(
              name,
              style: const TextStyle(fontSize: 14),
            ),
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
