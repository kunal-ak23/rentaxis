import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/promotion_provider.dart';
import '../widgets/home_ads_strip.dart';

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

/// Renter Home — design screen 02.
///
/// Layout from top:
///   1. Top bar: wordmark, outlined bell with unread dot, ink initials avatar
///   2. Gold hero: next cheque, progress, View cheques / Set reminder
///   3. Penalty strip (only when there are open penalties)
///   4. Quick actions: 4-col grid
///   5. Promotions carousel (renders nothing when there are no ads)
///   6. Amenity promo
///   7. Recent activity list
///
/// The shell owns the Scaffold and the bottom bar, so this is a plain Column.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final leasesAsync = ref.watch(_myLeasesProvider);
    final paymentsAsync = ref.watch(_myPaymentsProvider);
    final auth = ref.watch(authProvider);
    final penalties = ref.watch(_openPenaltyCountProvider).valueOrNull ?? 0;

    Future<void> refresh() async {
      ref.invalidate(_myLeasesProvider);
      ref.invalidate(_myPaymentsProvider);
      ref.invalidate(_openPenaltyCountProvider);
      // Pull-to-refresh re-reads the day's promo slate too.
      ref.invalidate(homePromoFeedProvider);
      ref.read(notificationProvider.notifier).fetchUnreadCount();
      await ref.read(_myLeasesProvider.future);
    }

    return Column(
      children: [
        _TopBar(userName: auth.name),
        Expanded(
          child: RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 24),
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                _MaybeHero(
                  leasesAsync: leasesAsync,
                  paymentsAsync: paymentsAsync,
                ),
                if (penalties > 0) ...[
                  const SizedBox(height: MiftahSpacing.gap),
                  _PenaltyStrip(count: penalties),
                ],
                const SizedBox(height: MiftahSpacing.gap),
                const _QuickActions(),
                const SizedBox(height: MiftahSpacing.gap),
                const HomeAdsStrip(),
                const SizedBox(height: MiftahSpacing.gap),
                const _FacilitiesCard(),
                const SizedBox(height: 18),
                const _RecentActivityHeader(),
                const SizedBox(height: 11),
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
        ),
      ],
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

// ─── Top bar ────────────────────────────────────────────────────────────────

/// Home's own header: wordmark left, outlined bell (with an unread dot) and
/// an ink initials avatar right. Design screen 02 — there is no title bar.
class _TopBar extends ConsumerWidget {
  const _TopBar({required this.userName});

  final String? userName;

