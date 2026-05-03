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
final penaltiesProvider =
    FutureProvider.family.autoDispose<List<Map<String, dynamic>>, String>(
  (ref, status) {
    final service = ref.watch(_penaltyServiceProvider);
    return service.listPenalties(status: status);
  },
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

  static const _tabs = ['Open', 'Cleared'];
  static const _statuses = ['open', 'cleared'];

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: _tabs.length, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppColors.navyDark,
        title: Text(
          'Penalties',
          style: GoogleFonts.cinzel(
            color: Colors.white,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: AppColors.accent,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white54,
          labelStyle: GoogleFonts.josefinSans(
            fontSize: 13,
            fontWeight: FontWeight.w600,
          ),
          unselectedLabelStyle: GoogleFonts.josefinSans(
            fontSize: 13,
            fontWeight: FontWeight.w400,
          ),
          tabs: _tabs.map((t) => Tab(text: t)).toList(),
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: List.generate(_tabs.length, (i) {
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

  const _PenaltyTabBody({
    required this.status,
    required this.showHowToPay,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final penaltiesAsync = ref.watch(penaltiesProvider(status));

    return penaltiesAsync.when(
      data: (penalties) => RefreshIndicator(
        color: AppColors.primary,
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
                        ? 'No Open Penalties'
                        : 'No Cleared Penalties',
                    subtitle: status == 'open'
                        ? 'You have no outstanding penalties'
                        : 'No penalties have been cleared yet',
                  ),
                ],
              )
            : ListView(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 120),
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
        message: 'Failed to load penalties',
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

  Color _reasonColor(String reason) {
    switch (reason.toUpperCase()) {
      case 'BOUNCE':
        return AppColors.danger;
      case 'SIGNATURE_MISMATCH':
        return AppColors.warning;
      case 'ACCOUNT_CLOSED':
        return AppColors.danger;
      default:
        return AppColors.textSecondary;
    }
  }

  String _reasonLabel(String reason) {
    switch (reason.toUpperCase()) {
      case 'BOUNCE':
        return 'Bounced Cheque';
      case 'SIGNATURE_MISMATCH':
        return 'Signature Mismatch';
      case 'ACCOUNT_CLOSED':
        return 'Account Closed';
      default:
        return reason;
    }
  }

  @override
  Widget build(BuildContext context) {
    final derivedStatus = _deriveStatus();
    final isOpen = derivedStatus == 'OPEN';

    final failureReason = (penalty['failureReason'] as String?) ?? '';
    final penaltyAmount = (penalty['penaltyAmount'] as num?) ?? 0;
    final currentTotal = (penalty['currentTotal'] as num?) ?? 0;
    final outstanding = (penalty['outstanding'] as num?) ?? 0;
    final daysOverdue = (penalty['daysOverdue'] as num?) ?? 0;
    final accrued = currentTotal - penaltyAmount;
    final createdAt = Formatters.date(penalty['createdAt'] as String?);
    final reasonColor = _reasonColor(failureReason);
    final reasonLabel = _reasonLabel(failureReason);

    // Status badge color
    Color statusColor;
    switch (derivedStatus) {
      case 'WAIVED':
        statusColor = AppColors.info;
      case 'CLEARED':
        statusColor = AppColors.success;
      default:
        statusColor = AppColors.danger;
    }

    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
        border: isOpen
            ? Border.all(
                color: AppColors.danger.withValues(alpha: 0.15),
              )
            : null,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Left accent stripe
              Container(
                width: 5,
                color: isOpen ? AppColors.danger : AppColors.success,
              ),
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
                                horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: reasonColor.withValues(alpha: 0.10),
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(
                                color: reasonColor.withValues(alpha: 0.30),
                              ),
                            ),
                            child: Text(
                              reasonLabel,
                              style: GoogleFonts.josefinSans(
                                fontSize: 11,
                                fontWeight: FontWeight.w700,
                                color: reasonColor,
                              ),
                            ),
                          ),
                          const Spacer(),
                          StatusBadge(
                            label: derivedStatus,
                            color: statusColor,
                          ),
                        ],
                      ),

                      if (daysOverdue > 0) ...[
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            Icon(Icons.schedule,
                                size: 13, color: AppColors.danger),
                            const SizedBox(width: 4),
                            Text(
                              '$daysOverdue days overdue',
                              style: GoogleFonts.josefinSans(
                                fontSize: 12,
                                color: AppColors.danger,
                                fontWeight: FontWeight.w600,
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
                            label: 'Base Fine',
                            value: Formatters.currency(penaltyAmount),
                          ),
                          if (accrued > 0)
                            _AmountDetail(
                              label: 'Accrued',
                              value: Formatters.currency(accrued),
                              valueColor: AppColors.warning,
                            ),
                          _AmountDetail(
                            label: 'Current Total',
                            value: Formatters.currency(currentTotal),
                          ),
                        ],
                      ),

                      const SizedBox(height: 10),
                      Divider(
                        height: 1,
                        color: AppColors.border.withValues(alpha: 0.5),
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
                                'Outstanding',
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                              Text(
                                Formatters.currency(outstanding),
                                style: GoogleFonts.josefinSans(
                                  fontSize: 18,
                                  fontWeight: FontWeight.w700,
                                  color: isOpen
                                      ? AppColors.danger
                                      : AppColors.textPrimary,
                                ),
                              ),
                            ],
                          ),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Text(
                                'Raised',
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                              Text(
                                createdAt,
                                style: GoogleFonts.josefinSans(
                                  fontSize: 12,
                                  color: AppColors.textSecondary,
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
    return Card(
      elevation: 0,
      color: AppColors.background,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: AppColors.border.withValues(alpha: 0.8),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.info_outline_rounded,
                    size: 18, color: AppColors.primary),
                const SizedBox(width: 8),
                Text(
                  'How to Pay',
                  style: GoogleFonts.cinzel(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textPrimary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _InfoRow(
              icon: Icons.account_balance_outlined,
              label: 'Bank Transfer',
              value: 'Account details will be provided by your property manager',
            ),
            const SizedBox(height: 10),
            _InfoRow(
              icon: Icons.location_on_outlined,
              label: 'Office Payment',
              value: 'Visit the management office with a cheque or cash',
            ),
            const SizedBox(height: 10),
            _InfoRow(
              icon: Icons.access_time_outlined,
              label: 'Office Hours',
              value: 'Sunday – Thursday, 9:00 AM – 6:00 PM',
            ),
            const SizedBox(height: 12),
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.warning.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                'Contact your property manager for updated payment instructions.',
                style: GoogleFonts.josefinSans(
                  fontSize: 11,
                  color: AppColors.warning,
                  fontWeight: FontWeight.w500,
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
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: AppColors.textSecondary),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: GoogleFonts.josefinSans(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: AppColors.textSecondary,
                  letterSpacing: 0.3,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                value,
                style: GoogleFonts.josefinSans(
                  fontSize: 12,
                  color: AppColors.textPrimary,
                ),
              ),
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
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 3),
          Text(
            value,
            style: GoogleFonts.josefinSans(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: valueColor ?? AppColors.textPrimary,
            ),
          ),
        ],
      ),
    );
  }
}
