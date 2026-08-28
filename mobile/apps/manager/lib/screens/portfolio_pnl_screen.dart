import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _portfolioPnlProvider = FutureProvider.autoDispose<Map<String, dynamic>>((
  ref,
) {
  final service = FinanceService(ref.watch(apiClientProvider).dio);
  return service.getPortfolioProfitLoss();
});

class PortfolioPnlScreen extends ConsumerStatefulWidget {
  const PortfolioPnlScreen({super.key});

  @override
  ConsumerState<PortfolioPnlScreen> createState() => _PortfolioPnlScreenState();
}

class _PortfolioPnlScreenState extends ConsumerState<PortfolioPnlScreen> {
  final _search = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    ref.invalidate(_portfolioPnlProvider);
    await ref.read(_portfolioPnlProvider.future);
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final m = context.miftah;
    final async = ref.watch(_portfolioPnlProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _Header(l: l),
          Expanded(
            child: async.when(
              loading: () => Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
              error: (_, _) => ErrorState(
                message: l.failed,
                onRetry: () => ref.invalidate(_portfolioPnlProvider),
              ),
              data: (data) {
                final overall = Map<String, dynamic>.from(
                  data['overall'] as Map? ?? const {},
                );
                final all = (data['properties'] as List? ?? const [])
                    .whereType<Map>()
                    .map((row) => Map<String, dynamic>.from(row))
                    .toList();
                final query = _query.trim().toLowerCase();
                final rows = query.isEmpty
                    ? all
                    : all.where((row) {
                        final en =
                            row['propertyNameEn']?.toString().toLowerCase() ??
                            '';
                        final ar =
                            row['propertyNameAr']?.toString().toLowerCase() ??
                            '';
                        return en.contains(query) || ar.contains(query);
                      }).toList();

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: CustomScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    slivers: [
                      SliverToBoxAdapter(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _OverallCard(
                                report: overall,
                                propertyCount: all.length,
                                l: l,
                              ),
                              const SizedBox(height: 18),
                              Text(
                                l.propertyPL,
                                style: l.ar
                                    ? GoogleFonts.notoNaskhArabic(
                                        fontSize: 18,
                                        fontWeight: FontWeight.w600,
                                        color: m.textPrimary,
                                      )
                                    : GoogleFonts.plusJakartaSans(
                                        fontSize: 17,
                                        fontWeight: FontWeight.w600,
                                        color: m.textPrimary,
                                      ),
                              ),
                              const SizedBox(height: 10),
                              TextField(
                                controller: _search,
                                onChanged: (value) =>
                                    setState(() => _query = value),
                                decoration: InputDecoration(
                                  hintText: l.search,
                                  prefixIcon: const Icon(Icons.search_rounded),
                                  suffixIcon: _query.isEmpty
                                      ? null
                                      : IconButton(
                                          onPressed: () {
                                            _search.clear();
                                            setState(() => _query = '');
                                          },
                                          icon: const Icon(Icons.close_rounded),
                                        ),
                                ),
                              ),
                              const SizedBox(height: 8),
                              Text(
                                l.showing(rows.length, all.length),
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 11,
                                  color: m.textMuted,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      if (rows.isEmpty)
                        SliverFillRemaining(
                          hasScrollBody: false,
                          child: EmptyState(
                            icon: Icons.apartment_rounded,
                            title: l.noProperties,
                          ),
                        )
                      else
                        SliverPadding(
                          padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
                          sliver: SliverList.builder(
                            itemCount: rows.length,
                            itemBuilder: (context, index) =>
                                _PropertyPnlCard(row: rows[index], l: l),
                          ),
                        ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.l});
  final _L l;

  @override
  Widget build(BuildContext context) => Container(
    color: AppColors.primary,
    child: SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 6, 20, 12),
        child: Row(
          children: [
            IconButton(
              onPressed: () => context.pop(),
              icon: Icon(
                context.isAr ? Icons.chevron_right : Icons.chevron_left,
                color: AppColors.accent,
              ),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: Text(
                l.title,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 22,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 19,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _OverallCard extends StatelessWidget {
  const _OverallCard({
    required this.report,
    required this.propertyCount,
    required this.l,
  });
  final Map<String, dynamic> report;
  final int propertyCount;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final income = (report['totalIncome'] as num? ?? 0).toDouble();
    final expenses = (report['totalExpenses'] as num? ?? 0).toDouble();
    final profit = (report['netProfit'] as num? ?? income - expenses)
        .toDouble();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.overallPL,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
          const SizedBox(height: 2),
          Text(
            l.properties(propertyCount),
            style: TextStyle(color: m.textMuted, fontSize: 12),
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              _Metric(label: l.income, value: income, color: m.success, l: l),
              _Metric(
                label: l.expenses,
                value: expenses,
                color: m.danger,
                l: l,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Divider(color: m.divider),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                l.netProfit,
                style: TextStyle(
                  color: m.textSecondary,
                  fontWeight: FontWeight.w600,
                ),
              ),
              Text(
                Formatters.currency(profit),
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 21,
                  fontWeight: FontWeight.w700,
                  color: profit >= 0 ? m.success : m.danger,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({
    required this.label,
    required this.value,
    required this.color,
    required this.l,
  });
  final String label;
  final double value;
  final Color color;
  final _L l;

  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(color: context.miftah.textMuted, fontSize: 11),
        ),
        const SizedBox(height: 4),
        Text(
          Formatters.currencyCompact(value),
          style: GoogleFonts.plusJakartaSans(
            fontWeight: FontWeight.w700,
            color: color,
            fontSize: 16,
          ),
        ),
      ],
    ),
  );
}

class _PropertyPnlCard extends StatelessWidget {
  const _PropertyPnlCard({required this.row, required this.l});
  final Map<String, dynamic> row;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final name =
        l.ar && (row['propertyNameAr']?.toString().trim().isNotEmpty ?? false)
        ? row['propertyNameAr'].toString()
        : row['propertyNameEn']?.toString() ?? '—';
    final income = (row['totalIncome'] as num? ?? 0).toDouble();
    final expenses = (row['totalExpenses'] as num? ?? 0).toDouble();
    final profit = (row['netProfit'] as num? ?? income - expenses).toDouble();
    return Container(
      key: ValueKey('property-pnl-${row['propertyId']}'),
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(15),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              _SmallValue(label: l.income, value: income, color: m.success),
              _SmallValue(label: l.expenses, value: expenses, color: m.danger),
              _SmallValue(
                label: l.netProfit,
                value: profit,
                color: profit >= 0 ? m.success : m.danger,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SmallValue extends StatelessWidget {
  const _SmallValue({
    required this.label,
    required this.value,
    required this.color,
  });
  final String label;
  final double value;
  final Color color;

  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 10, color: context.miftah.textMuted),
        ),
        const SizedBox(height: 3),
        Text(
          Formatters.currencyCompact(value),
          style: GoogleFonts.plusJakartaSans(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: color,
          ),
        ),
      ],
    ),
  );
}

class _L {
  const _L(this.ar);
  final bool ar;
  String get title => ar ? 'أرباح وخسائر المحفظة' : 'Portfolio P&L';
  String get overallPL =>
      ar ? 'إجمالي أرباح وخسائر المحفظة' : 'Overall Portfolio P&L';
  String get propertyPL =>
      ar ? 'الأرباح والخسائر حسب العقار' : 'P&L by Property';
  String get income => ar ? 'الدخل' : 'Income';
  String get expenses => ar ? 'المصاريف' : 'Expenses';
  String get netProfit => ar ? 'صافي الربح' : 'Net Profit';
  String get search => ar ? 'البحث باسم العقار' : 'Search properties';
  String get failed =>
      ar ? 'تعذر تحميل الأرباح والخسائر' : 'Failed to load portfolio P&L';
  String get noProperties =>
      ar ? 'لا توجد عقارات مطابقة' : 'No matching properties';
  String properties(int count) =>
      ar ? '$count عقارًا في المحفظة' : '$count properties in portfolio';
  String showing(int shown, int total) =>
      ar ? 'عرض $shown من $total' : 'Showing $shown of $total';
}
