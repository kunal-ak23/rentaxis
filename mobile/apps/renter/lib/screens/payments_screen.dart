import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
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

/// Renter Cheques screen — redesigned to the handoff
/// (mobile-renter.jsx · `RenterCheques`).
///
/// Layout:
///   1. Header — tenancy year eyebrow + serif "Your cheques" title
///   2. Progress card — sand-tinted "Total paid this lease" with
///      mono current/total and a gold gradient progress bar
///   3. List — every cheque shown with a coloured accent bar
///      (green = cleared, gold = pending), serif amount, due date,
///      cheque number, and a status pill on the right.
///
/// Tap a PENDING row → opens the Pay Rent flow with that payment
/// pre-selected (existing route /payments/pay; payment-screen
/// handles which one to focus).
class PaymentsScreen extends ConsumerWidget {
  const PaymentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final paymentsAsync = ref.watch(_myPaymentsProvider);
    final leasesAsync = ref.watch(_myLeasesProvider);

    Future<void> refresh() async {
      ref.invalidate(_myPaymentsProvider);
      ref.invalidate(_myLeasesProvider);
      await ref.read(_myPaymentsProvider.future);
    }

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: RefreshIndicator(
          color: AppColors.primary,
          onRefresh: refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 130),
            physics: const AlwaysScrollableScrollPhysics(),
            children: [
              _Header(leasesAsync: leasesAsync),
              const SizedBox(height: 16),
              _ProgressCard(paymentsAsync: paymentsAsync),
              const SizedBox(height: 18),
              paymentsAsync.when(
                loading: () => const _ChequesShimmer(),
                error: (e, _) => ErrorState(
                  message: 'Failed to load cheques',
                  onRetry: refresh,
                ),
                data: (payments) => _ChequesList(payments: payments),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─── Header ─────────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  final AsyncValue<List<dynamic>> leasesAsync;
  const _Header({required this.leasesAsync});

  String _tenancyLabel(List<dynamic> leases) {
    // Filter to typed maps once so the orElse fallback can't crash on a
    // non-Map first entry. (Reviewer nit #6.)
    final typed = leases.whereType<Map<String, dynamic>>().toList();
    final active = typed.firstWhere(
      (l) => l['status'] == 'ACTIVE' || l['status'] == 'PENDING_SIGNATURE',
      orElse: () => typed.isEmpty ? <String, dynamic>{} : typed.first,
    );
    final start = active['startDate']?.toString();
    final end = active['endDate']?.toString();
    if (start == null || end == null) return 'Your tenancy';
    final s = DateTime.tryParse(start);
    final e = DateTime.tryParse(end);
    if (s == null || e == null) return 'Your tenancy';
    if (s.year == e.year) return 'Tenancy ${s.year}';
    return 'Tenancy ${s.year}–${e.year}';
  }

  @override
  Widget build(BuildContext context) {
    final eyebrow = leasesAsync.when(
      data: _tenancyLabel,
      loading: () => 'Your tenancy',
      error: (_, _) => 'Your tenancy',
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          eyebrow,
          style: GoogleFonts.inter(
              fontSize: 12, color: AppColors.textMuted),
        ),
        const SizedBox(height: 4),
        Text(
          'Your cheques',
          style: GoogleFonts.sourceSerif4(
            fontSize: 22,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.4,
            color: AppColors.textPrimary,
          ),
        ),
      ],
    );
  }
}

// ─── Progress card ──────────────────────────────────────────────────────────

class _ProgressCard extends StatelessWidget {
  final AsyncValue<List<dynamic>> paymentsAsync;
  const _ProgressCard({required this.paymentsAsync});

