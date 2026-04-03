import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _dashboardServiceProvider = Provider<DashboardService>((ref) {
  final client = ref.watch(apiClientProvider);
  return DashboardService(client.dio);
});

final _dashboardDataProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(_dashboardServiceProvider);
  return service.getSummary();
});

class DashboardScreen extends ConsumerStatefulWidget {
  const DashboardScreen({super.key});

  @override
  ConsumerState<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends ConsumerState<DashboardScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_dashboardDataProvider);
    ref.read(notificationProvider.notifier).fetchUnreadCount();
  }

  @override
  Widget build(BuildContext context) {
    final dashboardAsync = ref.watch(_dashboardDataProvider);

    return dashboardAsync.when(
      loading: () => const Padding(
        padding: EdgeInsets.fromLTRB(20, 16, 20, 150),
        child: _DashboardShimmer(),
      ),
      error: (error, stackTrace) => ErrorState(
        message: 'Failed to load dashboard',
        onRetry: _refresh,
      ),
      data: (data) => RefreshIndicator(
        onRefresh: _refresh,
        color: AppColors.primary,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 150),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              AnimatedListItem(
                index: 0,
                child: _buildGreeting(),
              ),
              const SizedBox(height: 24),
              AnimatedListItem(
                index: 1,
                child: _buildKpiSection(data),
              ),
              const SizedBox(height: 24),
              AnimatedListItem(
                index: 2,
                child: _buildAlertsSection(data),
              ),
              const SizedBox(height: 24),
              AnimatedListItem(
                index: 3,
                child: _buildQuickActions(),
              ),
              const SizedBox(height: 24),
              AnimatedListItem(
                index: 4,
                child: _buildRecentActivity(data),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildGreeting() {
    final hour = DateTime.now().hour;
    final greeting = hour < 12 ? 'Good Morning' : (hour < 17 ? 'Good Afternoon' : 'Good Evening');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          greeting,
          style: GoogleFonts.cinzel(
            fontSize: 22,
            fontWeight: FontWeight.w700,
            color: AppColors.navyDark,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          'Here\'s your portfolio overview',
          style: GoogleFonts.josefinSans(
            fontSize: 14,
            color: AppColors.textSecondary,
          ),
        ),
      ],
    );
  }

  Widget _buildKpiSection(Map<String, dynamic> data) {
    final properties = data['totalProperties'] ?? data['propertyCount'] ?? 0;
    final units = data['totalUnits'] ?? data['unitCount'] ?? 0;
    final occupiedUnits = data['occupiedUnits'] ?? data['occupiedUnitCount'] ?? 0;
    final activeLeases = data['activeLeases'] ?? data['activeLeaseCount'] ?? 0;
    final totalRevenue = (data['totalRentRevenue'] ?? data['totalRevenue'] ?? 0).toDouble();
    final pendingAmount = (data['pendingAmount'] ?? 0).toDouble();
    final overdueAmount = (data['overdueAmount'] ?? 0).toDouble();
    final clearedAmount = (data['collectedAmount'] ?? data['clearedAmount'] ?? 0).toDouble();
    final occupancyPct = data['occupancyRate'] != null
        ? (data['occupancyRate'] as num).toDouble()
        : (units > 0 ? (occupiedUnits / units * 100) : 0.0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Overview',
            style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 14),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: 1.55,
          children: [
            _KpiCard(
              icon: Icons.apartment_rounded,
              iconColor: AppColors.primary,
              label: 'Properties',
              value: '$properties',
              onTap: () => context.go('/properties'),
            ),
            _KpiCard(
              icon: Icons.door_front_door_outlined,
              iconColor: AppColors.info,
              label: 'Units',
              value: '$units',
              onTap: () => context.go('/properties'),
            ),
            _KpiCard(
              icon: Icons.pie_chart_outline_rounded,
              iconColor: AppColors.success,
              label: 'Occupancy',
              value: '${occupancyPct.toStringAsFixed(0)}%',
              progressValue: occupancyPct / 100,
              onTap: () => context.go('/properties'),
            ),
            _KpiCard(
              icon: Icons.description_outlined,
              iconColor: AppColors.accent,
              label: 'Active Leases',
              value: '$activeLeases',
              onTap: () => context.go('/leases'),
            ),
            _KpiCard(
              icon: Icons.account_balance_wallet_outlined,
              iconColor: AppColors.primary,
              label: 'Total Revenue',
              value: Formatters.currencyCompact(totalRevenue),
              isWide: true,
              onTap: () => context.go('/payments'),
            ),
            _KpiCard(
              icon: Icons.pending_actions_outlined,
              iconColor: AppColors.warning,
              label: 'Pending',
              value: Formatters.currencyCompact(pendingAmount),
              onTap: () => context.go('/payments'),
            ),
            _KpiCard(
              icon: Icons.warning_amber_rounded,
              iconColor: AppColors.danger,
              label: 'Overdue',
              value: Formatters.currencyCompact(overdueAmount),
              onTap: () => context.go('/payments'),
            ),
            _KpiCard(
              icon: Icons.check_circle_outline,
              iconColor: AppColors.success,
              label: 'Cleared',
              value: Formatters.currencyCompact(clearedAmount),
              onTap: () => context.go('/payments'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildAlertsSection(Map<String, dynamic> data) {
    final expiringLeases = data['expiringLeases'] ?? data['expiringLeaseCount'] ?? 0;
    final overduePayments = data['overduePaymentCount'] ?? 0;
    final overdueTotal = (data['overdueAmount'] ?? 0).toDouble();
    final openTickets = data['openTicketCount'] ?? 0;

    if (expiringLeases == 0 && overduePayments == 0 && openTickets == 0) {
      return const SizedBox.shrink();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.notifications_active_outlined,
                size: 20, color: AppColors.warning),
            const SizedBox(width: 8),
            Text('Alerts',
                style: Theme.of(context).textTheme.headlineSmall),
          ],
        ),
        const SizedBox(height: 14),
        if (expiringLeases > 0)
          _AlertCard(
            icon: Icons.timer_outlined,
            color: AppColors.warning,
            title: '$expiringLeases lease${expiringLeases > 1 ? 's' : ''} expiring',
            subtitle: 'Within the next 30 days',
            onTap: () => context.go('/leases'),
          ),
        if (overduePayments > 0) ...[
          const SizedBox(height: 8),
          _AlertCard(
            icon: Icons.money_off_rounded,
            color: AppColors.danger,
            title: '$overduePayments overdue payment${overduePayments > 1 ? 's' : ''}',
            subtitle: 'Total: ${Formatters.currency(overdueTotal)}',
            onTap: () => context.go('/payments'),
          ),
        ],
        if (openTickets > 0) ...[
          const SizedBox(height: 8),
          _AlertCard(
            icon: Icons.support_agent_outlined,
            color: AppColors.info,
            title: '$openTickets open ticket${openTickets > 1 ? 's' : ''}',
            subtitle: 'Needs attention',
            onTap: () => context.push('/tickets'),
          ),
        ],
      ],
    );
  }

  Widget _buildQuickActions() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Quick Actions',
            style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 14),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              _QuickActionChip(
                icon: Icons.receipt_long_outlined,
                label: 'Collect Cheque',
                onTap: () => context.go('/payments'),
              ),
              const SizedBox(width: 10),
              _QuickActionChip(
                icon: Icons.note_add_outlined,
                label: 'Create Lease',
                onTap: () => context.go('/leases'),
              ),
              const SizedBox(width: 10),
              _QuickActionChip(
                icon: Icons.person_add_outlined,
                label: 'Add Renter',
                onTap: () => context.push('/renters'),
              ),
              const SizedBox(width: 10),
              _QuickActionChip(
                icon: Icons.confirmation_number_outlined,
                label: 'New Ticket',
                onTap: () => context.push('/tickets/create'),
              ),
              const SizedBox(width: 10),
              _QuickActionChip(
                icon: Icons.badge_outlined,
                label: 'Staff',
                onTap: () => context.push('/staff'),
              ),
              const SizedBox(width: 10),
              _QuickActionChip(
                icon: Icons.assessment_outlined,
                label: 'Reports',
                onTap: () => context.push('/finance-reports'),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildRecentActivity(Map<String, dynamic> data) {
    final activities =
        List<Map<String, dynamic>>.from(data['recentActivity'] ?? []);

    if (activities.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Recent Activity',
            style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 14),
        ...activities.take(10).indexed.map((entry) {
          final (i, activity) = entry;
          return AnimatedListItem(
            index: i + 5,
            child: _ActivityItem(
              icon: _activityIcon(activity['type'] ?? ''),
              iconColor: _activityColor(activity['type'] ?? ''),
              description: activity['description'] ?? '',
              time: Formatters.timeAgo(activity['timestamp'] ?? activity['createdAt']),
            ),
          );
        }),
      ],
    );
  }

  IconData _activityIcon(String type) {
    return switch (type) {
      'PAYMENT_COLLECTED' => Icons.payments_outlined,
      'PAYMENT_CLEARED' => Icons.check_circle_outline,
      'PAYMENT_PENDING' => Icons.pending_actions_outlined,
      'PAYMENT_ONLINE_PENDING' => Icons.cloud_sync_outlined,
      'LEASE_ACTIVATED' => Icons.description_outlined,
      'LEASE_CREATED' => Icons.note_add_outlined,
      'TICKET_CREATED' => Icons.confirmation_number_outlined,
      'TICKET_RESOLVED' => Icons.task_alt_outlined,
      'RENTER_CREATED' => Icons.person_add_outlined,
      _ => Icons.circle_outlined,
    };
  }

  Color _activityColor(String type) {
    return switch (type) {
      'PAYMENT_COLLECTED' => AppColors.info,
      'PAYMENT_CLEARED' => AppColors.success,
      'PAYMENT_PENDING' => AppColors.warning,
      'PAYMENT_ONLINE_PENDING' => AppColors.warning,
      'LEASE_ACTIVATED' => AppColors.primary,
      'LEASE_CREATED' => AppColors.accent,
      'TICKET_CREATED' => AppColors.warning,
      'TICKET_RESOLVED' => AppColors.success,
      'RENTER_CREATED' => AppColors.primary,
      _ => AppColors.textMuted,
    };
  }
}

