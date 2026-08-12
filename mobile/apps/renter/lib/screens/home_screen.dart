import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

final _homePenaltyServiceProvider = Provider<PenaltyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PenaltyService(client.dio);
});

final _myLeasesProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  return ref.watch(_leaseServiceProvider).getMyLeases();
});

final _myPaymentsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  return ref.watch(_paymentServiceProvider).getMyPayments();
});

final _openPenaltyCountProvider = FutureProvider.autoDispose<int>((ref) async {
  final list = await ref
      .watch(_homePenaltyServiceProvider)
      .listPenalties(status: 'open', size: 50);
  return list.length;
});

/// Renter Home — Miftah dashboard. Dark chrome greeting strip, near-black
/// "next payment" hero with gold hairline + progress, quick-action tiles,
/// and a recent-activity list — restyled to match the Miftah design system
/// (`context.miftah` tokens so it renders correctly in light and dark mode).
///
/// Layout from top:
///   1. Header: dark chrome greeting + property/tenancy line + avatar
///   2. Hero balance card: near-black card, gold hairline, amount due,
///      gold progress bar, Pay-now + Set-reminder buttons
///   3. Quick actions: 4-col grid (Cheques · Maintain · Contract · Inbox)
///   4. Recent activity: list of recent payments
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final leasesAsync = ref.watch(_myLeasesProvider);
    final paymentsAsync = ref.watch(_myPaymentsProvider);
    final auth = ref.watch(authProvider);

    Future<void> refresh() async {
      ref.invalidate(_myLeasesProvider);
      ref.invalidate(_myPaymentsProvider);
      ref.invalidate(_openPenaltyCountProvider);
      ref.read(notificationProvider.notifier).fetchUnreadCount();
      await ref.read(_myLeasesProvider.future);
    }

    return Scaffold(
      backgroundColor: m.background,
      body: RefreshIndicator(
        color: AppColors.accent,
        backgroundColor: m.surface,
        onRefresh: refresh,
        child: ListView(
          padding: EdgeInsets.only(bottom: 24),
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            // Header is full-bleed chrome; the rest of the content is inset.
            leasesAsync.when(
              data: (leases) =>
                  _Header(lease: _findActiveLease(leases), userName: auth.name),
              loading: () => _Header(lease: null, userName: auth.name),
              error: (_, _) => _Header(lease: null, userName: auth.name),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _MaybeHero(
                    leasesAsync: leasesAsync,
                    paymentsAsync: paymentsAsync,
                  ),
                  const SizedBox(height: 18),
                  _QuickActions(
                    penaltyBadge: ref
                        .watch(_openPenaltyCountProvider)
                        .valueOrNull,
                  ),
                  const SizedBox(height: 14),
                  const _FacilitiesCard(),
                  const SizedBox(height: 22),
                  _RecentActivityHeader(),
                  const SizedBox(height: 10),
                  paymentsAsync.when(
                    loading: () => const _ActivityShimmer(),
                    // Surface the failure instead of a silent blank section.
                    error: (_, _) => ErrorState(
                      message: _L(context.isAr).activityLoadFailed,
                      onRetry: refresh,
                    ),
                    data: (payments) => _RecentActivity(payments: payments),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

Map<String, dynamic>? _findActiveLease(List<dynamic> leases) {
  for (final l in leases) {
    if (l is Map<String, dynamic> &&
        (l['status'] == 'ACTIVE' || l['status'] == 'PENDING_SIGNATURE')) {
      return l;
    }
  }
  if (leases.isNotEmpty && leases.first is Map<String, dynamic>) {
    return leases.first as Map<String, dynamic>;
  }
  return null;
}

Map<String, dynamic>? _nextPaymentFor(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return null;
  // OVERDUE takes priority, then PENDING, then ONLINE_PENDING (checkout in
  // flight) — mirrors the web renter portal's nextPayment selection.
  const statusPriority = {'OVERDUE': 0, 'PENDING': 1, 'ONLINE_PENDING': 2};
  final mine =
      payments
          .whereType<Map<String, dynamic>>()
          .where(
            (p) =>
                p['leaseId'] == leaseId &&
                statusPriority.containsKey(p['status']),
          )
          .toList()
        ..sort((a, b) {
          final ap = statusPriority[a['status']] ?? 9;
          final bp = statusPriority[b['status']] ?? 9;
          if (ap != bp) return ap.compareTo(bp);
          final ad = (a['installmentNumber'] ?? 0) as num;
          final bd = (b['installmentNumber'] ?? 0) as num;
          return ad.compareTo(bd);
        });
  return mine.isEmpty ? null : mine.first;
}

int _clearedCount(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return 0;
  return payments
      .whereType<Map<String, dynamic>>()
      .where((p) => p['leaseId'] == leaseId && p['status'] == 'CLEARED')
      .length;
}

int _totalInstalmentsFor(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return 0;
  return payments
      .whereType<Map<String, dynamic>>()
      .where((p) => p['leaseId'] == leaseId)
      .length;
}

/// Sum of `amount` over CLEARED payments — what the renter has actually
/// paid. Avoids the assumption that every cheque is the same size.
double _clearedAmountFor(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return 0;
  return payments
      .whereType<Map<String, dynamic>>()
      .where((p) => p['leaseId'] == leaseId && p['status'] == 'CLEARED')
      .fold<double>(0, (s, p) => s + ((p['amount'] ?? 0) as num).toDouble());
}

/// Sum of `amount` over the entire lease schedule. Matches the
/// denominator the renter expects to see ("X / Y") so cleared can
/// converge on total at full clearance, regardless of rentAmount being
/// rent-only vs schedule-with-VAT.
double _scheduleTotalFor(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return 0;
  return payments
      .whereType<Map<String, dynamic>>()
      .where(
        (p) =>
            p['leaseId'] == leaseId &&
            p['status'] != 'CANCELLED' &&
            p['status'] != 'REPLACED',
      )
      .fold<double>(0, (s, p) => s + ((p['amount'] ?? 0) as num).toDouble());
}

// ─── Header ─────────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  final Map<String, dynamic>? lease;
  final String? userName;
  const _Header({required this.lease, required this.userName});

  String _initials() {
    final name = (userName ?? '').trim();
    if (name.isEmpty) return 'ME';
    final parts = name.split(RegExp(r'\s+'));
    if (parts.first.isEmpty) return 'ME';
    if (parts.length == 1) return parts.first.substring(0, 1).toUpperCase();
    return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
  }

  String _greeting(_L l) {
    final hour = DateTime.now().hour;
    if (hour < 12) return l.goodMorning;
    if (hour < 17) return l.goodAfternoon;
    return l.goodEvening;
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final propertyName = lease?['propertyName']?.toString();
    // Backend's LeaseDTO uses `unitIdentifier`. We fall back to
    // `unitNumber` defensively in case other endpoints emit it.
    final unit = (lease?['unitIdentifier'] ?? lease?['unitNumber'])?.toString();
    final tenancyLabel =
        (propertyName != null && unit != null && unit.isNotEmpty)
        ? '$propertyName · $unit'
        : (propertyName ?? l.noActiveLease);
    final displayName = (userName != null && userName!.trim().isNotEmpty)
        ? userName!
        : l.defaultRenterName;

    return Container(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(bottom: BorderSide(color: m.chromeBorder)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Image.asset('assets/logo_mark.png', width: 40, height: 40),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _greeting(l),
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 11.5,
                              fontWeight: FontWeight.w500,
                              color: Colors.white.withValues(alpha: 0.45),
                            )
                          : LegacyMiftahType.overline(
                              fontSize: 10.5,
                              letterSpacing: 2.4,
                              color: Colors.white.withValues(alpha: 0.45),
                            ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      displayName,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 17,
                              fontWeight: FontWeight.w600,
                              color: Colors.white,
                            )
                          : LegacyMiftahType.display(
                              fontSize: 16,
                              letterSpacing: 0.6,
                              color: Colors.white,
                            ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: AppColors.accent.withValues(alpha: 0.4),
                  ),
                ),
                child: Text(
                  _initials(),
                  style: GoogleFonts.josefinSans(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accent,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Container(width: 14, height: 1, color: AppColors.goldMid),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  tenancyLabel,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13,
                          color: Colors.white.withValues(alpha: 0.62),
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 12.5,
                          letterSpacing: 0.4,
                          color: Colors.white.withValues(alpha: 0.62),
                        ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ─── Hero balance card ──────────────────────────────────────────────────────

class _MaybeHero extends StatelessWidget {
  final AsyncValue<List<dynamic>> leasesAsync;
  final AsyncValue<List<dynamic>> paymentsAsync;
  const _MaybeHero({required this.leasesAsync, required this.paymentsAsync});

  @override
  Widget build(BuildContext context) {
    if (leasesAsync.isLoading || paymentsAsync.isLoading) {
      return const ShimmerLoading(
        height: 220,
        width: double.infinity,
        borderRadius: 18,
      );
    }
    final leases = leasesAsync.valueOrNull ?? const [];
    final payments = paymentsAsync.valueOrNull ?? const [];
    final lease = _findActiveLease(leases);
    if (lease == null) return const _NoLeaseCard();
    final leaseId = lease['id']?.toString();
    final next = _nextPaymentFor(payments, leaseId);
    final cleared = _clearedCount(payments, leaseId);
    final total = _totalInstalmentsFor(payments, leaseId);
    final clearedAmount = _clearedAmountFor(payments, leaseId);
    final scheduleTotal = _scheduleTotalFor(payments, leaseId);
    return _HeroBalanceCard(
      lease: lease,
      next: next,
      clearedCount: cleared,
      totalCount: total,
      clearedAmount: clearedAmount,
      scheduleTotal: scheduleTotal,
    );
  }
}

class _NoLeaseCard extends StatelessWidget {
  const _NoLeaseCard();

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          Icon(Icons.info_outline, color: m.textMuted, size: 18),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              l.noLeaseMessage,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.inter(fontSize: 12.5, color: m.textSecondary),
            ),
          ),
        ],
      ),
    );
  }
}

class _HeroBalanceCard extends StatelessWidget {
  final Map<String, dynamic> lease;
  final Map<String, dynamic>? next;
  final int clearedCount;
  final int totalCount;
  final double clearedAmount;
  final double scheduleTotal;
  const _HeroBalanceCard({
    required this.lease,
    required this.next,
    required this.clearedCount,
    required this.totalCount,
    required this.clearedAmount,
    required this.scheduleTotal,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final amount = (next?['amount'] ?? 0) as num;
    final dueRaw = next?['dueDate']?.toString();
    final installmentNumber = next?['installmentNumber'];
    final installmentTotal = totalCount;
    final daysToDue = _daysUntil(dueRaw);
    final status = next?['status']?.toString();
    final isOverdue = status == 'OVERDUE' || daysToDue == 'overdue';
    final isInFlight = status == 'ONLINE_PENDING';
    // If the schedule sum is unavailable (e.g. payments not loaded), fall
    // back to lease.rentAmount so the totals slot isn't empty.
    final totalAmount = scheduleTotal > 0
        ? scheduleTotal
        : ((lease['rentAmount'] ?? 0) as num).toDouble();

    final ({String label, Color fg, Color bg, Color border}) pill;
    if (next == null) {
      pill = (
        label: l.allClear,
        fg: AppColorsDark.success,
        bg: AppColorsDark.success.withValues(alpha: 0.16),
        border: AppColorsDark.success.withValues(alpha: 0.4),
      );
    } else if (isOverdue) {
      pill = (
        label: l.overdue,
        fg: AppColorsDark.danger,
        bg: AppColorsDark.danger.withValues(alpha: 0.16),
        border: AppColorsDark.danger.withValues(alpha: 0.45),
      );
    } else if (isInFlight) {
      pill = (
        label: l.processing,
        fg: AppColorsDark.warning,
        bg: AppColorsDark.warning.withValues(alpha: 0.16),
        border: AppColorsDark.warning.withValues(alpha: 0.45),
      );
    } else {
      pill = (
        label: daysToDue == 'today'
            ? l.dueToday
            : l.due(l.daysToDueLabel(daysToDue)),
        fg: AppColors.accent,
        bg: AppColors.accent.withValues(alpha: 0.14),
        border: AppColors.accent.withValues(alpha: 0.4),
      );
    }

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        color: m.isDark ? null : AppColors.navyDark,
        gradient: m.isDark ? LegacyMiftahGradients.heroDark : null,
        border: Border.all(
          color: AppColors.accent.withValues(alpha: m.isDark ? 0.3 : 0.22),
        ),
        boxShadow: AppShadows.hero,
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  l.nextPayment,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: Colors.white.withValues(alpha: 0.5),
                        )
                      : LegacyMiftahType.overline(
                          fontSize: 11,
                          letterSpacing: 3.2,
                          color: Colors.white.withValues(alpha: 0.5),
                        ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: pill.bg,
                    border: Border.all(color: pill.border),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    pill.label,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            color: pill.fg,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 10.5,
                            fontWeight: FontWeight.w600,
                            letterSpacing: 1.6,
                            color: pill.fg,
                          ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                if (next != null)
                  Padding(
                    padding: const EdgeInsetsDirectional.only(end: 9),
                    child: Text(
                      'AED',
                      style: LegacyMiftahType.display(
                        fontSize: 15,
                        letterSpacing: 1.4,
                        color: AppColors.goldMid,
                      ),
                    ),
                  ),
                Text(
                  next == null
                      ? l.allCaughtUp
                      : NumberFormat('#,##0').format(amount),
                  style: (next == null && l.ar)
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        )
                      : LegacyMiftahType.display(
                          fontSize: next == null ? 22 : 40,
                          letterSpacing: 0.4,
                          color: Colors.white,
                        ),
                ),
              ],
            ),
            if (next != null) ...[
              const SizedBox(height: 4),
              RichText(
                text: TextSpan(
                  style:
                      (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.inter)(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.7),
                      ),
                  children: [
                    TextSpan(
                      text: l.chequeLabel(installmentNumber, installmentTotal),
                    ),
                    TextSpan(
                      text: isOverdue
                          ? l.sepOverdue
                          : isInFlight
                          ? l.sepProcessing
                          : daysToDue == 'today'
                          ? l.sepDueToday
                          : l.sepDueIn,
                    ),
                    TextSpan(
                      text: isOverdue
                          ? l.overdueWord
                          : isInFlight
                          ? l.processingWord
                          : l.daysToDueLabel(daysToDue),
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.inter)(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: isOverdue
                                ? AppColorsDark.danger
                                : AppColors.gold400,
                          ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 16),
            Container(height: 1, color: Colors.white.withValues(alpha: 0.1)),
            const SizedBox(height: 12),
            if (totalCount > 0) ...[
              _Progress(cleared: clearedCount, total: totalCount),
              const SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Flexible(
                    child: Text(
                      l.clearedCountLabel(clearedCount, totalCount),
                      overflow: TextOverflow.ellipsis,
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.inter)(
                            fontSize: 11.5,
                            color: Colors.white.withValues(alpha: 0.7),
                          ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    '${_formatAmount(clearedAmount)} / ${_formatAmount(totalAmount)}',
                    style: GoogleFonts.jetBrainsMono(
                      fontSize: 11,
                      color: Colors.white.withValues(alpha: 0.7),
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: GoldButton(
                    label: l.viewCheques,
                    height: 44,
                    onPressed: () => context.push('/payments'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: GoldButton.outlined(
                    label: l.setReminder,
                    height: 44,
                    onDark: true,
                    onPressed: () {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(l.remindersComingSoon)),
                      );
                    },
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _formatAmount(num n) {
    return 'AED ${NumberFormat('#,##0').format(n)}';
  }

  String _daysUntil(String? iso) {
    if (iso == null || iso.isEmpty) return '—';
    try {
      final dt = DateTime.parse(iso).toLocal();
      final days = dt.difference(DateTime.now()).inDays;
      if (days < 0) return 'overdue';
      if (days == 0) return 'today';
      if (days == 1) return '1 day';
      return '$days days';
    } catch (_) {
      return '—';
    }
  }
}

class _Progress extends StatelessWidget {
  final int cleared;
  final int total;
  const _Progress({required this.cleared, required this.total});

  @override
  Widget build(BuildContext context) {
    // For long monthly schedules (12+) discrete segments become tiny
    // hairlines that read poorly. Render a single proportional bar instead.
    if (total > 6) {
      final ratio = total > 0 ? (cleared / total).clamp(0.0, 1.0) : 0.0;
      return Stack(
        children: [
          Container(
            height: 3,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(3),
            ),
          ),
          FractionallySizedBox(
            widthFactor: ratio,
            child: Container(
              height: 3,
              decoration: BoxDecoration(
                gradient: LegacyMiftahGradients.goldProgress,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
          ),
        ],
      );
    }
    return Row(
      children: List.generate(total, (i) {
        final filled = i < cleared;
        return Expanded(
          child: Container(
            height: 3,
            margin: EdgeInsetsDirectional.only(end: i == total - 1 ? 0 : 6),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(3),
              gradient: filled ? LegacyMiftahGradients.goldProgress : null,
              color: filled ? null : Colors.white.withValues(alpha: 0.12),
            ),
          ),
        );
      }),
    );
  }
}

// ─── Quick actions ──────────────────────────────────────────────────────────

/// Entry card for the amenities & parking booking flow (route /facilities).
/// A full-width card rather than a sixth quick-action tile: the grid above is
/// deliberately five-across so the row stays whole (see its comment), and a
/// sixth tile would strand one on a second row.
class _FacilitiesCard extends StatelessWidget {
  const _FacilitiesCard();

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: () => context.push('/facilities'),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppColors.accent.withValues(alpha: 0.1),
                border: Border.all(
                  color: AppColors.accent.withValues(alpha: 0.35),
                ),
              ),
              child: const Icon(
                Icons.pool_outlined,
                size: 20,
                color: AppColors.accentDark,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.facilitiesTitle,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 14.5,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    l.facilitiesSub,
                    style:
                        (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                          fontSize: 12,
                          color: m.textSecondary,
                        ),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, size: 18, color: m.textMuted),
          ],
        ),
      ),
    );
  }
}

class _QuickActions extends StatelessWidget {
  final int? penaltyBadge;
  const _QuickActions({this.penaltyBadge});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final actions = [
      (
        icon: Icons.receipt_long_outlined,
        label: l.cheques,
        route: '/payments',
        badge: (penaltyBadge != null && penaltyBadge! > 0)
            ? penaltyBadge
            : null,
      ),
      (
        icon: Icons.build_outlined,
        label: l.maintain,
        route: '/tickets',
        badge: null,
      ),
      (
        icon: Icons.description_outlined,
        label: l.contract,
        route: '/profile',
        badge: null,
      ),
      (
        icon: Icons.contact_mail_outlined,
        label: l.inbox,
        route: '/notifications',
        badge: null,
      ),
      // "Visitors" rather than "Gate Pass": it is what the renter is arranging,
      // it matches the guard app's language for the same objects, and it is
      // short enough to sit under the icon on a narrow phone.
      (
        icon: Icons.qr_code_2_outlined,
        label: l.visitors,
        route: '/gatepass',
        badge: null,
      ),
    ];
    // Five across rather than four, so the row stays whole instead of leaving a
    // single tile stranded on a second row.
    //
    // The aspect ratio drops from 0.95 to buy height, because narrowing the
    // tiles is what puts the labels at risk: these `Text`s wrap rather than
    // ellipsize, so on a ~320pt phone (tile ≈ 51pt) a label that no longer fits
    // takes a second line and would overflow the tile vertically at the old
    // ratio. 0.75 leaves room for that second line rather than betting no label
    // ever needs one.
    return GridView.count(
      // Nested in a scroll view: without this the sliver auto-pads
      // with MediaQuery.padding, which under extendBody carries the
      // floating nav height and opens a gap below the content.
      padding: EdgeInsets.zero,
      crossAxisCount: 5,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: 6,
      mainAxisSpacing: 8,
      childAspectRatio: 0.75,
      children: actions
          .map(
            (a) => InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: () => context.go(a.route),
              child: Container(
                decoration: BoxDecoration(
                  color: m.surface,
                  border: Border.all(color: m.border),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Stack(
                      clipBehavior: Clip.none,
                      children: [
                        Icon(a.icon, size: 22, color: AppColors.accentDark),
                        if (a.badge != null)
                          PositionedDirectional(
                            top: -6,
                            end: -8,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 4,
                              ),
                              constraints: const BoxConstraints(
                                minWidth: 16,
                                minHeight: 16,
                              ),
                              decoration: BoxDecoration(
                                color: m.danger,
                                borderRadius: BorderRadius.circular(999),
                                border: Border.all(color: m.surface, width: 2),
                              ),
                              child: Center(
                                child: Text(
                                  '${a.badge}',
                                  style: GoogleFonts.inter(
                                    fontSize: 9,
                                    fontWeight: FontWeight.w700,
                                    color: Colors.white,
                                  ),
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 9),
                    // Single-word labels ("CONTRACT") cannot wrap on a word
                    // boundary, so without this they split mid-word. Scale the
                    // long ones down rather than breaking them.
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        l.ar ? a.label : a.label.toUpperCase(),
                        textAlign: TextAlign.center,
                        maxLines: 2,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 11.5,
                                height: 1.25,
                                fontWeight: FontWeight.w600,
                                color: m.textSecondary,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 10.5,
                                height: 1.25,
                                fontWeight: FontWeight.w500,
                                letterSpacing: 1.1,
                                color: m.textSecondary,
                              ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          )
          .toList(),
    );
  }
}

// ─── Recent activity ────────────────────────────────────────────────────────

class _RecentActivityHeader extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        Text(
          l.recentActivity,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: m.textPrimary,
                )
              : LegacyMiftahType.display(
                  fontSize: 14,
                  letterSpacing: 2.2,
                  color: m.textPrimary,
                ),
        ),
        InkWell(
          onTap: () => context.go('/payments'),
          child: Text(
            l.all,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: m.isDark ? AppColors.accent : AppColors.accentDark,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1.6,
                    color: m.isDark ? AppColors.accent : AppColors.accentDark,
                  ),
          ),
        ),
      ],
    );
  }
}

class _RecentActivity extends StatelessWidget {
  final List<dynamic> payments;
  const _RecentActivity({required this.payments});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final visible =
        payments
            .whereType<Map<String, dynamic>>()
            .where(
              (p) => p['status'] != 'CANCELLED' && p['status'] != 'REPLACED',
            )
            .toList()
          ..sort((a, b) {
            DateTime parse(String? s) => s == null
                ? DateTime(1900)
                : DateTime.tryParse(s) ?? DateTime(1900);
            final ad = parse(
              a['statusChangedAt']?.toString() ?? a['dueDate']?.toString(),
            );
            final bd = parse(
              b['statusChangedAt']?.toString() ?? b['dueDate']?.toString(),
            );
            return bd.compareTo(ad);
          });
    final top = visible.take(5).toList();
    if (top.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            Icon(Icons.inbox_outlined, color: m.textMuted),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                l.noRecentActivity,
                style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.inter)(
                  fontSize: 12.5,
                  color: m.textSecondary,
                ),
              ),
            ),
          ],
        ),
      );
    }
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        children: List.generate(top.length, (i) {
          return _ActivityRow(
            payment: top[i],
            showDivider: i != top.length - 1,
          );
        }),
      ),
    );
  }
}