  @override
  Widget build(BuildContext context) {
    return paymentsAsync.when(
      loading: () => const ShimmerLoading(
          height: 110, width: double.infinity, borderRadius: 12),
      error: (_, _) => const SizedBox.shrink(),
      data: (payments) {
        final visible = payments
            .whereType<Map<String, dynamic>>()
            .where((p) => p['status'] != 'CANCELLED' && p['status'] != 'REPLACED')
            .toList();
        final total = visible.fold<double>(
            0, (s, p) => s + ((p['amount'] ?? 0) as num).toDouble());
        final paid = visible
            .where((p) => p['status'] == 'CLEARED')
            .fold<double>(
                0, (s, p) => s + ((p['amount'] ?? 0) as num).toDouble());
        final ratio = total > 0 ? (paid / total).clamp(0.0, 1.0) : 0.0;
        // Floor (not round) so "100% complete" only shows when paid==total.
        // Otherwise 99.6% would round up and mislead the user.
        final pct = (ratio * 100).floor();
        return Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: AppColors.surface2,
            border: Border.all(color: AppColors.border),
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
                    'Total paid this lease',
                    style: GoogleFonts.inter(
                        fontSize: 12, color: AppColors.textMuted),
                  ),
                  Text(
                    '$pct% complete',
                    style: GoogleFonts.inter(
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
                  style: GoogleFonts.sourceSerif4(
                    fontSize: 28,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textPrimary,
                  ),
                  children: [
                    TextSpan(text: 'AED ${NumberFormat('#,##0').format(paid)}'),
                    TextSpan(
                      text: '  / ${NumberFormat('#,##0').format(total)}',
                      style: GoogleFonts.sourceSerif4(
                        fontSize: 14,
                        color: AppColors.textMuted,
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
                      Container(color: AppColors.surface),
                      FractionallySizedBox(
                        widthFactor: ratio,
                        child: Container(
                          decoration: const BoxDecoration(
                            gradient: LinearGradient(
                              colors: [
                                AppColors.accent,
                                AppColors.accentDark,
                              ],
                            ),
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

// ─── Cheques list ───────────────────────────────────────────────────────────

class _ChequesList extends StatelessWidget {
  final List<dynamic> payments;
  const _ChequesList({required this.payments});

  @override
  Widget build(BuildContext context) {
    final list = payments
        .whereType<Map<String, dynamic>>()
        .where((p) => p['status'] != 'CANCELLED' && p['status'] != 'REPLACED')
        .toList()
      ..sort((a, b) {
        final ai = (a['installmentNumber'] ?? 0) as num;
        final bi = (b['installmentNumber'] ?? 0) as num;
        return ai.compareTo(bi);
      });

    if (list.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(20),
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
                'No cheques yet — they will appear here once your lease is set up.',
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

    final total = list.length;
    return Column(
      children: list
          .map((p) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _ChequeCard(payment: p, totalCount: total),
              ))
          .toList(),
    );
  }
}

class _ChequeCard extends StatelessWidget {
  final Map<String, dynamic> payment;
  final int totalCount;
  const _ChequeCard({required this.payment, required this.totalCount});

  @override
  Widget build(BuildContext context) {
    final status = payment['status']?.toString() ?? 'PENDING';
    final amount = (payment['amount'] ?? 0) as num;
    final n = payment['installmentNumber'];
    final dueRaw = payment['dueDate']?.toString();
    final cheque = payment['chequeNumber']?.toString();
    final dueLabel = _formatDate(dueRaw);

    final accent = _accentFor(status);

    return InkWell(
      onTap: () {
        // PENDING / OVERDUE → open Pay Rent for this payment with the
        // tapped paymentId pre-selected. Other statuses are read-only.
        if (status == 'PENDING' || status == 'OVERDUE') {
          final id = payment['id']?.toString();
          context.push(
              id != null ? '/payments/pay?paymentId=$id' : '/payments/pay');
        }
      },
      borderRadius: BorderRadius.circular(14),
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.border),
          borderRadius: BorderRadius.circular(14),
        ),
        child: IntrinsicHeight(
          child: Row(
            children: [
              Container(
                width: 3,
                margin: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: accent,
                  borderRadius: const BorderRadius.only(
                    topRight: Radius.circular(2),
                    bottomRight: Radius.circular(2),
                  ),
                ),
              ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(11, 14, 14, 14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              n != null
                                  ? 'CHEQUE $n OF $totalCount'
                                  : 'CHEQUE',
                              style: GoogleFonts.inter(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                                letterSpacing: 0.6,
                                color: AppColors.textMuted,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'AED ${NumberFormat('#,##0').format(amount)}',
                              style: GoogleFonts.sourceSerif4(
                                fontSize: 22,
                                fontWeight: FontWeight.w600,
                                letterSpacing: -0.3,
                                color: AppColors.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              [
                                if (dueLabel.isNotEmpty) 'Due $dueLabel',
                                if (cheque != null && cheque.isNotEmpty)
                                  cheque,
                              ].join(' · '),
                              style: GoogleFonts.inter(
                                fontSize: 11.5,
                                color: AppColors.textMuted,
                              ),
                            ),
                          ],
                        ),
                      ),
                      _StatusPill(status: status),
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

  Color _accentFor(String status) {
    switch (status) {
      case 'CLEARED':
        return AppColors.success;
      case 'COLLECTED':
      case 'DEPOSITED':
        return AppColors.info;
      case 'BOUNCED':
      case 'OVERDUE':
        return AppColors.danger;
      case 'PENDING':
      default:
        return AppColors.accent;
    }
  }

  String _formatDate(String? iso) {
    if (iso == null || iso.isEmpty) return '';
    final dt = DateTime.tryParse(iso);
    if (dt == null) return '';
    return DateFormat('d MMM yyyy').format(dt);
  }
}

class _StatusPill extends StatelessWidget {
  final String status;
  const _StatusPill({required this.status});

  ({Color fg, Color bg, String label}) _meta() {
    switch (status) {
      case 'CLEARED':
        return (
          fg: AppColors.success,
          bg: AppColors.successLight,
          label: 'cleared'
        );
      case 'COLLECTED':
      case 'DEPOSITED':
        return (
          fg: AppColors.info,
          bg: const Color(0xFFD6EBEB),
          label: status == 'COLLECTED' ? 'collected' : 'deposited'
        );
      case 'BOUNCED':
        return (
          fg: AppColors.danger,
          bg: AppColors.dangerLight,
          label: 'bounced'
        );
      case 'OVERDUE':
        return (
          fg: AppColors.danger,
          bg: AppColors.dangerLight,
          label: 'overdue'
        );
      case 'PENDING':
      default:
        return (
          fg: AppColors.accentDark,
          bg: AppColors.accentLight,
          label: 'pending'
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = _meta();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: m.bg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: m.fg,
            ),
          ),
          const SizedBox(width: 6),
          Text(
            m.label,
            style: GoogleFonts.inter(
              fontSize: 10.5,
              fontWeight: FontWeight.w600,
              color: m.fg,
              letterSpacing: 0.2,
            ),
          ),
        ],
      ),
    );
  }
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