  String _initials() {
    final name = (userName ?? '').trim();
    if (name.isEmpty) return 'ME';
    final parts = name
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .toList();
    if (parts.isEmpty) return 'ME';
    if (parts.length == 1) {
      return parts.first.characters.take(2).toString().toUpperCase();
    }
    return (parts.first.characters.first + parts[1].characters.first)
        .toUpperCase();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unread = ref.watch(notificationProvider).unreadCount;

    return Container(
      color: Theme.of(context).colorScheme.surface,
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 14),
      child: Row(
        children: [
          // The Arabic wordmark fronts the dashboard in both locales — the
          // brand is Arabic-first. Tinted to onSurface (ink on the light
          // header, white in dark mode); the source asset is gold, and the
          // mockup itself darkens it on light surfaces.
          Image.asset(
            'assets/logo_mark.png',
            height: 44,
            fit: BoxFit.contain,
            color: Theme.of(context).colorScheme.onSurface,
          ),
          const Spacer(),
          _CircleButton(
            icon: Icons.notifications_outlined,
            dot: unread > 0,
            onTap: () => context.push('/notifications'),
          ),
          const SizedBox(width: 9),
          GestureDetector(
            onTap: () => context.push('/profile'),
            child: Container(
              width: 38,
              height: 38,
              alignment: Alignment.center,
              decoration: const BoxDecoration(
                shape: BoxShape.circle,
                color: MiftahColors.ink,
              ),
              child: Text(
                _initials(),
                style: MiftahType.button(
                  size: 13,
                  color: MiftahColors.brassLight,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({
    required this.icon,
    required this.onTap,
    this.dot = false,
  });

  final IconData icon;
  final VoidCallback onTap;
  final bool dot;

  @override
  Widget build(BuildContext context) {
    final surface = Theme.of(context).colorScheme.surface;
    return GestureDetector(
      onTap: onTap,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: MiftahColors.borderStrong),
            ),
            child: Icon(icon, size: 19, color: MiftahColors.ink),
          ),
          if (dot)
            PositionedDirectional(
              top: 7,
              end: 8,
              child: Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: MiftahColors.dangerBright,
                  border: Border.all(color: surface, width: 2),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ─── Hero money card ────────────────────────────────────────────────────────

class _MaybeHero extends StatelessWidget {
  const _MaybeHero({required this.leasesAsync, required this.paymentsAsync});

  final AsyncValue<List<dynamic>> leasesAsync;
  final AsyncValue<List<dynamic>> paymentsAsync;

  @override
  Widget build(BuildContext context) {
    return leasesAsync.when(
      loading: () => const _HeroSkeleton(),
      error: (_, _) => const _NoLeaseCard(),
      data: (leases) {
        final lease = _findActiveLease(leases);
        if (lease == null) return const _NoLeaseCard();
        final leaseId = lease['id']?.toString();
        final payments = paymentsAsync.valueOrNull ?? const [];
        return _HeroBalanceCard(
          lease: lease,
          next: _nextPaymentFor(payments, leaseId),
          clearedCount: _clearedCount(payments, leaseId),
          totalCount: _totalInstalmentsFor(payments, leaseId),
          clearedAmount: _clearedAmountFor(payments, leaseId),
          scheduleTotal: _scheduleTotalFor(payments, leaseId),
        );
      },
    );
  }
}

class _HeroSkeleton extends StatelessWidget {
  const _HeroSkeleton();

  @override
  Widget build(BuildContext context) =>
      const ShimmerLoading(height: 240, borderRadius: MiftahRadii.hero);
}

class _NoLeaseCard extends StatelessWidget {
  const _NoLeaseCard();

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    return MiftahCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          MiftahIconTile(
            icon: Icons.description_outlined,
            tone: MiftahTone.neutral,
          ),
          const SizedBox(height: 12),
          Text(
            l.noActiveLease,
            style: l.ar
                ? MiftahType.ar(size: 16, weight: FontWeight.w700)
                : MiftahType.cardTitle(),
          ),
          const SizedBox(height: 4),
          Text(
            l.noLeaseMessage,
            style: l.ar ? MiftahType.ar(size: 13) : MiftahType.body(),
          ),
        ],
      ),
    );
  }
}

/// The gold money card — the single gradient surface on Home.
class _HeroBalanceCard extends ConsumerWidget {
  const _HeroBalanceCard({
    required this.lease,
    required this.next,
    required this.clearedCount,
    required this.totalCount,
    required this.clearedAmount,
    required this.scheduleTotal,
  });

  final Map<String, dynamic> lease;
  final Map<String, dynamic>? next;
  final int clearedCount;
  final int totalCount;
  final double clearedAmount;
  final double scheduleTotal;

  /// Unchanged from the pre-redesign card — only the presentation moved.
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

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = _L(context.isAr);
    final auth = ref.watch(authProvider);

    final amount = (next?['amount'] ?? 0) as num;
    final dueRaw = next?['dueDate']?.toString();
    final daysToDue = _daysUntil(dueRaw);
    final status = next?['status']?.toString();
    final isOverdue = status == 'OVERDUE' || daysToDue == 'overdue';
    final isInFlight = status == 'ONLINE_PENDING';
    final totalAmount = scheduleTotal > 0
        ? scheduleTotal
        : ((lease['rentAmount'] ?? 0) as num).toDouble();

    final String pillLabel;
    if (next == null) {
      pillLabel = l.allClear;
    } else if (isOverdue) {
      pillLabel = l.overdue;
    } else if (isInFlight) {
      pillLabel = l.processing;
    } else if (daysToDue == 'today') {
      pillLabel = l.dueToday;
    } else {
      pillLabel = l.due(l.daysToDueLabel(daysToDue));
    }

    final property = [
      lease['propertyName']?.toString(),
      lease['unitNumber']?.toString() ?? lease['unitIdentifier']?.toString(),
    ].whereType<String>().where((s) => s.isNotEmpty).join(' · ');

    final money = NumberFormat('#,##0', l.ar ? 'ar' : 'en');
    final progress = totalCount == 0 ? 0.0 : clearedCount / totalCount;

    return MiftahGoldCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l.greetingFor(auth.name),
                      style: _onGold(14.5, FontWeight.w600, 0.8, l.ar),
                    ),
                    if (property.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text(
                        property,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _onGold(12, FontWeight.w600, 0.62, l.ar),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 10),
              // Status pill: ink on gold, per the mockup.
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 15,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: MiftahColors.ink,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  pillLabel,
                  style: l.ar
                      ? MiftahType.ar(
                          size: 12,
                          weight: FontWeight.w700,
                          color: MiftahColors.brassPale,
                        )
                      : MiftahType.button(
                          size: 12.5,
                          color: MiftahColors.brassPale,
                        ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            'AED ${money.format(amount)}',
            style: MiftahType.amount(size: 38, color: MiftahColors.ink),
          ),
          if (next != null) ...[
            const SizedBox(height: 2),
            Text(
              l.chequeLine(
                (next?['installmentNumber'] ?? 0) as num,
                totalCount,
                dueRaw,
              ),
              style: _onGold(13, FontWeight.w600, 0.72, l.ar),
            ),
          ],
          const SizedBox(height: 16),
          MiftahProgress(value: progress, onGold: true),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                l.clearedOf(clearedCount, totalCount),
                style: _onGold(11.5, FontWeight.w600, 0.72, l.ar),
              ),
              Text(
                '${money.format(clearedAmount)} / ${money.format(totalAmount)}',
                style: MiftahType.mono(
                  size: 11.5,
                  color: MiftahColors.ink.withValues(alpha: 0.72),
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              Expanded(
                child: _HeroButton(
                  label: l.viewCheques,
                  filled: true,
                  onTap: () => context.go('/payments'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _HeroButton(
                  label: l.setReminder,
                  filled: false,
                  onTap: () => ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text(l.remindersComingSoon)),
                  ),
                ),
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

class _HeroButton extends StatelessWidget {
  const _HeroButton({
    required this.label,
    required this.filled,
    required this.onTap,
  });

  final String label;
  final bool filled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 46,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: filled ? MiftahColors.ink : null,
          borderRadius: BorderRadius.circular(14),
          border: filled
              ? null
              : Border.all(
                  color: MiftahColors.ink.withValues(alpha: 0.3),
                  width: 1.5,
                ),
        ),
        child: Text(
          label,
          style: context.isAr
              ? MiftahType.ar(
                  size: 14,
                  weight: FontWeight.w700,
                  color: filled ? Colors.white : MiftahColors.ink,
                )
              : MiftahType.button(
                  size: 14,
                  color: filled ? Colors.white : MiftahColors.ink,
                ),
        ),
      ),
    );
  }
}

// ─── Penalty strip ──────────────────────────────────────────────────────────

class _PenaltyStrip extends StatelessWidget {
  const _PenaltyStrip({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    return GestureDetector(
      onTap: () => context.push('/penalties'),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: MiftahColors.warningTint,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            const Icon(
              Icons.gavel_rounded,
              size: 19,
              color: MiftahColors.brassDeep,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                l.openPenalties(count),
                style: l.ar
                    ? MiftahType.ar(size: 13)
                    : MiftahType.body(size: 13.5),
              ),
            ),
            Text(
              l.view,
              style: l.ar
                  ? MiftahType.ar(
                      size: 12,
                      weight: FontWeight.w700,
                      color: MiftahColors.brassDeep,
                    )
                  : MiftahType.button(
                      size: 12.5,
                      color: MiftahColors.brassDeep,
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Quick actions ──────────────────────────────────────────────────────────

class _QuickActions extends StatelessWidget {
  const _QuickActions();

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final items = [
      (Icons.receipt_long_rounded, l.cheques, '/payments'),
      (Icons.handyman_rounded, l.maintain, '/tickets'),
      (Icons.qr_code_2_rounded, l.visitors, '/gatepass'),
      (Icons.description_rounded, l.contract, '/profile'),
    ];
    return Row(
      children: [
        for (var i = 0; i < items.length; i++) ...[
          if (i > 0) const SizedBox(width: 10),
          Expanded(
            child: _QuickAction(
              icon: items[i].$1,
              label: items[i].$2,
              route: items[i].$3,
            ),
          ),
        ],
      ],
    );
  }
}

class _QuickAction extends StatelessWidget {
  const _QuickAction({
    required this.icon,
    required this.label,
    required this.route,
  });

  final IconData icon;
  final String label;
  final String route;

  @override
  Widget build(BuildContext context) {
    return MiftahCard(
      padding: const EdgeInsets.fromLTRB(6, 13, 6, 11),
      radius: MiftahRadii.tile,
      onTap: () => context.push(route),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: const Color(0xFFF4F1FC),
              borderRadius: BorderRadius.circular(11),
            ),
            child: Icon(icon, size: 20, color: MiftahColors.brassDeep),
          ),
          const SizedBox(height: 8),
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              label,
              maxLines: 1,
              style: context.isAr
                  ? MiftahType.ar(
                      size: 11,
                      weight: FontWeight.w600,
                      color: MiftahColors.textPrimary,
                    )
                  : MiftahType.meta(
                      color: const Color(0xFF3A3448),
                    ).copyWith(fontSize: 11, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Amenity promo ──────────────────────────────────────────────────────────

class _FacilitiesCard extends StatelessWidget {
  const _FacilitiesCard();

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    return GestureDetector(
      onTap: () => context.push('/facilities'),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(18),
        child: Container(
          height: 110,
          color: const Color(0xFFE8F4FB),
          child: Row(
            children: [
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        l.facilitiesTitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: l.ar
                            ? MiftahType.ar(
                                size: 16,
                                weight: FontWeight.w700,
                                color: const Color(0xFF134B69),
                              )
                            : MiftahType.cardTitle(
                                color: const Color(0xFF134B69),
                              ).copyWith(fontSize: 20),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        l.facilitiesSub,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: l.ar
                            ? MiftahType.ar(
                                size: 11.5,
                                color: const Color(0xFF4A7C95),
                              )
                            : MiftahType.meta(color: const Color(0xFF4A7C95)),
                      ),
                    ],
                  ),
                ),
              ),
              Container(
                width: 130,
                alignment: Alignment.center,
                color: const Color(0xFFD5E9F5),
                child: const Icon(
                  Icons.pool_rounded,
                  size: 32,
                  color: Color(0xFF3E6E88),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─── Recent activity ────────────────────────────────────────────────────────

class _RecentActivityHeader extends StatelessWidget {
  const _RecentActivityHeader();

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          l.recentActivityTitle,
          style: l.ar
              ? MiftahType.ar(size: 18, weight: FontWeight.w700)
              : MiftahType.title().copyWith(fontSize: 18),
        ),
        GestureDetector(
          onTap: () => context.go('/payments'),
          child: Text(
            l.seeAll,
            style: l.ar
                ? MiftahType.ar(
                    size: 12,
                    weight: FontWeight.w700,
                    color: MiftahColors.brassDeep,
                  )
                : MiftahType.button(size: 12.5, color: MiftahColors.brassDeep),
          ),
        ),
      ],
    );
  }
}

class _RecentActivity extends StatelessWidget {
  const _RecentActivity({required this.payments});

  final List<dynamic> payments;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final rows = payments.whereType<Map<String, dynamic>>().toList()
      ..sort((a, b) {
        final ad = a['statusChangedAt']?.toString() ?? '';
        final bd = b['statusChangedAt']?.toString() ?? '';
        return bd.compareTo(ad);
      });
    final recent = rows.take(3).toList();

    if (recent.isEmpty) {
      return MiftahEmptyState(
        icon: Icons.history_rounded,
        title: l.noRecentActivity,
        subtitle: l.noRecentActivitySub,
      );
    }

    return MiftahCard(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      radius: 18,
      child: Column(
        children: [
          for (var i = 0; i < recent.length; i++)
            _ActivityRow(payment: recent[i], isLast: i == recent.length - 1),
        ],
      ),
    );
  }
}

class _ActivityRow extends StatelessWidget {
  const _ActivityRow({required this.payment, required this.isLast});

  final Map<String, dynamic> payment;
  final bool isLast;

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final status = payment['status']?.toString() ?? '';
    final amount = (payment['amount'] ?? 0) as num;

    final ({IconData icon, MiftahTone tone, String title}) spec =
        switch (status) {
          'CLEARED' => (
            icon: Icons.check_circle_rounded,
            tone: MiftahTone.success,
            title: l.chequeCleared,
          ),
          'DEPOSITED' => (
            icon: Icons.account_balance_rounded,
            tone: MiftahTone.info,
            title: l.chequeDeposited,
          ),
          'BOUNCED' => (
            icon: Icons.error_rounded,
            tone: MiftahTone.danger,
            title: l.chequeBounced,
          ),
          'OVERDUE' => (
            icon: Icons.schedule_rounded,
            tone: MiftahTone.danger,
            title: l.paymentOverdue,
          ),
          'PENDING' => (
            icon: Icons.receipt_long_rounded,
            tone: MiftahTone.brass,
            title: l.upcomingPayment,
          ),
          _ => (
            icon: Icons.receipt_long_rounded,
            tone: MiftahTone.brass,
            title: l.paymentGeneric,
          ),
        };

    final money = NumberFormat('#,##0', l.ar ? 'ar' : 'en');
    final when =
        payment['statusChangedAt']?.toString() ??
        payment['dueDate']?.toString();

    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12),
      decoration: BoxDecoration(
        border: isLast
            ? null
            : const Border(bottom: BorderSide(color: Color(0xFFF2EFF8))),
      ),
      child: Row(
        children: [
          MiftahIconTile(icon: spec.icon, tone: spec.tone, size: 36),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  spec.title,
                  style: l.ar
                      ? MiftahType.ar(size: 14, weight: FontWeight.w700)
                      : MiftahType.cardTitle().copyWith(fontSize: 14),
                ),
                const SizedBox(height: 2),
                Text(
                  l.instalmentLine(
                    (payment['installmentNumber'] ?? 0) as num,
                    when,
                  ),
                  style: l.ar ? MiftahType.ar(size: 11.5) : MiftahType.meta(),
                ),
              ],
            ),
          ),
          Text(
            'AED ${money.format(amount)}',
            style: MiftahType.cardTitle(
              color: status == 'CLEARED'
                  ? MiftahColors.success
                  : MiftahColors.textPrimary,
            ).copyWith(fontSize: 13.5),
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
  // ── Added by the redesign (design screen 02) ──
  String greetingFor(String? name) {
    final n = (name ?? '').trim().split(RegExp(r'\s+')).first;
    if (n.isEmpty) return ar ? 'أهلاً!' : 'Hi there!';
    return ar ? 'أهلاً، $n!' : 'Hi, $n!';
  }

  String chequeLine(num n, int total, String? dueIso) {
    final due = _shortDate(dueIso);
    final head = ar ? 'شيك $n من $total' : 'Cheque $n of $total';
    if (due == null) return head;
    return ar ? '$head · مستحق $due' : '$head · due $due';
  }

  String clearedOf(int cleared, int total) =>
      ar ? '$cleared من $total مسددة' : '$cleared of $total cleared';

  String openPenalties(int count) {
    if (ar) return '$count غرامة مفتوحة';
    return '$count open ${count == 1 ? 'penalty' : 'penalties'}';
  }

  String get view => ar ? 'عرض' : 'View';
  String get noRecentActivitySub => ar
      ? 'ستظهر هنا الشيكات والطلبات والزيارات.'
      : 'Cheques, requests and gate activity will show up here.';
  String get recentActivityTitle => ar ? 'آخر التحديثات' : 'Recent activity';
  String get seeAll => ar ? 'عرض الكل' : 'See all';

  String instalmentLine(num n, String? whenIso) {
    final when = _shortDate(whenIso);
    final head = ar ? 'قسط $n' : 'Instalment $n';
    return when == null ? head : '$head · $when';
  }

  String? _shortDate(String? iso) {
    if (iso == null || iso.isEmpty) return null;
    try {
      return DateFormat('d MMM', ar ? 'ar' : 'en').format(DateTime.parse(iso));
    } catch (_) {
      return null;
    }
  }
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