// --- Shimmer Loading ---

class _DashboardShimmer extends StatelessWidget {
  const _DashboardShimmer();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const ShimmerLoading(height: 24, width: 180),
        const SizedBox(height: 6),
        const ShimmerLoading(height: 14, width: 220),
        const SizedBox(height: 28),
        const ShimmerLoading(height: 18, width: 100),
        const SizedBox(height: 14),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: 1.55,
          children: List.generate(4, (_) => Container(
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(16),
              boxShadow: AppShadows.soft,
            ),
            padding: const EdgeInsets.all(14),
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ShimmerLoading(height: 30, width: 30, borderRadius: 8),
                Spacer(),
                ShimmerLoading(height: 20, width: 80),
                SizedBox(height: 4),
                ShimmerLoading(height: 12, width: 60),
              ],
            ),
          )),
        ),
        const SizedBox(height: 28),
        const ShimmerLoading(height: 18, width: 120),
        const SizedBox(height: 14),
        ...List.generate(3, (_) => const Padding(
          padding: EdgeInsets.only(bottom: 10),
          child: CardShimmer(),
        )),
      ],
    );
  }
}

// --- KPI Card ---

class _KpiCard extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String label;
  final String value;
  final double? progressValue;
  final bool isWide;
  final VoidCallback? onTap;

  const _KpiCard({
    required this.icon,
    required this.iconColor,
    required this.label,
    required this.value,
    this.progressValue,
    this.isWide = false,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Container(
            padding: const EdgeInsets.all(7),
            decoration: BoxDecoration(
              color: iconColor.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, size: 18, color: iconColor),
          ),
          const Spacer(),
          Text(
            value,
            style: GoogleFonts.josefinSans(
              fontSize: 20,
              fontWeight: FontWeight.w700,
              color: AppColors.textPrimary,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          const SizedBox(height: 2),
          Text(
            label,
            style: GoogleFonts.josefinSans(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
          if (progressValue != null) ...[
            const SizedBox(height: 6),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: progressValue!.clamp(0.0, 1.0),
                backgroundColor: AppColors.border,
                color: AppColors.success,
                minHeight: 4,
              ),
            ),
          ],
        ],
      ),
    ));
  }
}

