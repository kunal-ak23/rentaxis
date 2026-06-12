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
  final list =
      await ref.watch(_homePenaltyServiceProvider).listPenalties(status: 'open', size: 50);
  return list.length;
});

/// Renter Home — redesigned to match the handoff (mobile-renter.jsx ·
/// `RenterHome`). Calm, billing-focused subscription manager feel.
///
/// Layout from top:
///   1. Header: "Tenancy at" eyebrow + property/unit + avatar
///   2. Hero balance card: navy gradient with gold radial, amount due,
///      mini progress (cleared / total), Pay-now + Set-reminder buttons
///   3. Quick actions: 4-col grid (Cheques · Maintain · Contract · Contact)
///   4. Recent activity: list of recent payments
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
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
      backgroundColor: AppColors.background,
      body: RefreshIndicator(
        color: AppColors.primary,
        onRefresh: refresh,
        child: ListView(
          padding: EdgeInsets.fromLTRB(20, 12, 20, AppInsets.bottomNav(context)),
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            leasesAsync.when(
              data: (leases) => _Header(
                lease: _findActiveLease(leases),
                userName: auth.name,
              ),
              loading: () => _Header(lease: null, userName: auth.name),
              error: (_, _) => _Header(lease: null, userName: auth.name),
            ),
            const SizedBox(height: 16),
            _MaybeHero(leasesAsync: leasesAsync, paymentsAsync: paymentsAsync),
            const SizedBox(height: 18),
            _QuickActions(
              penaltyBadge: ref.watch(_openPenaltyCountProvider).valueOrNull,
            ),
            const SizedBox(height: 22),
            _RecentActivityHeader(),
            const SizedBox(height: 10),
            paymentsAsync.when(
              loading: () => const _ActivityShimmer(),
              error: (_, _) => const SizedBox.shrink(),
              data: (payments) => _RecentActivity(payments: payments),
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

Map<String, dynamic>? _nextPaymentFor(
    List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return null;
  // OVERDUE takes priority, then PENDING, then ONLINE_PENDING (checkout in
  // flight) — mirrors the web renter portal's nextPayment selection.
  const statusPriority = {'OVERDUE': 0, 'PENDING': 1, 'ONLINE_PENDING': 2};
  final mine = payments
      .whereType<Map<String, dynamic>>()
      .where((p) =>
          p['leaseId'] == leaseId && statusPriority.containsKey(p['status']))
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
      .fold<double>(
          0, (s, p) => s + ((p['amount'] ?? 0) as num).toDouble());
}

/// Sum of `amount` over the entire lease schedule. Matches the
/// denominator the renter expects to see ("X / Y") so cleared can
/// converge on total at full clearance, regardless of rentAmount being
/// rent-only vs schedule-with-VAT.
double _scheduleTotalFor(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return 0;
  return payments
      .whereType<Map<String, dynamic>>()
      .where((p) =>
          p['leaseId'] == leaseId &&
          p['status'] != 'CANCELLED' &&
          p['status'] != 'REPLACED')
      .fold<double>(
          0, (s, p) => s + ((p['amount'] ?? 0) as num).toDouble());
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
    return (parts[0].substring(0, 1) + parts[1].substring(0, 1))
        .toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    final propertyName = lease?['propertyName']?.toString();
    // Backend's LeaseDTO uses `unitIdentifier`. We fall back to
    // `unitNumber` defensively in case other endpoints emit it.
    final unit = (lease?['unitIdentifier'] ?? lease?['unitNumber'])
        ?.toString();
    final tenancyLabel = (propertyName != null && unit != null && unit.isNotEmpty)
        ? '$propertyName · $unit'
        : (propertyName ?? 'No active lease');
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Tenancy at',
                style: GoogleFonts.inter(
                    fontSize: 12, color: AppColors.textMuted),
              ),
              const SizedBox(height: 2),
              Text(
                tenancyLabel,
                style: GoogleFonts.inter(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Container(
          width: 34,
          height: 34,
          alignment: Alignment.center,
          decoration: const BoxDecoration(
            shape: BoxShape.circle,
            color: AppColors.accent,
          ),
          child: Text(
            _initials(),
            style: GoogleFonts.inter(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.primary,
            ),
          ),
        ),
      ],
    );
  }
}

// ─── Hero balance card ──────────────────────────────────────────────────────

class _MaybeHero extends StatelessWidget {
  final AsyncValue<List<dynamic>> leasesAsync;
  final AsyncValue<List<dynamic>> paymentsAsync;
  const _MaybeHero({
    required this.leasesAsync,
    required this.paymentsAsync,
  });

  @override
  Widget build(BuildContext context) {
    if (leasesAsync.isLoading || paymentsAsync.isLoading) {
      return const ShimmerLoading(
          height: 220, width: double.infinity, borderRadius: 18);
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
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          const Icon(Icons.info_outline,
              color: AppColors.textMuted, size: 18),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              "You don't have an active lease yet. Once your tenancy starts you'll see your balance here.",
              style: GoogleFonts.inter(
                fontSize: 12.5,
                color: AppColors.textSecondary,
              ),
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
    final totalAmount =
        scheduleTotal > 0 ? scheduleTotal : ((lease['rentAmount'] ?? 0) as num).toDouble();

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.primary, AppColors.primaryLight],
        ),
      ),
      child: Stack(
        children: [
          Positioned(
            top: -40,
            right: -40,
            child: Container(
              width: 140,
              height: 140,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [
                    AppColors.accent.withValues(alpha: 0.35),
                    Colors.transparent,
                  ],
                  stops: const [0, 0.7],
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  next == null
                      ? 'NO PAYMENTS DUE'
                      : isOverdue
                          ? 'PAYMENT OVERDUE'
                          : isInFlight
                              ? 'PAYMENT IN PROGRESS'
                              : 'NEXT PAYMENT DUE',
                  style: GoogleFonts.inter(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.8,
                    color: Colors.white.withValues(alpha: 0.6),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  next == null ? 'All caught up' : _formatAmount(amount),
                  style: GoogleFonts.sourceSerif4(
                    fontSize: 36,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.7,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 4),
                if (next != null)
                  RichText(
                    text: TextSpan(
                      style: GoogleFonts.inter(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.7),
                      ),
                      children: [
                        TextSpan(
                          text: installmentNumber != null
                              ? 'Cheque $installmentNumber'
                              : 'Cheque',
                        ),
                        if (installmentTotal > 0)
                          TextSpan(text: ' of $installmentTotal'),
                        TextSpan(
                            text: isOverdue
                                ? ' · '
                                : isInFlight
                                    ? ' · payment '
                                    : daysToDue == 'today'
                                        ? ' · due '
                                        : ' · due in '),
                        TextSpan(
                          text: isOverdue
                              ? 'overdue'
                              : isInFlight
                                  ? 'processing'
                                  : daysToDue,
                          style: GoogleFonts.inter(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: isOverdue
                                ? const Color(0xFFFCA5A5)
                                : AppColors.gold400,
                          ),
                        ),
                      ],
                    ),
                  ),
                const SizedBox(height: 16),
                Container(
                  height: 1,
                  color: Colors.white.withValues(alpha: 0.1),
                ),
                const SizedBox(height: 12),
                if (totalCount > 0) ...[
                  _Progress(cleared: clearedCount, total: totalCount),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        '$clearedCount of $totalCount cleared',
                        style: GoogleFonts.inter(
                          fontSize: 11.5,
                          color: Colors.white.withValues(alpha: 0.7),
                        ),
                      ),
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
                      child: _PrimaryButton(
                        label: 'View cheques',
                        onTap: () => context.push('/payments'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    _GhostButton(
                      label: 'Set reminder',
                      onTap: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Reminders coming soon'),
                          ),
                        );
                      },
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
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
      return ClipRRect(
        borderRadius: BorderRadius.circular(2),
        child: LinearProgressIndicator(
          value: ratio,
          minHeight: 4,
          backgroundColor: Colors.white.withValues(alpha: 0.15),
          valueColor: const AlwaysStoppedAnimation(AppColors.accent),
        ),
      );
    }
    return Row(
      children: List.generate(total, (i) {
        final filled = i < cleared;
        return Expanded(
          child: Container(
            height: 4,
            margin: EdgeInsets.only(right: i == total - 1 ? 0 : 6),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(2),
              color: filled
                  ? AppColors.accent
                  : Colors.white.withValues(alpha: 0.15),
            ),
          ),
        );
      }),
    );
  }
}

class _PrimaryButton extends StatelessWidget {
  final String label;
  final VoidCallback? onTap;
  const _PrimaryButton({required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 44,
      child: ElevatedButton(
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.accent,
          disabledBackgroundColor: AppColors.accent.withValues(alpha: 0.3),
          foregroundColor: AppColors.primary,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          padding: EdgeInsets.zero,
        ),
        onPressed: onTap,
        child: Text(
          label,
          style: GoogleFonts.inter(
              fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}

class _GhostButton extends StatelessWidget {
  final String label;
  final VoidCallback onTap;
  const _GhostButton({required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 44,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          side: BorderSide(color: Colors.white.withValues(alpha: 0.3)),
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        onPressed: onTap,
        child: Text(
          label,
          style: GoogleFonts.inter(
              fontSize: 13, fontWeight: FontWeight.w500),
        ),
      ),
    );
  }
}

// ─── Quick actions ──────────────────────────────────────────────────────────

class _QuickActions extends StatelessWidget {
  final int? penaltyBadge;
  const _QuickActions({this.penaltyBadge});

  @override
  Widget build(BuildContext context) {
    final actions = [
      (
        icon: Icons.receipt_long_outlined,
        label: 'Cheques',
        route: '/payments',
        badge: (penaltyBadge != null && penaltyBadge! > 0) ? penaltyBadge : null
      ),
      (
        icon: Icons.build_outlined,
        label: 'Maintain',
        route: '/tickets',
        badge: null
      ),
      (
        icon: Icons.description_outlined,
        label: 'Contract',
        route: '/profile',
        badge: null
      ),
      (
        icon: Icons.contact_mail_outlined,
        label: 'Inbox',
        route: '/notifications',
        badge: null
      ),
    ];
    return GridView.count(
      crossAxisCount: 4,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: 8,
      mainAxisSpacing: 8,
      childAspectRatio: 0.95,
      children: actions
          .map((a) => InkWell(
                borderRadius: BorderRadius.circular(14),
                onTap: () => context.go(a.route),
                child: Container(
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    border: Border.all(color: AppColors.border),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Container(
                            width: 32,
                            height: 32,
                            decoration: BoxDecoration(
                              color: AppColors.surface2,
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Icon(a.icon,
                                size: 16, color: AppColors.primary),
                          ),
                          if (a.badge != null)
                            Positioned(
                              top: -4,
                              right: -4,
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 4),
                                constraints: const BoxConstraints(
                                    minWidth: 16, minHeight: 16),
                                decoration: BoxDecoration(
                                  color: AppColors.danger,
                                  borderRadius: BorderRadius.circular(999),
                                  border: Border.all(
                                      color: AppColors.surface, width: 2),
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
                      const SizedBox(height: 6),
                      Text(
                        a.label,
                        style: GoogleFonts.inter(
                          fontSize: 11,
                          fontWeight: FontWeight.w500,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    ],
                  ),
                ),
              ))
          .toList(),
    );
  }
}

// ─── Recent activity ────────────────────────────────────────────────────────

class _RecentActivityHeader extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        Text(
          'Recent activity',
          style: GoogleFonts.inter(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: AppColors.textPrimary,
          ),
        ),
        InkWell(
          onTap: () => context.go('/payments'),
          child: Text(
            'See all',
            style: GoogleFonts.inter(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: AppColors.accent,
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
    final visible = payments
        .whereType<Map<String, dynamic>>()
        .where((p) => p['status'] != 'CANCELLED' && p['status'] != 'REPLACED')
        .toList()
      ..sort((a, b) {
        DateTime parse(String? s) =>
            s == null ? DateTime(1900) : DateTime.tryParse(s) ?? DateTime(1900);
        final ad = parse(a['statusChangedAt']?.toString() ??
            a['dueDate']?.toString());
        final bd = parse(b['statusChangedAt']?.toString() ??
            b['dueDate']?.toString());
        return bd.compareTo(ad);
      });
    final top = visible.take(5).toList();
    if (top.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            const Icon(Icons.inbox_outlined, color: AppColors.textMuted),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                'No recent activity yet.',
                style: GoogleFonts.inter(
                    fontSize: 12.5, color: AppColors.textSecondary),
              ),
            ),
          ],
        ),
      );
    }
    return Column(
      children: top
          .map((p) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _ActivityRow(payment: p),
              ))
          .toList(),
    );
  }
}

class _ActivityRow extends StatelessWidget {
  final Map<String, dynamic> payment;
  const _ActivityRow({required this.payment});

  ({IconData icon, Color fg, Color bg, String title}) _meta(String status) {
    switch (status) {
      case 'CLEARED':
        return (
          icon: Icons.check,
          fg: AppColors.success,
          bg: AppColors.successLight,
          title: 'Cheque cleared',
        );
      case 'COLLECTED':
        return (
          icon: Icons.receipt_long_outlined,
          fg: AppColors.info,
          bg: const Color(0xFFD6EBEB),
          title: 'Cheque received',
        );
      case 'DEPOSITED':
        return (
          icon: Icons.savings_outlined,
          fg: AppColors.info,
          bg: const Color(0xFFD6EBEB),
          title: 'Cheque deposited',
        );
      case 'BOUNCED':
        return (
          icon: Icons.error_outline,
          fg: AppColors.danger,
          bg: AppColors.dangerLight,
          title: 'Cheque bounced',
        );
      case 'PENDING':
      case 'OVERDUE':
        return (
          icon: Icons.schedule,
          fg: AppColors.accentDark,
          bg: AppColors.accentLight,
          title: status == 'OVERDUE' ? 'Payment overdue' : 'Upcoming payment',
        );
      default:
        return (
          icon: Icons.receipt_outlined,
          fg: AppColors.textMuted,
          bg: AppColors.surface2,
          title: 'Payment',
        );
    }
  }

  String _formatDate(dynamic raw) {
    final s = raw?.toString();
    if (s == null || s.isEmpty) return '';
    final dt = DateTime.tryParse(s);
    if (dt == null) return '';
    return DateFormat('d MMM').format(dt);
  }

  @override
  Widget build(BuildContext context) {
    final status = payment['status']?.toString() ?? 'PENDING';
    final m = _meta(status);
    final amount = (payment['amount'] ?? 0) as num;
    final installment = payment['installmentNumber'];
    final bank = payment['bankName']?.toString();
    final dateLabel = _formatDate(
        payment['statusChangedAt'] ?? payment['dueDate']);
    final subtitle = [
      if (bank != null && bank.isNotEmpty) bank,
      if (dateLabel.isNotEmpty) dateLabel,
    ].join(' · ');
    final title = installment != null ? '${m.title} · #$installment' : m.title;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: m.bg,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(m.icon, size: 16, color: m.fg),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: GoogleFonts.inter(
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                    color: AppColors.textPrimary,
                  ),
                ),
                if (subtitle.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: GoogleFonts.inter(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (amount > 0)
            Text(
              'AED ${NumberFormat('#,##0').format(amount)}',
              style: GoogleFonts.jetBrainsMono(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
              ),
            ),
        ],
      ),
    );
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
