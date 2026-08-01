import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _penaltyServiceProvider = Provider<PenaltyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PenaltyService(client.dio);
});

/// Family provider keyed by status string: 'open' | 'cleared' | 'all'.
final penaltiesProvider = FutureProvider.family
    .autoDispose<List<Map<String, dynamic>>, String>((ref, status) {
      final service = ref.watch(_penaltyServiceProvider);
      return service.listPenalties(status: status);
    });

// ---------------------------------------------------------------------------
// Fonts helper — Arabic uses Noto Naskh instead of Cinzel/Josefin Sans, and
// never carries the EN tracked-uppercase letterSpacing (breaks glyph joining).
// ---------------------------------------------------------------------------

TextStyle _display(
  bool ar, {
  double size = 16,
  FontWeight weight = FontWeight.w600,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.cinzel(fontSize: size, fontWeight: weight, color: color);

TextStyle _body(
  bool ar, {
  double size = 13,
  FontWeight weight = FontWeight.w400,
  Color? color,
  double letterSpacing = 0,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.josefinSans(
        fontSize: size,
        fontWeight: weight,
        color: color,
        letterSpacing: letterSpacing,
      );

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

class PenaltiesScreen extends ConsumerStatefulWidget {
  const PenaltiesScreen({super.key});

  @override
  ConsumerState<PenaltiesScreen> createState() => _PenaltiesScreenState();
}

class _PenaltiesScreenState extends ConsumerState<PenaltiesScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;

  static const _statuses = ['open', 'cleared'];

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: _statuses.length, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppColors.navyDark,
        title: Text(
          l.penalties,
          style: _display(
            l.ar,
            size: 18,
            weight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: AppColors.accent,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white54,
          labelStyle: _body(l.ar, size: 13, weight: FontWeight.w600),
          unselectedLabelStyle: _body(l.ar, size: 13, weight: FontWeight.w400),
          tabs: [
            Tab(text: l.tabOpen),
            Tab(text: l.tabCleared),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: List.generate(_statuses.length, (i) {
          return _PenaltyTabBody(
            status: _statuses[i],
            showHowToPay: _statuses[i] == 'open',
          );
        }),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Tab body
// ---------------------------------------------------------------------------

class _PenaltyTabBody extends ConsumerWidget {
  final String status;
  final bool showHowToPay;

  const _PenaltyTabBody({required this.status, required this.showHowToPay});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final penaltiesAsync = ref.watch(penaltiesProvider(status));
    final m = context.miftah;
    final l = _L(context.isAr);

    return penaltiesAsync.when(
      data: (penalties) => RefreshIndicator(
        color: m.isDark ? AppColors.accent : AppColors.primary,
        onRefresh: () async => ref.invalidate(penaltiesProvider(status)),
        child: penalties.isEmpty
            ? ListView(
                children: [
                  const SizedBox(height: 80),
                  EmptyState(
                    icon: status == 'open'
                        ? Icons.check_circle_outline
                        : Icons.history,
                    title: status == 'open'
                        ? l.noOpenPenalties
                        : l.noClearedPenalties,
                    subtitle: status == 'open'
                        ? l.noOpenPenaltiesSub
                        : l.noClearedPenaltiesSub,
                  ),
                ],
              )
            : ListView(
                padding: EdgeInsets.fromLTRB(
                  16,
                  16,
                  16,
                  AppInsets.bottomNav(context),
                ),
                children: [
                  ...penalties.asMap().entries.map(
                    (entry) => AnimatedListItem(
                      index: entry.key,
                      child: Padding(
                        padding: const EdgeInsets.only(bottom: 14),
                        child: _PenaltyCard(penalty: entry.value),
                      ),
                    ),
                  ),
                  if (showHowToPay) ...[
                    const SizedBox(height: 8),
                    const _HowToPayCard(),
                  ],
                ],
              ),
      ),
      loading: () => Padding(
        padding: const EdgeInsets.all(20),
        child: ListShimmer(itemCount: 3),
      ),
      error: (err, _) => ErrorState(
        message: l.failedToLoad,
        onRetry: () => ref.invalidate(penaltiesProvider(status)),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Penalty card
// ---------------------------------------------------------------------------

class _PenaltyCard extends StatelessWidget {
  final Map<String, dynamic> penalty;

  const _PenaltyCard({required this.penalty});

  /// Client-side status derivation: WAIVED > CLEARED > OPEN
  String _deriveStatus() {
    final clearedAt = penalty['clearedAt'];
    final waived = penalty['waived'] == true;
    if (clearedAt != null && waived) return 'WAIVED';
    if (clearedAt != null) return 'CLEARED';
    return 'OPEN';
  }

  Color _reasonColor(BuildContext context, String reason) {
    final m = context.miftah;
    switch (reason.toUpperCase()) {
      case 'BOUNCE':
        return m.danger;
      case 'SIGNATURE_MISMATCH':
        return m.warning;
      case 'ACCOUNT_CLOSED':
        return m.danger;
      default:
        return m.textSecondary;
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final derivedStatus = _deriveStatus();
    final isOpen = derivedStatus == 'OPEN';

    final failureReason = (penalty['failureReason'] as String?) ?? '';
    final penaltyAmount = (penalty['penaltyAmount'] as num?) ?? 0;
    final currentTotal = (penalty['currentTotal'] as num?) ?? 0;
    final outstanding = (penalty['outstanding'] as num?) ?? 0;
    final daysOverdue = (penalty['daysOverdue'] as num?) ?? 0;
    final accrued = currentTotal - penaltyAmount;
    final createdAt = Formatters.date(
      penalty['createdAt'] as String?,
      ar: context.isAr,
    );
    final reasonColor = _reasonColor(context, failureReason);
    final reasonLabel = l.reasonLabel(failureReason);

    // Status badge color
    Color statusColor;
    switch (derivedStatus) {
      case 'WAIVED':
        statusColor = AppColors.info;
      case 'CLEARED':
        statusColor = m.success;
      default:
        statusColor = m.danger;
    }

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
        border: isOpen
            ? Border.all(color: m.danger.withValues(alpha: 0.15))
            : null,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Left accent stripe
              Container(width: 5, color: isOpen ? m.danger : m.success),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Row: reason badge + status chip
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 4,
                            ),
                            decoration: BoxDecoration(
                              color: reasonColor.withValues(alpha: 0.10),
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(
                                color: reasonColor.withValues(alpha: 0.30),
                              ),
                            ),
                            child: Text(
                              reasonLabel,
                              style: _body(
                                l.ar,
                                size: 11,
                                weight: FontWeight.w700,
                                color: reasonColor,
                              ),
                            ),
                          ),
                          const Spacer(),
                          StatusBadge(
                            label: l.statusLabel(derivedStatus),
                            color: statusColor,
                          ),
                        ],
                      ),

                      if (daysOverdue > 0) ...[
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            Icon(Icons.schedule, size: 13, color: m.danger),
                            const SizedBox(width: 4),
                            Text(
                              l.daysOverdue(daysOverdue.toInt()),
                              style: _body(
                                l.ar,
                                size: 12,
                                weight: FontWeight.w600,
                                color: m.danger,
                              ),
                            ),
                          ],
                        ),
                      ],

                      const SizedBox(height: 14),

                      // Amount grid
                      Row(
                        children: [
                          _AmountDetail(
                            label: l.baseFine,
                            value: Formatters.currency(penaltyAmount),
                          ),
                          if (accrued > 0)
                            _AmountDetail(
                              label: l.accrued,
                              value: Formatters.currency(accrued),
                              valueColor: m.warning,
                            ),
                          _AmountDetail(
                            label: l.currentTotal,
                            value: Formatters.currency(currentTotal),
                          ),
                        ],
                      ),

                      const SizedBox(height: 10),
                      Divider(
                        height: 1,
                        color: m.border.withValues(alpha: 0.5),
                      ),
                      const SizedBox(height: 10),

                      // Outstanding + date row
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                l.outstanding,
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                              Text(
                                Formatters.currency(outstanding),
                                style: _body(
                                  l.ar,
                                  size: 18,
                                  weight: FontWeight.w700,
                                  color: isOpen ? m.danger : m.textPrimary,
                                ),
                              ),
                            ],
                          ),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Text(
                                l.raised,
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                              Text(
                                createdAt,
                                style: _body(
                                  l.ar,
                                  size: 12,
                                  color: m.textSecondary,
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// How to pay card
// ---------------------------------------------------------------------------

class _HowToPayCard extends StatelessWidget {
  const _HowToPayCard();

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Card(
      elevation: 0,
      color: m.background,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: m.border.withValues(alpha: 0.8)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.info_outline_rounded,
                  size: 18,
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  l.howToPay,
                  style: _display(
                    l.ar,
                    size: 14,
                    weight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _InfoRow(
              icon: Icons.account_balance_outlined,
              label: l.bankTransfer,
              value: l.bankTransferValue,
            ),
            const SizedBox(height: 10),
            _InfoRow(
              icon: Icons.location_on_outlined,
              label: l.officePayment,
              value: l.officePaymentValue,
            ),
            const SizedBox(height: 10),
            _InfoRow(
              icon: Icons.access_time_outlined,
              label: l.officeHours,
              value: l.officeHoursValue,
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: m.warningBg,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                l.contactManager,
                style: _body(
                  l.ar,
                  size: 11,
                  weight: FontWeight.w500,
                  color: m.warning,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _InfoRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: m.textSecondary),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: _body(
                  ar,
                  size: 11,
                  weight: FontWeight.w700,
                  color: m.textSecondary,
                ),
              ),
              const SizedBox(height: 2),
              Text(value, style: _body(ar, size: 12, color: m.textPrimary)),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Amount detail widget
// ---------------------------------------------------------------------------

class _AmountDetail extends StatelessWidget {
  final String label;
  final String value;
  final Color? valueColor;

  const _AmountDetail({
    required this.label,
    required this.value,
    this.valueColor,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 3),
          Text(
            value,
            style: _body(
              ar,
              size: 12,
              weight: FontWeight.w600,
              color: valueColor ?? m.textPrimary,
            ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get penalties => ar ? 'الغرامات' : 'Penalties';
  String get tabOpen => ar ? 'مفتوحة' : 'Open';
  String get tabCleared => ar ? 'مسددة' : 'Cleared';
  String get noOpenPenalties =>
      ar ? 'لا توجد غرامات مفتوحة' : 'No Open Penalties';
  String get noOpenPenaltiesSub => ar
      ? 'ليس لديك أي غرامات مستحقة حالياً'
      : 'You have no outstanding penalties';
  String get noClearedPenalties =>
      ar ? 'لا توجد غرامات مسددة' : 'No Cleared Penalties';
  String get noClearedPenaltiesSub =>
      ar ? 'لم يتم تسوية أي غرامات بعد' : 'No penalties have been cleared yet';
  String get failedToLoad =>
      ar ? 'تعذر تحميل الغرامات' : 'Failed to load penalties';
  String get baseFine => ar ? 'الغرامة الأساسية' : 'Base Fine';
  String get accrued => ar ? 'المتراكم' : 'Accrued';
  String get currentTotal => ar ? 'الإجمالي الحالي' : 'Current Total';
  String get outstanding => ar ? 'المستحق' : 'Outstanding';
  String get raised => ar ? 'تاريخ الإصدار' : 'Raised';
  String get howToPay => ar ? 'كيفية دفع الغرامة' : 'How to Pay';
  String get bankTransfer => ar ? 'تحويل بنكي' : 'Bank Transfer';
  String get bankTransferValue => ar
      ? 'سيتم تزويدك ببيانات الحساب من قبل مدير العقار'
      : 'Account details will be provided by your property manager';
  String get officePayment => ar ? 'الدفع في المكتب' : 'Office Payment';
  String get officePaymentValue => ar
      ? 'قم بزيارة مكتب الإدارة بشيك أو نقداً'
      : 'Visit the management office with a cheque or cash';
  String get officeHours => ar ? 'ساعات العمل' : 'Office Hours';
  String get officeHoursValue => ar
      ? 'الأحد – الخميس، 9:00 صباحاً – 6:00 مساءً'
      : 'Sunday – Thursday, 9:00 AM – 6:00 PM';
  String get contactManager => ar
      ? 'تواصل مع مدير العقار للحصول على تعليمات الدفع المحدّثة.'
      : 'Contact your property manager for updated payment instructions.';

  // Arabic numeral–noun agreement: 1 يوم واحد · 2 يومين · 3–10 أيام · 11+ يوماً
  String daysOverdue(int n) {
    if (!ar) return '$n days overdue';
    if (n == 1) return 'متأخر يوماً واحداً';
    if (n == 2) return 'متأخر يومين';
    if (n >= 3 && n <= 10) return 'متأخر $n أيام';
    return 'متأخر $n يوماً';
  }

  String statusLabel(String status) {
    switch (status) {
      case 'WAIVED':
        return ar ? 'معفوة' : 'WAIVED';
      case 'CLEARED':
        return ar ? 'مسددة' : 'CLEARED';
      default:
        return ar ? 'مفتوحة' : 'OPEN';
    }
  }

  String reasonLabel(String reason) {
    switch (reason.toUpperCase()) {
      case 'BOUNCE':
        return ar ? 'ارتداد الشيك' : 'Bounced Cheque';
      case 'SIGNATURE_MISMATCH':
        return ar ? 'عدم تطابق التوقيع' : 'Signature Mismatch';
      case 'ACCOUNT_CLOSED':
        return ar ? 'الحساب مغلق' : 'Account Closed';
      default:
        return reason;
    }
  }
}
