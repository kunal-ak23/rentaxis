import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
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

enum _ChequeFilter { all, cleared, upcoming }

/// Renter Cheques screen — restyled to the Miftah cheque-timeline design
/// (mock 1d / 2b dark variant).
///
/// Layout:
///   1. Chrome header — tenancy eyebrow + "ANNUAL RENT" Cinzel total + gold
///      "N CHEQUES" caption (always near-black, both theme modes)
///   2. Progress card — "Total paid this lease" with a gold gradient bar
///   3. Filter chips — ALL / CLEARED / UPCOMING
///   4. Timeline — every cheque on a status-dot rail (cleared = filled
///      success, overdue/bounced = danger dot with glow ring, upcoming =
///      hollow ring), connected by a hairline. The nearest overdue (else
///      earliest still-pending) cheque renders as an emphasized near-black /
///      heroDark card. Cards are non-interactive — the renter app is
///      informational; cheque submission is handled offline.
class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});

  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen> {
  _ChequeFilter _filter = _ChequeFilter.all;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final paymentsAsync = ref.watch(_myPaymentsProvider);
    final leasesAsync = ref.watch(_myLeasesProvider);

    Future<void> refresh() async {
      ref.invalidate(_myPaymentsProvider);
      ref.invalidate(_myLeasesProvider);
      await ref.read(_myPaymentsProvider.future);
    }

    return Scaffold(
      backgroundColor: m.background,
      body: SafeArea(
        child: RefreshIndicator(
          color: AppColors.primary,
          onRefresh: refresh,
          child: ListView(
            padding: const EdgeInsets.only(bottom: 130),
            physics: const AlwaysScrollableScrollPhysics(),
            children: [
              _ChromeHeader(
                leasesAsync: leasesAsync,
                paymentsAsync: paymentsAsync,
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _ProgressCard(paymentsAsync: paymentsAsync),
                    const SizedBox(height: 18),
                    _FilterChips(
                      selected: _filter,
                      onChanged: (f) => setState(() => _filter = f),
                    ),
                    const SizedBox(height: 6),
                    paymentsAsync.when(
                      loading: () => const _ChequesShimmer(),
                      error: (e, _) => ErrorState(
                        message: l.failedToLoadCheques,
                        onRetry: refresh,
                      ),
                      data: (payments) =>
                          _ChequesTimeline(payments: payments, filter: _filter),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─── Chrome header ──────────────────────────────────────────────────────────

class _ChromeHeader extends StatelessWidget {
  final AsyncValue<List<dynamic>> leasesAsync;
  final AsyncValue<List<dynamic>> paymentsAsync;
  const _ChromeHeader({required this.leasesAsync, required this.paymentsAsync});

  String _tenancyLabel(List<dynamic> leases, _L l) {
    // Filter to typed maps once so the orElse fallback can't crash on a
    // non-Map first entry. (Reviewer nit #6.)
    final typed = leases.whereType<Map<String, dynamic>>().toList();
    final active = typed.firstWhere(
      (leaseMap) =>
          leaseMap['status'] == 'ACTIVE' ||
          leaseMap['status'] == 'PENDING_SIGNATURE',
      orElse: () => typed.isEmpty ? <String, dynamic>{} : typed.first,
    );
    final start = active['startDate']?.toString();
    final end = active['endDate']?.toString();
    if (start == null || end == null) return l.yourTenancy;
    final s = DateTime.tryParse(start);
    final e = DateTime.tryParse(end);
    if (s == null || e == null) return l.yourTenancy;
    if (s.year == e.year) return l.tenancyYear(s.year);
    return l.tenancyYearRange(s.year, e.year);
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final eyebrow = leasesAsync.when(
      data: (leases) => _tenancyLabel(leases, l),
      loading: () => l.yourTenancy,
      error: (_, _) => l.yourTenancy,
    );

    final summary = paymentsAsync.when(
      data: (payments) {
        final rentOnly = payments
            .whereType<Map<String, dynamic>>()
            .where(
              (p) =>
                  p['status'] != 'CANCELLED' &&
                  p['status'] != 'REPLACED' &&
                  !isNonRentPayment(p),
            )
            .toList();
        final total = rentOnly.fold<double>(
          0,
          (s, p) => s + ((p['amount'] ?? 0) as num).toDouble(),
        );
        return (total: total, count: rentOnly.length);
      },
      loading: () => null,
      error: (_, _) => null,
    );

    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 22),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.ar ? eyebrow : eyebrow.toUpperCase(),
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accent.withValues(alpha: 0.7),
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 11,
                    letterSpacing: 2.0,
                    color: AppColors.accent.withValues(alpha: 0.7),
                  ),
          ),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.annualRent,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: Colors.white.withValues(alpha: 0.45),
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 11,
                            letterSpacing: 2.2,
                            color: Colors.white.withValues(alpha: 0.45),
                          ),
                  ),
                  const SizedBox(height: 5),
                  Text(
                    summary == null
                        ? '—'
                        : 'AED ${NumberFormat('#,##0').format(summary.total)}',
                    style: GoogleFonts.cinzel(
                      fontSize: 26,
                      fontWeight: FontWeight.w500,
                      color: Colors.white,
                    ),
                  ),
                ],
              ),
              if (summary != null)
                Text(
                  l.chequeCountLabel(summary.count),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: AppColors.goldMid,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 11,
                          letterSpacing: 1.4,
                          color: AppColors.goldMid,
                        ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

// ─── Progress card ──────────────────────────────────────────────────────────

class _ProgressCard extends StatelessWidget {
  final AsyncValue<List<dynamic>> paymentsAsync;
  const _ProgressCard({required this.paymentsAsync});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return paymentsAsync.when(
      loading: () => const ShimmerLoading(
        height: 110,
        width: double.infinity,
        borderRadius: 12,
      ),
      error: (_, _) => const SizedBox.shrink(),
      data: (payments) {
        final visible = payments
            .whereType<Map<String, dynamic>>()
            .where(
              (p) => p['status'] != 'CANCELLED' && p['status'] != 'REPLACED',
            )
            .toList();
        final total = visible.fold<double>(
          0,
          (s, p) => s + ((p['amount'] ?? 0) as num).toDouble(),
        );
        final paid = visible
            .where((p) => p['status'] == 'CLEARED')
            .fold<double>(
              0,
              (s, p) => s + ((p['amount'] ?? 0) as num).toDouble(),
            );
        final ratio = total > 0 ? (paid / total).clamp(0.0, 1.0) : 0.0;
        // Floor (not round) so "100% complete" only shows when paid==total.
        // Otherwise 99.6% would round up and mislead the user.
        final pct = (ratio * 100).floor();
        return Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: m.surfaceAlt,
            border: Border.all(color: m.border),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  Text(
                    l.totalPaidThisLease,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts
                              .josefinSans)(fontSize: 12, color: m.textMuted),
                  ),
                  Text(
                    l.percentComplete(pct),
                    style:
                        (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color: AppColors.accentDark,
                        ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              RichText(
                text: TextSpan(
                  style: GoogleFonts.cinzel(
                    fontSize: 24,
                    fontWeight: FontWeight.w500,
                    color: m.textPrimary,
                  ),
                  children: [
                    TextSpan(text: 'AED ${NumberFormat('#,##0').format(paid)}'),
                    TextSpan(
                      text: '  / ${NumberFormat('#,##0').format(total)}',
                      style: GoogleFonts.cinzel(
                        fontSize: 13,
                        color: m.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: SizedBox(
                  height: 8,
                  child: Stack(
                    children: [
                      Container(color: m.surface),
                      FractionallySizedBox(
                        widthFactor: ratio,
                        child: Container(
                          decoration: const BoxDecoration(
                            gradient: MiftahGradients.goldProgress,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

// ─── Filter chips ───────────────────────────────────────────────────────────

class _FilterChips extends StatelessWidget {
  final _ChequeFilter selected;
  final ValueChanged<_ChequeFilter> onChanged;
  const _FilterChips({required this.selected, required this.onChanged});

  static String _label(_ChequeFilter f, _L l) => switch (f) {
    _ChequeFilter.all => l.filterAll,
    _ChequeFilter.cleared => l.filterCleared,
    _ChequeFilter.upcoming => l.filterUpcoming,
  };

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Row(
      children: _ChequeFilter.values.map((f) {
        final isSelected = f == selected;
        return Padding(
          padding: const EdgeInsetsDirectional.only(end: 8),
          child: GestureDetector(
            onTap: () => onChanged(f),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(999),
                color: isSelected
                    ? (m.isDark ? AppColors.accent : AppColors.primary)
                    : Colors.transparent,
                border: isSelected ? null : Border.all(color: m.borderStrong),
              ),
              child: Text(
                _label(f, l),
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: isSelected
                            ? (m.isDark ? AppColors.primary : AppColors.accent)
                            : m.textSecondary,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 11.5,
                        letterSpacing: 1.6,
                        fontWeight: FontWeight.w500,
                        color: isSelected
                            ? (m.isDark ? AppColors.primary : AppColors.accent)
                            : m.textSecondary,
                      ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}

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
/// toward "CHEQUE n OF total" / the header's "ANNUAL RENT" summary.
bool isNonRentPayment(Map<String, dynamic> p) =>
    p['isBookingDeposit'] == true ||
    p['isSecurityDeposit'] == true ||
    p['isCharge'] == true;

// ─── Cheques timeline ───────────────────────────────────────────────────────

class _ChequesTimeline extends StatelessWidget {
  final List<dynamic> payments;
  final _ChequeFilter filter;
  const _ChequesTimeline({required this.payments, required this.filter});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final all =
        payments
            .whereType<Map<String, dynamic>>()
            .where(
              (p) => p['status'] != 'CANCELLED' && p['status'] != 'REPLACED',
            )
            .toList()
          ..sort((a, b) {
            final ai = (a['installmentNumber'] ?? 0) as num;
            final bi = (b['installmentNumber'] ?? 0) as num;
            return ai.compareTo(bi);
          });

    if (all.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Icon(Icons.inbox_outlined, color: m.textMuted),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                l.noChequesYet,
                style: (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts
                          .josefinSans)(fontSize: 12.5, color: m.textSecondary),
              ),
            ),
          ],
        ),
      );
    }

    final activePick = _pickActive(all);
    final rentTotal = all.where((p) => !isNonRentPayment(p)).length;

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

    if (list.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(
          l.noChequesInFilter,
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 12.5,
            color: m.textSecondary,
          ),
        ),
      );
    }

    return Column(
      children: [
        for (var i = 0; i < list.length; i++)
          _TimelineRow(
            payment: list[i],
            totalCount: rentTotal,
            isActive: identical(list[i], activePick),
            isLast: i == list.length - 1,
          ),
      ],
    );
  }

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
}

class _TimelineRow extends StatelessWidget {
  final Map<String, dynamic> payment;
  final int totalCount;
  final bool isActive;
  final bool isLast;
  const _TimelineRow({
    required this.payment,
    required this.totalCount,
    required this.isActive,
    required this.isLast,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = effectivePaymentStatus(payment);
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 27,
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 20),
                  child: _StatusDot(status: status),
                ),
                if (!isLast)
                  Expanded(
                    child: Center(child: Container(width: 1, color: m.border)),
                  ),
              ],
            ),
          ),
          Expanded(
            child: Padding(
              padding: EdgeInsets.only(bottom: isLast ? 0 : 14),
              child: _ChequeTimelineCard(
                payment: payment,
                totalCount: totalCount,
                isActive: isActive,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusDot extends StatelessWidget {
  final String status;
  const _StatusDot({required this.status});

  static const _clearedLike = {'CLEARED', 'COLLECTED', 'DEPOSITED'};
  static const _dangerLike = {'OVERDUE', 'BOUNCED'};

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;

    if (_clearedLike.contains(status)) {
      return Container(
        width: 11,
        height: 11,
        decoration: BoxDecoration(shape: BoxShape.circle, color: m.success),
      );
    }
    if (_dangerLike.contains(status)) {
      return Container(
        width: 11,
        height: 11,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: m.danger,
          boxShadow: [
            BoxShadow(
              color: m.danger.withValues(alpha: 0.16),
              blurRadius: 0,
              spreadRadius: 4,
            ),
          ],
        ),
      );
    }
    return Container(
      width: 11,
      height: 11,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: m.background,
        border: Border.all(color: m.borderStrong, width: 1.5),
      ),
    );
  }
}

class _ChequeTimelineCard extends StatelessWidget {
  final Map<String, dynamic> payment;
  final int totalCount;
  final bool isActive;
  const _ChequeTimelineCard({
    required this.payment,
    required this.totalCount,
    required this.isActive,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final status = effectivePaymentStatus(payment);
    final amount = (payment['amount'] ?? 0) as num;
    final n = payment['installmentNumber'];
    final dueRaw = payment['dueDate']?.toString();
    final cheque = payment['chequeNumber']?.toString();
    final bank = payment['bankName']?.toString();
    final dueLabel = _formatDate(dueRaw, ar: l.ar);
    final isNonRent = isNonRentPayment(payment);
    final purposeLabel = payment['purposeLabel']?.toString();
    final penalty = (payment['penaltyAmount'] ?? 0) as num;
    final totalPayable = (payment['totalPayable'] ?? amount) as num;
    final property = payment['propertyName']?.toString();
    final unit = payment['unitIdentifier']?.toString();

    final overline =
        isNonRent && purposeLabel != null && purposeLabel.isNotEmpty
        ? (l.ar ? _purposeAr(purposeLabel) : purposeLabel.toUpperCase())
        : n != null
        ? l.chequeOfTotal(n, totalCount)
        : l.chequeWord;

    final subtitleParts = <String>[
      if (cheque != null && cheque.isNotEmpty) l.refLabel(cheque),
      if (bank != null && bank.isNotEmpty) bank,
      if ((property != null && property.isNotEmpty) ||
          (unit != null && unit.isNotEmpty))
        [
          property,
          unit,
        ].whereType<String>().where((s) => s.isNotEmpty).join(' — '),
    ];
    final subtitle = _subtitleFor(status, payment, dueLabel, l);

    final onDark = isActive;
    final mutedTextColor = onDark
        ? Colors.white.withValues(alpha: 0.5)
        : m.textMuted;
    final secondaryTextColor = onDark
        ? Colors.white.withValues(alpha: 0.55)
        : m.textSecondary;
    final amountColor = onDark ? Colors.white : m.textPrimary;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: isActive
          ? BoxDecoration(
              color: m.isDark ? null : const Color(0xFF111111),
              gradient: m.isDark ? MiftahGradients.heroDark : null,
              border: Border.all(
                color: AppColors.accent.withValues(
                  alpha: m.isDark ? 0.3 : 0.22,
                ),
              ),
              borderRadius: BorderRadius.circular(14),
            )
          : BoxDecoration(
              color: m.surface,
              border: Border.all(color: m.border),
              borderRadius: BorderRadius.circular(14),
            ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Text(
                  overline,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color: mutedTextColor,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 11,
                          letterSpacing: 1.6,
                          color: mutedTextColor,
                        ),
                ),
              ),
              _StatusPill(status: status, onDark: onDark),
            ],
          ),
          SizedBox(height: isActive ? 10 : 6),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                'AED ${NumberFormat('#,##0').format(amount)}',
                style: GoogleFonts.cinzel(
                  fontSize: isActive ? 24 : 20,
                  fontWeight: FontWeight.w500,
                  color: amountColor,
                ),
              ),
              if (dueLabel.isNotEmpty)
                Text(
                  dueLabel,
                  style:
                      (l.ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: 12,
                        color: secondaryTextColor,
                      ),
                ),
            ],
          ),
          if (subtitleParts.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              subtitleParts.join(' · '),
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts
                        .josefinSans)(fontSize: 11.5, color: mutedTextColor),
            ),
          ],
          if (penalty > 0)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                l.penaltyLine(
                  NumberFormat('#,##0').format(penalty),
                  NumberFormat('#,##0').format(totalPayable),
                ),
                style:
                    (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: m.danger,
                    ),
              ),
            ),
          if (subtitle.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                subtitle,
                style:
                    (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                      fontSize: 11,
                      color: mutedTextColor,
                      fontStyle: FontStyle.italic,
                    ),
              ),
            ),
        ],
      ),
    );
  }

  /// Best-effort Arabic for backend-provided purpose labels; unknown labels
  /// pass through unchanged.
  String _purposeAr(String label) {
    final s = label.toLowerCase();
    if (s.contains('booking')) return 'دفعة الحجز';
    if (s.contains('admin')) return 'رسوم إدارية';
    if (s.contains('security') || s.contains('deposit')) {
      return 'مبلغ التأمين';
    }
    if (s.contains('service')) return 'رسوم الخدمات';
    if (s.contains('rent')) return 'الإيجار';
    return label;
  }

  String _formatDate(String? iso, {bool ar = false}) {
    if (iso == null || iso.isEmpty) return '';
    final dt = DateTime.tryParse(iso);
    if (dt == null) return '';
    if (ar) return DateFormat('d MMMM yyyy', 'ar').format(dt);
    // LTR isolate keeps "20 Dec 2025" ordered inside RTL text.
    return '\u2066${DateFormat('d MMM yyyy').format(dt)}\u2069';
  }

  /// Status-specific subtitle line. Empty string → don't render.
  String _subtitleFor(
    String status,
    Map<String, dynamic> payment,
    String dueLabel,
    _L l,
  ) {
    final raw = payment['statusChangedAt']?.toString();
    final formatted = _formatDate(raw, ar: l.ar);
    final reason = payment['failureReason']?.toString();
    switch (status) {
      case 'COLLECTED':
        return l.collectedLine(formatted);
      case 'DEPOSITED':
        return l.depositedLine(formatted);
      case 'BOUNCED':
        final head = l.bouncedLine(formatted);
        return reason == null || reason.isEmpty ? head : '$head · $reason';
      case 'OVERDUE':
        return l.overdueLine(dueLabel);
      default:
        return '';
    }
  }
}

class _StatusPill extends StatelessWidget {
  final String status;

  /// Whether this pill sits on the emphasized (near-black / heroDark) card.
  /// Cleared/upcoming pills stay filled; danger pills on that card switch to
  /// an outlined style so they read against the dark fill, per the design.
  final bool onDark;
  const _StatusPill({required this.status, required this.onDark});

  ({Color fg, Color bg, String label}) _meta(MiftahColors m, _L l) {
    switch (status) {
      case 'CLEARED':
        return (fg: m.success, bg: m.successBg, label: l.statusCleared);
      case 'COLLECTED':
      case 'DEPOSITED':
        // Bronze, not success-green: collected/deposited cheques can still
        // bounce, so they must not read as settled (matches home activity).
        return (
          fg: m.isDark ? AppColors.goldMid : AppColors.accentDark,
          bg: AppColors.accent.withValues(alpha: 0.12),
          label: status == 'COLLECTED' ? l.statusCollected : l.statusDeposited,
        );
      case 'BOUNCED':
        return (fg: m.danger, bg: m.dangerBg, label: l.statusBounced);
      case 'OVERDUE':
        return (fg: m.danger, bg: m.dangerBg, label: l.statusOverdue);
      case 'PENDING':
      default:
        return (
          fg: onDark ? Colors.white.withValues(alpha: 0.6) : m.textSecondary,
          bg: onDark ? Colors.white.withValues(alpha: 0.07) : m.surfaceAlt,
          label: l.statusUpcoming,
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final meta = _meta(m, l);
    final outlined = onDark && (status == 'BOUNCED' || status == 'OVERDUE');

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: outlined ? Colors.transparent : meta.bg,
        borderRadius: BorderRadius.circular(999),
        border: outlined
            ? Border.all(color: meta.fg.withValues(alpha: 0.5))
            : null,
      ),
      child: Text(
        l.ar ? meta.label : meta.label.toUpperCase(),
        style: l.ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: meta.fg,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                fontWeight: FontWeight.w500,
                color: meta.fg,
                letterSpacing: 1.2,
              ),
      ),
    );
  }
}

// ─── Strings (EN/AR) ────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
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
  String get filterAll => ar ? 'الكل' : 'ALL';
  String get filterCleared => ar ? 'مسدّدة' : 'CLEARED';
  String get filterUpcoming => ar ? 'القادمة' : 'UPCOMING';

  // Timeline empty states
  String get noChequesYet => ar
      ? 'لا توجد شيكات بعد — ستظهر هنا بمجرد إعداد عقدك.'
      : 'No cheques yet — they will appear here once your lease is set up.';
  String get noChequesInFilter =>
      ar ? 'لا توجد شيكات ضمن هذا التصفية.' : 'No cheques in this filter.';

  // Timeline card
  String get chequeWord => ar ? 'شيك' : 'CHEQUE';
  String chequeOfTotal(dynamic n, int total) {
    final nn = n.toString().padLeft(2, '0');
    final tt = total.toString().padLeft(2, '0');
    return ar ? 'شيك $nn من $tt' : 'CHEQUE $nn OF $tt';
  }

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
}

class _ChequesShimmer extends StatelessWidget {
  const _ChequesShimmer();

  @override
  Widget build(BuildContext context) {
    return Column(
      children: List.generate(
        4,
        (_) => const Padding(
          padding: EdgeInsets.only(bottom: 10),
          child: ShimmerLoading(
            height: 92,
            width: double.infinity,
            borderRadius: 14,
          ),
        ),
      ),
    );
  }
}
