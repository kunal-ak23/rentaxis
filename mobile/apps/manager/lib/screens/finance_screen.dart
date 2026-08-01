import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _financeServiceProvider = Provider<FinanceService>((ref) {
  final client = ref.watch(apiClientProvider);
  return FinanceService(client.dio);
});

final _accountsProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_financeServiceProvider);
  return service.getAccounts();
});

final _transactionsProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_financeServiceProvider);
  return service.getTransactions();
});

final _reportProvider = FutureProvider.autoDispose<Map<String, dynamic>>((
  ref,
) async {
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
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(l: l, tabController: _tabController),
          Expanded(
            child: TabBarView(
              controller: _tabController,
              children: [
                _AccountsTab(onRefresh: _refresh, l: l),
                _TransactionsTab(onRefresh: _refresh, l: l),
                _ReportsTab(onRefresh: _refresh, l: l),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ChromeHeader extends StatelessWidget {
  final _L l;
  final TabController tabController;
  const _ChromeHeader({required this.l, required this.tabController});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 4, 20, 6),
              child: Row(
                children: [
                  IconButton(
                    onPressed: () => context.pop(),
                    icon: Icon(
                      context.isAr ? Icons.chevron_right : Icons.chevron_left,
                      color: AppColors.accent,
                      size: 26,
                    ),
                  ),
                  Text(
                    l.title,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 18,
                            fontWeight: FontWeight.w600,
                            color: Colors.white,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 16,
                            letterSpacing: 2.4,
                            color: Colors.white,
                          ),
                  ),
                ],
              ),
            ),
            TabBar(
              controller: tabController,
              labelColor: AppColors.accent,
              unselectedLabelColor: Colors.white60,
              indicatorColor: AppColors.accent,
              labelStyle: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 11.5,
                      letterSpacing: 1.4,
                      fontWeight: FontWeight.w600,
                    ),
              unselectedLabelStyle: l.ar
                  ? GoogleFonts.notoNaskhArabic(fontSize: 13)
                  : GoogleFonts.josefinSans(fontSize: 11.5, letterSpacing: 1.4),
              tabs: [
                Tab(text: l.ar ? l.accounts : l.accounts.toUpperCase()),
                Tab(text: l.ar ? l.transactions : l.transactions.toUpperCase()),
                Tab(text: l.ar ? l.reports : l.reports.toUpperCase()),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// --- Accounts Tab ---
class _AccountsTab extends ConsumerWidget {
  final VoidCallback onRefresh;
  final _L l;
  const _AccountsTab({required this.onRefresh, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accountsAsync = ref.watch(_accountsProvider);
    final m = context.miftah;

    return accountsAsync.when(
      loading: () =>
          Center(child: CircularProgressIndicator(color: AppColors.accent)),
      error: (e, _) =>
          ErrorState(message: l.failedToLoadAccounts, onRetry: onRefresh),
      data: (accounts) {
        if (accounts.isEmpty) {
          return EmptyState(
            icon: Icons.account_balance_outlined,
            title: l.noAccounts,
          );
        }

        return RefreshIndicator(
          onRefresh: () async => onRefresh(),
          color: AppColors.accent,
          child: ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            itemCount: accounts.length,
            itemBuilder: (context, index) {
              final account = accounts[index];
              final accountType =
                  account['type'] ?? account['accountType'] ?? '';
              final typeColor = _accountTypeColor(accountType, m);

              return Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: m.surface,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: m.border),
                ),
                child: Row(
                  children: [
                    Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: typeColor.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Center(
                        child: Text(
                          account['code'] ?? '-',
                          style: GoogleFonts.jetBrainsMono(
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
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 14.5,
                                    fontWeight: FontWeight.w600,
                                    color: m.textPrimary,
                                  )
                                : GoogleFonts.josefinSans(
                                    fontWeight: FontWeight.w600,
                                    fontSize: 14,
                                    color: m.textPrimary,
                                  ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            account['description'] ?? '',
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 12,
                                    color: m.textSecondary,
                                  )
                                : GoogleFonts.josefinSans(
                                    fontSize: 12,
                                    color: m.textSecondary,
                                  ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                    _StatusPill(
                      label: l.accountTypeLabel(accountType),
                      color: typeColor,
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

  Color _accountTypeColor(String type, MiftahColors m) {
    return switch (type) {
      'ASSET' => AppColors.info,
      'LIABILITY' => m.danger,
      'EQUITY' => AppColors.primary,
      'INCOME' => m.success,
      'EXPENSE' => m.warning,
      _ => m.textMuted,
    };
  }
}

// --- Transactions Tab ---
class _TransactionsTab extends ConsumerWidget {
  final VoidCallback onRefresh;
  final _L l;
  const _TransactionsTab({required this.onRefresh, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final transactionsAsync = ref.watch(_transactionsProvider);
    final m = context.miftah;

    return transactionsAsync.when(
      loading: () =>
          Center(child: CircularProgressIndicator(color: AppColors.accent)),
      error: (e, _) =>
          ErrorState(message: l.failedToLoadTransactions, onRetry: onRefresh),
      data: (transactions) {
        if (transactions.isEmpty) {
          return EmptyState(
            icon: Icons.swap_horiz_outlined,
            title: l.noTransactions,
          );
        }

        return RefreshIndicator(
          onRefresh: () async => onRefresh(),
          color: AppColors.accent,
          child: ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            itemCount: transactions.length,
            itemBuilder: (context, index) {
              final tx = transactions[index];
              final debit = (tx['debitAmount'] ?? 0).toDouble();
              final credit = (tx['creditAmount'] ?? 0).toDouble();
              final isDebit = debit > 0;
              final tone = isDebit ? m.danger : m.success;

              return Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: m.surface,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: m.border),
                ),
                child: Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: tone.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Icon(
                        isDebit
                            ? Icons.arrow_upward_rounded
                            : Icons.arrow_downward_rounded,
                        color: tone,
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
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 2),
                          Text(
                            Formatters.date(
                              tx['transactionDate'] ?? tx['createdAt'],
                              ar: l.ar,
                            ),
                            style: GoogleFonts.josefinSans(
                              fontSize: 11,
                              color: m.textMuted,
                            ),
                          ),
                          if (tx['description'] != null &&
                              tx['accountName'] != null)
                            Text(
                              tx['description'],
                              style: l.ar
                                  ? GoogleFonts.notoNaskhArabic(
                                      fontSize: 11.5,
                                      color: m.textSecondary,
                                    )
                                  : GoogleFonts.josefinSans(
                                      fontSize: 11,
                                      color: m.textSecondary,
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
                            style: GoogleFonts.cinzel(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                              color: m.danger,
                            ),
                          ),
                        if (credit > 0)
                          Text(
                            Formatters.currency(credit),
                            style: GoogleFonts.cinzel(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                              color: m.success,
                            ),
                          ),
                        Text(
                          isDebit ? l.debit : l.credit,
                          style: l.ar
                              ? GoogleFonts.notoNaskhArabic(
                                  fontSize: 11,
                                  color: m.textMuted,
                                )
                              : GoogleFonts.josefinSans(
                                  fontSize: 10,
                                  letterSpacing: 1.0,
                                  color: m.textMuted,
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
  final _L l;
  const _ReportsTab({required this.onRefresh, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportAsync = ref.watch(_reportProvider);
    final m = context.miftah;

    return reportAsync.when(
      loading: () =>
          Center(child: CircularProgressIndicator(color: AppColors.accent)),
      error: (e, _) =>
          ErrorState(message: l.failedToLoadReport, onRetry: onRefresh),
      data: (report) {
        final totalIncome = (report['totalIncome'] ?? 0).toDouble();
        final totalExpense = (report['totalExpense'] ?? 0).toDouble();
        final netIncome = (report['netIncome'] ?? totalIncome - totalExpense)
            .toDouble();
        final incomeAccounts = List<Map<String, dynamic>>.from(
          report['incomeAccounts'] ?? [],
        );
        final expenseAccounts = List<Map<String, dynamic>>.from(
          report['expenseAccounts'] ?? [],
        );

        return RefreshIndicator(
          onRefresh: () async => onRefresh(),
          color: AppColors.accent,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.organisationPL,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.cinzel(fontSize: 18, color: m.textPrimary),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    _ReportSummaryCard(
                      label: l.income,
                      amount: Formatters.currency(totalIncome),
                      color: m.success,
                      icon: Icons.trending_up_rounded,
                      ar: l.ar,
                    ),
                    const SizedBox(width: 10),
                    _ReportSummaryCard(
                      label: l.expenses,
                      amount: Formatters.currency(totalExpense),
                      color: m.danger,
                      icon: Icons.trending_down_rounded,
                      ar: l.ar,
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
                        l.netIncome,
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
                    (a) => _ReportLineItem(
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
                    (a) => _ReportLineItem(
                      name: a['name'] ?? '-',
                      amount: (a['amount'] ?? 0).toDouble(),
                      color: m.danger,
                      ar: l.ar,
                    ),
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
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

class _ReportSummaryCard extends StatelessWidget {
  final String label;
  final String amount;
  final Color color;
  final IconData icon;
  final bool ar;

  const _ReportSummaryCard({
    required this.label,
    required this.amount,
    required this.color,
    required this.icon,
    required this.ar,
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
                Text(
                  label,
                  style: ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13,
                          color: color.withValues(alpha: 0.85),
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 12,
                          letterSpacing: 1.0,
                          color: color.withValues(alpha: 0.85),
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

class _ReportLineItem extends StatelessWidget {
  final String name;
  final double amount;
  final Color color;
  final bool ar;

  const _ReportLineItem({
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

class _StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  const _StatusPill({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(fontSize: 11, color: color)
            : GoogleFonts.josefinSans(
                fontSize: 10,
                letterSpacing: 1.0,
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

  String get title => ar ? 'المالية' : 'Finance';
  String get accounts => ar ? 'الحسابات' : 'Accounts';
  String get transactions => ar ? 'المعاملات' : 'Transactions';
  String get reports => ar ? 'التقارير' : 'Reports';
  String get failedToLoadAccounts =>
      ar ? 'تعذر تحميل الحسابات' : 'Failed to load accounts';
  String get noAccounts => ar ? 'لا توجد حسابات' : 'No accounts found';
  String get failedToLoadTransactions =>
      ar ? 'تعذر تحميل المعاملات' : 'Failed to load transactions';
  String get noTransactions => ar ? 'لا توجد معاملات' : 'No transactions found';
  String get debit => ar ? 'مدين' : 'Debit';
  String get credit => ar ? 'دائن' : 'Credit';
  String get failedToLoadReport =>
      ar ? 'تعذر تحميل التقرير' : 'Failed to load report';
  String get organisationPL => ar ? 'أرباح وخسائر المؤسسة' : "Organisation P&L";
  String get income => ar ? 'الدخل' : 'Income';
  String get expenses => ar ? 'المصاريف' : 'Expenses';
  String get netIncome => ar ? 'صافي الدخل' : 'Net Income';
  String get incomeBreakdown => ar ? 'تفاصيل الدخل' : 'Income Breakdown';
  String get expenseBreakdown => ar ? 'تفاصيل المصاريف' : 'Expense Breakdown';

  String accountTypeLabel(String type) {
    const arMap = {
      'ASSET': 'أصول',
      'LIABILITY': 'التزامات',
      'EQUITY': 'حقوق ملكية',
      'INCOME': 'دخل',
      'EXPENSE': 'مصروف',
    };
    if (ar) return arMap[type] ?? type;
    return type;
  }
}