class _ActivityRow extends StatelessWidget {
  final Map<String, dynamic> payment;
  final bool showDivider;
  const _ActivityRow({required this.payment, required this.showDivider});

  ({Color color, String title, String word}) _meta(
    LegacyMiftahColors m,
    String status,
    _L l,
  ) {
    switch (status) {
      case 'CLEARED':
        return (color: m.success, title: l.chequeCleared, word: l.wordCleared);
      case 'COLLECTED':
        return (
          color: AppColors.accentDark,
          title: l.chequeReceived,
          word: l.wordReceived,
        );
      case 'DEPOSITED':
        return (
          color: AppColors.accentDark,
          title: l.chequeDeposited,
          word: l.wordDeposited,
        );
      case 'BOUNCED':
        return (color: m.danger, title: l.chequeBounced, word: l.wordBounced);
      case 'PENDING':
        return (color: m.warning, title: l.upcomingPayment, word: l.wordDue);
      case 'OVERDUE':
        return (color: m.danger, title: l.paymentOverdue, word: l.wordOverdue);
      default:
        return (
          color: m.textMuted,
          title: l.paymentGeneric,
          word: l.wordLogged,
        );
    }
  }

  String _formatDate(dynamic raw, {bool ar = false}) {
    final s = raw?.toString();
    if (s == null || s.isEmpty) return '';
    final dt = DateTime.tryParse(s);
    if (dt == null) return '';
    if (ar) return DateFormat('d MMMM', 'ar').format(dt);
    // LTR isolate keeps "1 Oct" ordered inside RTL text.
    return '\u2066${DateFormat('d MMM').format(dt)}\u2069';
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final status = payment['status']?.toString() ?? 'PENDING';
    final meta = _meta(m, status, l);
    final amount = (payment['amount'] ?? 0) as num;
    final installment = payment['installmentNumber'];
    final bank = payment['bankName']?.toString();
    final dateLabel = _formatDate(
      payment['statusChangedAt'] ?? payment['dueDate'],
      ar: l.ar,
    );
    final subtitle = [
      if (bank != null && bank.isNotEmpty) bank,
      if (dateLabel.isNotEmpty) dateLabel,
    ].join(' · ');
    final title = installment != null
        ? '${meta.title} · #$installment'
        : meta.title;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        border: showDivider
            ? Border(bottom: BorderSide(color: m.divider))
            : null,
      ),
      child: Row(
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: meta.color,
            ),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style:
                      (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.inter)(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w500,
                        color: m.textPrimary,
                      ),
                ),
                if (subtitle.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(
                    subtitle,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts
                              .inter)(fontSize: 11.5, color: m.textMuted),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                meta.word,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w600,
                        color: meta.color,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 1.4,
                        color: meta.color,
                      ),
              ),
              if (amount > 0) ...[
                const SizedBox(height: 3),
                Text(
                  'AED ${NumberFormat('#,##0').format(amount)}',
                  style: GoogleFonts.jetBrainsMono(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600,
                    color: m.textSecondary,
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

// ─── Strings (EN/AR) ────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  // Header
  String get goodMorning => ar ? 'صباح الخير' : 'GOOD MORNING';
  String get goodAfternoon => ar ? 'طاب يومك' : 'GOOD AFTERNOON';
  String get goodEvening => ar ? 'مساء الخير' : 'GOOD EVENING';
  String get defaultRenterName => ar ? 'مستأجر' : 'Renter';
  String get noActiveLease => ar ? 'لا يوجد عقد نشط' : 'No active lease';
  String get noLeaseMessage => ar
      ? 'لا يوجد لديك عقد إيجار نشط بعد. بمجرد بدء عقدك سيظهر رصيدك هنا.'
      : "You don't have an active lease yet. Once your tenancy starts you'll see your balance here.";

  // Hero balance card
  String get nextPayment => ar ? 'الدفعة القادمة' : 'NEXT PAYMENT';
  String get allClear => ar ? 'لا مستحقات' : 'ALL CLEAR';
  String get overdue => ar ? 'متأخر' : 'OVERDUE';
  String get processing => ar ? 'قيد المعالجة' : 'PROCESSING';
  String get dueToday => ar ? 'مستحق اليوم' : 'DUE TODAY';
  String due(String duration) =>
      ar ? 'مستحق $duration' : 'DUE ${duration.toUpperCase()}';
  String get allCaughtUp => ar ? 'لا توجد مستحقات حالياً' : 'All caught up';

  String chequeLabel(dynamic n, int total) {
    final base = n != null
        ? (ar ? 'شيك $n' : 'Cheque $n')
        : (ar ? 'شيك' : 'Cheque');
    if (total <= 0) return base;
    return ar ? '$base من $total' : '$base of $total';
  }

  String get sepOverdue => ' · ';
  String get sepProcessing => ar ? ' · الدفع ' : ' · payment ';
  String get sepDueToday => ar ? ' · مستحق ' : ' · due ';
  String get sepDueIn => ar ? ' · مستحق خلال ' : ' · due in ';
  String get overdueWord => ar ? 'متأخر' : 'overdue';
  String get processingWord => ar ? 'قيد المعالجة' : 'processing';

  /// Translates the raw EN token returned by `_daysUntil` ('—', 'today',
  /// 'overdue', '1 day', '$n days') into the Arabic equivalent. Pass-through
  /// for EN.
  String daysToDueLabel(String raw) {
    if (!ar) return raw;
    if (raw == 'today') return 'اليوم';
    if (raw == 'overdue') return 'متأخر';
    final match = RegExp(r'^(\d+) days?$').firstMatch(raw);
    if (match != null) {
      final n = int.parse(match.group(1)!);
      if (n == 1) return 'يوم واحد';
      if (n == 2) return 'يومان';
      return '$n أيام';
    }
    return raw;
  }

  String clearedCountLabel(int cleared, int total) =>
      ar ? '$cleared من $total تمت تسويتها' : '$cleared of $total cleared';

  String get viewCheques => ar ? 'عرض الشيكات' : 'View cheques';
  String get setReminder => ar ? 'تذكيرني' : 'Set reminder';
  String get remindersComingSoon =>
      ar ? 'ميزة التذكيرات قريباً' : 'Reminders coming soon';

  // Quick actions
  String get cheques => ar ? 'الشيكات' : 'Cheques';
  String get maintain => ar ? 'الصيانة' : 'Maintain';
  String get contract => ar ? 'العقد' : 'Contract';
  String get inbox => ar ? 'الرسائل' : 'Inbox';
  String get facilitiesTitle =>
      ar ? 'المرافق ومواقف السيارات' : 'Amenities & Parking';
  String get facilitiesSub => ar
      ? 'اطلب حجز المسبح أو القاعة أو موقف سيارة'
      : 'Request the pool, hall or a parking spot';
  String get visitors => ar ? 'الزوار' : 'Visitors';
  String get activityLoadFailed =>
      ar ? 'تعذر تحميل آخر التحديثات' : 'Failed to load recent activity';

  // Recent activity
  String get recentActivity => ar ? 'آخر التحديثات' : 'RECENT ACTIVITY';
  String get all => ar ? 'الكل' : 'ALL';
  String get noRecentActivity =>
      ar ? 'لا يوجد نشاط حديث بعد.' : 'No recent activity yet.';

  String get chequeCleared => ar ? 'تمت تسوية الشيك' : 'Cheque cleared';
  String get chequeReceived => ar ? 'تم استلام الشيك' : 'Cheque received';
  String get chequeDeposited => ar ? 'تم إيداع الشيك' : 'Cheque deposited';
  String get chequeBounced => ar ? 'ارتدّ الشيك' : 'Cheque bounced';
  String get upcomingPayment => ar ? 'دفعة قادمة' : 'Upcoming payment';
  String get paymentOverdue => ar ? 'دفعة متأخرة' : 'Payment overdue';
  String get paymentGeneric => ar ? 'دفعة' : 'Payment';
  String get wordCleared => ar ? 'تمت التسوية' : 'CLEARED';
  String get wordReceived => ar ? 'مستلم' : 'RECEIVED';
  String get wordDeposited => ar ? 'مودَع' : 'DEPOSITED';
  String get wordBounced => ar ? 'مرتجع' : 'BOUNCED';
  String get wordDue => ar ? 'مستحق' : 'DUE';
  String get wordOverdue => ar ? 'متأخر' : 'OVERDUE';
  String get wordLogged => ar ? 'مسجّل' : 'LOGGED';
}

class _ActivityShimmer extends StatelessWidget {
  const _ActivityShimmer();

  @override
  Widget build(BuildContext context) {
    return Column(
      children: List.generate(
        3,
        (_) => const Padding(
          padding: EdgeInsets.only(bottom: 8),
          child: ShimmerLoading(
            height: 56,
            width: double.infinity,
            borderRadius: 12,
          ),
        ),
      ),
    );
  }
}