// --- Alert Card ---

class _AlertCard extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  const _AlertCard({
    required this.icon,
    required this.color,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.06),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(icon, color: color, size: 20),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: GoogleFonts.josefinSans(
                        fontWeight: FontWeight.w600,
                        fontSize: 14,
                        color: color,
                      )),
                  const SizedBox(height: 2),
                  Text(subtitle,
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      )),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: color.withValues(alpha: 0.5)),
          ],
        ),
      ),
    );
  }
}

// --- Quick Action Chip ---

class _QuickActionChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const _QuickActionChip({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(24),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.primary.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(
            color: AppColors.primary.withValues(alpha: 0.2),
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 18, color: AppColors.primary),
            const SizedBox(width: 8),
            Text(
              label,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: AppColors.primary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// --- Activity Item ---

class _ActivityItem extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String description;
  final String time;

  const _ActivityItem({
    required this.icon,
    required this.iconColor,
    required this.description,
    required this.time,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: iconColor.withValues(alpha: 0.1),
              shape: BoxShape.circle,
            ),
            child: Icon(icon, size: 16, color: iconColor),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              description,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                color: AppColors.textPrimary,
              ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 8),
          Text(
            time,
            style: GoogleFonts.josefinSans(
              fontSize: 11,
              color: AppColors.textMuted,
            ),
          ),
        ],
      ),
    );
  }
}
