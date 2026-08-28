import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

final _myPaymentsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  return ref.watch(_paymentServiceProvider).getMyPayments();
});

final _myLeasesProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio).getMyLeases();
});

/// Drives the header pill. The design puts the open-penalty count next to the
/// screen title so an overdue renter sees it without opening Services.
final _walletPenaltyCountProvider = FutureProvider.autoDispose<int>((
  ref,
) async {
  final client = ref.watch(apiClientProvider);
  final list = await PenaltyService(
    client.dio,
  ).listPenalties(status: 'open', size: 50);
  return list.length;
});

enum _ChequeFilter { all, cleared, upcoming }

/// Renter Wallet · cheques — design screen 03.
///
/// Header, gold hero (annual rent + progress), filter chips, then the cheque
/// schedule. The cheque that needs attention next is inverted to ink, which is
/// the one emphasis the design allows per screen.
class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});

  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen> {
  _ChequeFilter _filter = _ChequeFilter.all;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final paymentsAsync = ref.watch(_myPaymentsProvider);
    final leasesAsync = ref.watch(_myLeasesProvider);
    final penalties = ref.watch(_walletPenaltyCountProvider).valueOrNull ?? 0;

    Future<void> refresh() async {
      ref.invalidate(_myPaymentsProvider);
      ref.invalidate(_myLeasesProvider);
      ref.invalidate(_walletPenaltyCountProvider);
      await ref.read(_myPaymentsProvider.future);
    }

    return Column(
      children: [
        _WalletHeader(penalties: penalties),
        Expanded(
          child: RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 24),
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                _AnnualRentHero(
                  leasesAsync: leasesAsync,
                  paymentsAsync: paymentsAsync,
                ),
                const SizedBox(height: MiftahSpacing.gap),
                _FilterChips(
                  selected: _filter,
                  onChanged: (f) => setState(() => _filter = f),
                ),
                const SizedBox(height: MiftahSpacing.gap),
                paymentsAsync.when(
                  loading: () => const _ChequesShimmer(),
                  error: (e, _) => ErrorState(
                    message: l.failedToLoadCheques,
                    onRetry: refresh,
                  ),
                  data: (payments) =>
                      _ChequeList(payments: payments, filter: _filter),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

// ─── Header ─────────────────────────────────────────────────────────────────

class _WalletHeader extends StatelessWidget {
  const _WalletHeader({required this.penalties});

  final int penalties;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    return Container(
      color: Theme.of(context).colorScheme.surface,
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Row(
        children: [
          Expanded(
            child: Text(
              l.yourCheques,
              style: l.ar
                  ? MiftahType.ar(size: 21, weight: FontWeight.w700)
                  : MiftahType.title(),
            ),
          ),
          if (penalties > 0)
            GestureDetector(
              onTap: () => context.push('/penalties'),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 13,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: MiftahColors.dangerTint,
                  border: Border.all(color: MiftahColors.dangerTintBorder),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  l.penaltiesPill(penalties),
                  style: l.ar
                      ? MiftahType.ar(
                          size: 11.5,
                          weight: FontWeight.w700,
                          color: MiftahColors.danger,
                        )
                      : MiftahType.button(
                          size: 11.5,
                          color: MiftahColors.danger,
                        ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ─── Annual rent hero ───────────────────────────────────────────────────────

class _AnnualRentHero extends StatelessWidget {
  const _AnnualRentHero({
    required this.leasesAsync,
    required this.paymentsAsync,
  });

  final AsyncValue<List<dynamic>> leasesAsync;
  final AsyncValue<List<dynamic>> paymentsAsync;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final payments = paymentsAsync.valueOrNull ?? const [];
    final rent = payments
        .whereType<Map<String, dynamic>>()
        .where((p) => !isNonRentPayment(p))
        .toList();

    double sum(Iterable<Map<String, dynamic>> rows) => rows.fold<double>(
      0,
      (s, p) => s + ((p['amount'] ?? 0) as num).toDouble(),
    );

    final total = sum(
      rent.where((p) => !const {'CANCELLED', 'REPLACED'}.contains(p['status'])),
    );
    final paid = sum(
      rent.where(
        (p) => const {
          'CLEARED',
          'COLLECTED',
          'DEPOSITED',
        }.contains(effectivePaymentStatus(p)),
      ),
    );
    final pct = total <= 0 ? 0.0 : (paid / total).clamp(0.0, 1.0);

    final lease = (leasesAsync.valueOrNull ?? const [])
        .whereType<Map<String, dynamic>>()
        .firstOrNull;
    final year =
        DateTime.tryParse(lease?['startDate']?.toString() ?? '')?.year ??
        DateTime.now().year;

    final money = NumberFormat('#,##0', l.ar ? 'ar' : 'en');

    return MiftahGoldCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.annualRentLine(year),
            style: _onGold(13.5, FontWeight.w600, 0.75, l.ar),
          ),
          const SizedBox(height: 4),
          Text(
            'AED ${money.format(total)}',
            style: MiftahType.amount(size: 36, color: MiftahColors.ink),
          ),
          const SizedBox(height: 16),
          MiftahProgress(value: pct, onGold: true),
          const SizedBox(height: 9),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                l.paidAmount('AED ${money.format(paid)}'),
                style: _onGold(12, FontWeight.w700, 0.78, l.ar),
              ),
              Text(
                l.percentComplete((pct * 100).round()),
                style: _onGold(12, FontWeight.w700, 0.78, l.ar),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

TextStyle _onGold(double size, FontWeight weight, double opacity, bool ar) {
  final color = MiftahColors.ink.withValues(alpha: opacity);
  return ar
      ? MiftahType.ar(size: size, weight: weight, color: color)
      : MiftahType.body(size: size, color: color).copyWith(fontWeight: weight);
}

// ─── Filter chips ───────────────────────────────────────────────────────────

class _FilterChips extends StatelessWidget {
  const _FilterChips({required this.selected, required this.onChanged});

  final _ChequeFilter selected;
  final ValueChanged<_ChequeFilter> onChanged;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final items = [
      (_ChequeFilter.all, l.filterAll),
      (_ChequeFilter.cleared, l.filterCleared),
      (_ChequeFilter.upcoming, l.filterUpcoming),
    ];
    return Row(
      children: [
        for (final (f, label) in items) ...[
          MiftahFilterChip(
            label: label,
            selected: f == selected,
            onTap: () => onChanged(f),
          ),
          const SizedBox(width: 8),
        ],
      ],
    );
  }
}

// ─── Cheque list ────────────────────────────────────────────────────────────

/// The backend stores past-due rows as PENDING until the penalty batch flips
/// them to OVERDUE — derive the renter-facing status either way (same rule
/// as the dashboard and manager app).
String effectivePaymentStatus(Map<String, dynamic> p) {
  final stored = p['status']?.toString() ?? 'PENDING';
  if (stored != 'PENDING') return stored;
  final due = DateTime.tryParse(p['dueDate']?.toString() ?? '');
  if (due == null) return stored;
  final now = DateTime.now();
  return due.isBefore(DateTime(now.year, now.month, now.day))
      ? 'OVERDUE'
      : stored;
}

/// Deposits and charges carry their own purpose label; only rent rows count
/// toward "Cheque n of total" and the hero's annual-rent summary.
bool isNonRentPayment(Map<String, dynamic> p) =>
    p['isBookingDeposit'] == true ||
    p['isSecurityDeposit'] == true ||
    p['isCharge'] == true;

class _ChequeList extends StatelessWidget {
  const _ChequeList({required this.payments, required this.filter});

  final List<dynamic> payments;
  final _ChequeFilter filter;

  /// Same "what needs the renter's attention next" rule the old hero card
  /// used: the nearest OVERDUE cheque, else the earliest still-PENDING one.
  Map<String, dynamic>? _pickActive(List<Map<String, dynamic>> candidates) {
    final due = candidates.where((p) {
      final s = effectivePaymentStatus(p);
      return s == 'PENDING' || s == 'OVERDUE';
    }).toList();
    if (due.isEmpty) return null;

    int statusRank(String s) => s == 'OVERDUE' ? 0 : 1;
    due.sort((a, b) {
      final ra = statusRank(effectivePaymentStatus(a));
      final rb = statusRank(effectivePaymentStatus(b));
      if (ra != rb) return ra.compareTo(rb);
      final ad =
          DateTime.tryParse(a['dueDate']?.toString() ?? '') ?? DateTime(2100);
      final bd =
          DateTime.tryParse(b['dueDate']?.toString() ?? '') ?? DateTime(2100);
      final c = ad.compareTo(bd);
      if (c != 0) return c;
      final ai = (a['installmentNumber'] ?? 0) as num;
      final bi = (b['installmentNumber'] ?? 0) as num;
      return ai.compareTo(bi);
    });
    return due.first;
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final all = payments.whereType<Map<String, dynamic>>().toList()
      ..sort((a, b) {
        final ad =
            DateTime.tryParse(a['dueDate']?.toString() ?? '') ?? DateTime(2100);
        final bd =
            DateTime.tryParse(b['dueDate']?.toString() ?? '') ?? DateTime(2100);
        return ad.compareTo(bd);
      });

    if (all.isEmpty) {
      return MiftahEmptyState(
        icon: Icons.receipt_long_rounded,
        title: l.noChequesYet,
        subtitle: l.noChequesYetSub,
      );
    }

    final activePick = _pickActive(all);
    final rentRows = all.where((p) => !isNonRentPayment(p)).toList();
    final rentTotal = rentRows.length;
    final clearedCount = rentRows
        .where(
          (p) => const {
            'CLEARED',
            'COLLECTED',
            'DEPOSITED',
          }.contains(effectivePaymentStatus(p)),
        )
        .length;

    final list = switch (filter) {
      _ChequeFilter.all => all,
      _ChequeFilter.cleared =>
        all
            .where(
              (p) => const {
                'CLEARED',
                'COLLECTED',
                'DEPOSITED',
              }.contains(effectivePaymentStatus(p)),
            )
            .toList(),
      _ChequeFilter.upcoming =>
        all
            .where(
              (p) => const {
                'PENDING',
                'OVERDUE',
              }.contains(effectivePaymentStatus(p)),
            )
            .toList(),
    };

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 11),
          child: Text(
            l.chequeSummary(rentTotal, clearedCount),
            style: MiftahType.mono(size: 12.5),
          ),
        ),
        if (list.isEmpty)
          MiftahCard(
            child: Text(
              l.noChequesInFilter,
              style: l.ar ? MiftahType.ar(size: 13) : MiftahType.body(),
            ),
          )
        else
          for (var i = 0; i < list.length; i++)
            Padding(
              padding: EdgeInsets.only(
                bottom: i == list.length - 1 ? 0 : MiftahSpacing.gap,
              ),
              child: AnimatedListItem(
                index: i,
                child: _ChequeCard(
                  payment: list[i],
                  totalCount: rentTotal,
                  isActive: identical(list[i], activePick),
                ),
              ),
            ),
      ],
    );
  }
}

class _ChequeCard extends StatelessWidget {
  const _ChequeCard({
    required this.payment,
    required this.totalCount,
    required this.isActive,
  });

  final Map<String, dynamic> payment;
  final int totalCount;

  /// The cheque needing attention next — inverted to ink, the screen's one
  /// emphasis.
  final bool isActive;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final status = effectivePaymentStatus(payment);
    final isNonRent = isNonRentPayment(payment);
    final amount = (payment['amount'] ?? 0) as num;
    final money = NumberFormat('#,##0', l.ar ? 'ar' : 'en');

    final ({IconData icon, MiftahTone tone, String label}) spec =
        switch (status) {
          'CLEARED' => (
            icon: Icons.check_rounded,
            tone: MiftahTone.success,
            label: l.badgeCleared,
          ),
          'COLLECTED' => (
            icon: Icons.check_rounded,
            tone: MiftahTone.success,
            label: l.badgeCollected,
          ),
          'DEPOSITED' => (
            icon: Icons.account_balance_rounded,
            tone: MiftahTone.info,
            label: l.badgeDeposited,
          ),
          'BOUNCED' => (
            icon: Icons.error_outline_rounded,
            tone: MiftahTone.danger,
            label: l.badgeBounced,
          ),
          'OVERDUE' => (
            icon: Icons.schedule_rounded,
            tone: MiftahTone.danger,
            label: l.badgeOverdue,
          ),
          _ => (
            icon: Icons.circle_outlined,
            tone: MiftahTone.neutral,
            label: l.badgeUpcoming,
          ),
        };

    final title = isNonRent
        ? l.purposeLabel(payment)
        : l.chequeOfTotal(payment['installmentNumber'] ?? 0, totalCount);
    final due = _fmtDate(payment['dueDate']?.toString(), l.ar);

    // Ink inversion for the live cheque; everything else is a flat white card.
    final onInk = isActive;
    final m = context.miftah;
    final titleColor = onInk ? Colors.white : m.textPrimary;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      decoration: BoxDecoration(
        color: onInk ? MiftahColors.ink : m.surface,
        borderRadius: BorderRadius.circular(MiftahRadii.card),
        border: onInk ? null : Border.all(color: MiftahColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 34,
                height: 34,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: onInk
                      ? MiftahColors.brass.withValues(alpha: 0.18)
                      : MiftahBadge.colorsFor(spec.tone, isDark: m.isDark).bg,
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Icon(
                  spec.icon,
                  size: 18,
                  color: onInk
                      ? MiftahColors.brassLight
                      : MiftahBadge.colorsFor(spec.tone, isDark: m.isDark).fg,
                ),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: l.ar
                      ? MiftahType.ar(
                          size: 14.5,
                          weight: FontWeight.w700,
                          color: titleColor,
                        )
                      : MiftahType.cardTitle(
                          color: titleColor,
                        ).copyWith(fontSize: 14.5),
                ),
              ),
              const SizedBox(width: 8),
              MiftahBadge(
                spec.label,
                tone: onInk ? MiftahTone.onDark : spec.tone,
              ),
            ],
          ),
          const SizedBox(height: 13),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Text(
                  'AED ${money.format(amount)}',
                  style: MiftahType.amount(size: 26, color: titleColor),
                ),
              ),
              if (due != null)
                Text(
                  due,
                  style: MiftahType.mono(
                    size: 11.5,
                    color: onInk
                        ? const Color(0xFF9B93B4)
                        : MiftahColors.textMuted,
                  ),
                ),
            ],
          ),
          // The live cheque explains itself; the rest do not need a footnote.
          if (onInk && payment['bankName'] != null) ...[
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.only(top: 13),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: Color(0x1AFFFFFF))),
              ),
              child: Text(
                l.presentedTo(payment['bankName'].toString()),
                style: l.ar
                    ? MiftahType.ar(size: 12, color: const Color(0xFF9B93B4))
                    : MiftahType.body(size: 12, color: const Color(0xFF9B93B4)),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

String? _fmtDate(String? iso, bool ar) {
  if (iso == null || iso.isEmpty) return null;
  try {
    return DateFormat(
      'd MMM yyyy',
      ar ? 'ar' : 'en',
    ).format(DateTime.parse(iso));
  } catch (_) {
    return null;
  }
}

class _ChequesShimmer extends StatelessWidget {
  const _ChequesShimmer();

  @override
  Widget build(BuildContext context) => const Column(
    children: [
      ShimmerLoading(height: 96, borderRadius: MiftahRadii.card),
      SizedBox(height: MiftahSpacing.gap),
      ShimmerLoading(height: 96, borderRadius: MiftahRadii.card),
      SizedBox(height: MiftahSpacing.gap),
      ShimmerLoading(height: 96, borderRadius: MiftahRadii.card),
    ],
  );
}

// ─── Strings (EN/AR) ────────────────────────────────────────────────────────

class _L {
  _L(this.ar);
  final bool ar;

  String get failedToLoadCheques =>
      ar ? 'تعذّر تحميل الشيكات' : 'Failed to load cheques';

  // Chrome header
  String get yourTenancy => ar ? 'عقدك' : 'Your tenancy';
  String tenancyYear(int year) => ar ? 'عقد $year' : 'Tenancy $year';
  String tenancyYearRange(int start, int end) =>
      ar ? 'عقد $start–$end' : 'Tenancy $start–$end';
  String get annualRent => ar ? 'الإيجار السنوي' : 'ANNUAL RENT';
  String chequeCountLabel(int count) {
    if (!ar) return '$count CHEQUE${count == 1 ? '' : 'S'}';
    if (count == 1) return 'شيك واحد';
    if (count == 2) return 'شيكان';
    return '$count شيكات';
  }

  // Progress card
  String get totalPaidThisLease =>
      ar ? 'إجمالي المدفوع لهذا العقد' : 'Total paid this lease';
  String percentComplete(int pct) => ar ? '$pct% مكتمل' : '$pct% complete';

  // Filter chips
  String get filterAll => ar ? 'الكل' : 'All';
  String get filterCleared => ar ? 'مسدّدة' : 'Cleared';
  String get filterUpcoming => ar ? 'القادمة' : 'Upcoming';

  // Timeline empty states
  String get noChequesYet => ar
      ? 'لا توجد شيكات بعد — ستظهر هنا بمجرد إعداد عقدك.'
      : 'No cheques yet — they will appear here once your lease is set up.';
  String get noChequesInFilter =>
      ar ? 'لا توجد شيكات ضمن هذا التصفية.' : 'No cheques in this filter.';

  // Timeline card
  String get chequeWord => ar ? 'شيك' : 'Cheque';

  /// Sentence case, unpadded — the redesign drops the tracked-caps treatment.
  String chequeOfTotal(dynamic n, int total) =>
      ar ? 'شيك $n من $total' : 'Cheque $n of $total';

  String refLabel(String ref) => ar ? 'مرجع $ref' : 'Ref $ref';

  String collectedLine(String formatted) => ar
      ? (formatted.isEmpty ? 'تم الاستلام' : 'تم الاستلام في $formatted')
      : (formatted.isEmpty ? 'Collected' : 'Collected on $formatted');
  String depositedLine(String formatted) => ar
      ? (formatted.isEmpty ? 'تم الإيداع' : 'تم الإيداع في $formatted')
      : (formatted.isEmpty ? 'Deposited' : 'Deposited on $formatted');
  String bouncedLine(String formatted) => ar
      ? (formatted.isEmpty ? 'ارتدّ الشيك' : 'ارتدّ الشيك في $formatted')
      : (formatted.isEmpty ? 'Bounced' : 'Bounced on $formatted');
  String overdueLine(String dueLabel) => ar
      ? (dueLabel.isEmpty ? 'متأخر' : 'متأخر منذ $dueLabel')
      : (dueLabel.isEmpty ? 'Overdue' : 'Overdue since $dueLabel');

  String penaltyLine(String penalty, String totalPayable) => ar
      ? '+ $penalty درهم غرامة · $totalPayable درهم إجمالي المستحق'
      : '+ AED $penalty penalty · AED $totalPayable total payable';

  // Status pill labels
  String get statusCleared => ar ? 'تمت التسوية' : 'cleared';
  String get statusCollected => ar ? 'تم التحصيل' : 'collected';
  String get statusDeposited => ar ? 'تم الإيداع' : 'deposited';
  String get statusBounced => ar ? 'مرتجع' : 'bounced';
  String get statusOverdue => ar ? 'متأخر' : 'overdue';
  String get statusUpcoming => ar ? 'قيد الانتظار' : 'upcoming';

  // ── Added by the redesign (design screen 03) ──
  String get yourCheques => ar ? 'شيكاتك' : 'Your cheques';

  String penaltiesPill(int count) =>
      ar ? '$count غرامة' : '$count ${count == 1 ? 'penalty' : 'penalties'}';

  String annualRentLine(int year) =>
      ar ? 'الإيجار السنوي · عقد $year' : 'Annual rent · tenancy $year';

  String paidAmount(String formatted) =>
      ar ? '$formatted مدفوعة' : '$formatted paid';

  String chequeSummary(int total, int cleared) => ar
      ? '$total شيك · $cleared مسدّدة'
      : '$total ${total == 1 ? 'cheque' : 'cheques'} · $cleared cleared';

  String get noChequesYetSub => ar
      ? 'سيظهر جدول الشيكات هنا بمجرد تسجيل عقدك.'
      : 'Your cheque schedule appears here once the lease is registered.';

  String get badgeCleared => ar ? 'مسدّد' : 'CLEARED';
  String get badgeCollected => ar ? 'محصّل' : 'COLLECTED';
  String get badgeDeposited => ar ? 'مودع' : 'DEPOSITED';
  String get badgeBounced => ar ? 'مرتجع' : 'BOUNCED';
  String get badgeOverdue => ar ? 'متأخر' : 'OVERDUE';
  String get badgeUpcoming => ar ? 'قادم' : 'UPCOMING';

  String presentedTo(String bank) => ar
      ? 'مقدّم إلى $bank · يُسوّى خلال ٣ أيام عمل'
      : 'Presented to $bank · clears in 3 working days';

  /// Deposits and charges carry their own label rather than a cheque number.
  String purposeLabel(Map<String, dynamic> p) {
    if (p['isSecurityDeposit'] == true) {
      return ar ? 'مبلغ التأمين' : 'Security deposit';
    }
    if (p['isBookingDeposit'] == true) {
      return ar ? 'دفعة الحجز' : 'Booking deposit';
    }
    if (p['isCharge'] == true) return ar ? 'رسوم' : 'Charge';
    return ar ? 'دفعة' : 'Payment';
  }
}
