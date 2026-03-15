import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
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

final _myLeasesProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final service = ref.watch(_leaseServiceProvider);
  return service.getMyLeases();
});

final _myPaymentsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final service = ref.watch(_paymentServiceProvider);
  return service.getMyPayments();
});

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);
    final leasesAsync = ref.watch(_myLeasesProvider);
    final paymentsAsync = ref.watch(_myPaymentsProvider);
    final notifState = ref.watch(notificationProvider);

    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppColors.navyDark,
        title: const Text(
          'RentAxis',
          style: TextStyle(
            color: AppColors.accent,
            fontWeight: FontWeight.w700,
            fontSize: 20,
            letterSpacing: 0.5,
          ),
        ),
        actions: [
          Stack(
            children: [
              IconButton(
                icon: const Icon(Icons.notifications_outlined),
                onPressed: () => context.push('/notifications'),
              ),
              if (notifState.unreadCount > 0)
                Positioned(
                  right: 8,
                  top: 8,
                  child: Container(
                    padding: const EdgeInsets.all(4),
                    decoration: const BoxDecoration(
                      color: AppColors.danger,
                      shape: BoxShape.circle,
                    ),
                    constraints:
                        const BoxConstraints(minWidth: 18, minHeight: 18),
                    child: Text(
                      notifState.unreadCount > 99
                          ? '99+'
                          : '${notifState.unreadCount}',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 10,
                        fontWeight: FontWeight.w600,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
      body: RefreshIndicator(
        color: AppColors.primary,
        onRefresh: () async {
          ref.invalidate(_myLeasesProvider);
          ref.invalidate(_myPaymentsProvider);
          ref.read(notificationProvider.notifier).fetchUnreadCount();
        },
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            // Greeting
            _buildGreeting(context, authState),
            const SizedBox(height: 20),

            // Quick Actions
            _buildQuickActions(context),
            const SizedBox(height: 24),

            // Active Leases
            Text('My Leases',
                style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),

            leasesAsync.when(
              data: (leases) {
                if (leases.isEmpty) {
                  return const EmptyState(
                    icon: Icons.description_outlined,
                    title: 'No Active Leases',
                    subtitle: 'Your lease details will appear here',
                  );
                }
                return Column(
                  children: leases.map((lease) {
                    return _LeaseCard(
                      lease: lease,
                      nextPayment: _findNextPayment(
                        paymentsAsync.valueOrNull ?? [],
                        lease['id'],
                      ),
                    );
                  }).toList(),
                );
              },
              loading: () => const Center(
                child: Padding(
                  padding: EdgeInsets.all(32),
                  child: CircularProgressIndicator(color: AppColors.primary),
                ),
              ),
              error: (err, _) => ErrorState(
                message: 'Failed to load leases',
                onRetry: () => ref.invalidate(_myLeasesProvider),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildGreeting(BuildContext context, AuthState authState) {
    final firstName = (authState.name ?? 'there').split(' ').first;
    final today = DateFormat('EEEE, d MMMM yyyy').format(DateTime.now());

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Hi $firstName',
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        const SizedBox(height: 4),
        Text(
          today,
          style: Theme.of(context)
              .textTheme
              .bodyMedium
              ?.copyWith(color: AppColors.textSecondary),
        ),
      ],
    );
  }

  Widget _buildQuickActions(BuildContext context) {
    return Row(
      children: [
        _QuickActionButton(
          icon: Icons.payment,
          label: 'Pay Rent',
          color: AppColors.primary,
          onTap: () => context.go('/payments'),
        ),
        const SizedBox(width: 12),
        _QuickActionButton(
          icon: Icons.build_outlined,
          label: 'Raise Ticket',
          color: AppColors.warning,
          onTap: () => context.push('/tickets/create'),
        ),
        const SizedBox(width: 12),
        _QuickActionButton(
          icon: Icons.notifications_outlined,
          label: 'Notifications',
          color: AppColors.info,
          onTap: () => context.push('/notifications'),
        ),
      ],
    );
  }

  Map<String, dynamic>? _findNextPayment(
      List<dynamic> payments, String? leaseId) {
    if (payments.isEmpty) return null;
    final pending = payments.where((p) {
      final status = p['status'] ?? '';
      final pLeaseId = p['leaseId'] ?? p['lease']?['id'];
      return (status == 'PENDING' || status == 'OVERDUE') &&
          (leaseId == null || pLeaseId == leaseId);
    }).toList();
    if (pending.isEmpty) return null;
    pending.sort((a, b) {
      final aDate = a['dueDate'] ?? '';
      final bDate = b['dueDate'] ?? '';
      return aDate.compareTo(bDate);
    });
    return pending.first;
  }
}

class _QuickActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  const _QuickActionButton({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 16),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: color.withValues(alpha: 0.2)),
          ),
          child: Column(
            children: [
              Icon(icon, color: color, size: 28),
              const SizedBox(height: 8),
              Text(
                label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _LeaseCard extends StatelessWidget {
  final Map<String, dynamic> lease;
  final Map<String, dynamic>? nextPayment;

  const _LeaseCard({required this.lease, this.nextPayment});

  @override
  Widget build(BuildContext context) {
    final propertyName =
        lease['property']?['name'] ?? lease['propertyName'] ?? 'Property';
    final unitNumber =
        lease['unit']?['unitNumber'] ?? lease['unitNumber'] ?? '-';
    final status = lease['status'] ?? 'ACTIVE';
    final monthlyRent = lease['monthlyRent'] ?? lease['rentAmount'] ?? 0;
    final startDate = Formatters.date(lease['startDate']);
    final endDate = Formatters.date(lease['endDate']);

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        propertyName,
                        style: Theme.of(context).textTheme.titleLarge,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        'Unit $unitNumber',
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: AppColors.textSecondary),
                      ),
                    ],
                  ),
                ),
                StatusBadge(
                  label: status,
                  color: StatusHelper.getLeaseStatusColor(status),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Divider(height: 1),
            const SizedBox(height: 12),

            // Rent & Dates
            Row(
              children: [
                _InfoItem(
                  label: 'Monthly Rent',
                  value: Formatters.currency(monthlyRent),
                  valueStyle: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: AppColors.primary,
                  ),
                ),
                const Spacer(),
                _InfoItem(
                  label: 'Period',
                  value: '$startDate - $endDate',
                ),
              ],
            ),

            // Next Payment Alert
            if (nextPayment != null) ...[
              const SizedBox(height: 12),
              _NextPaymentAlert(payment: nextPayment!),
            ],
          ],
        ),
      ),
    );
  }
}

class _InfoItem extends StatelessWidget {
  final String label;
  final String value;
  final TextStyle? valueStyle;

  const _InfoItem({required this.label, required this.value, this.valueStyle});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelSmall),
        const SizedBox(height: 2),
        Text(
          value,
          style: valueStyle ?? Theme.of(context).textTheme.titleMedium,
        ),
      ],
    );
  }
}

class _NextPaymentAlert extends StatelessWidget {
  final Map<String, dynamic> payment;

  const _NextPaymentAlert({required this.payment});

  @override
  Widget build(BuildContext context) {
    final dueDate = payment['dueDate'];
    final amount = payment['totalPayable'] ?? payment['amount'] ?? 0;
    final status = payment['status'] ?? 'PENDING';
    final isOverdue = status == 'OVERDUE';

    int daysUntil = 0;
    if (dueDate != null) {
      try {
        final due = DateTime.parse(dueDate);
        daysUntil = due.difference(DateTime.now()).inDays;
      } catch (_) {}
    }

    final color = isOverdue ? AppColors.danger : AppColors.warning;
    final message = isOverdue
        ? '${daysUntil.abs()} days overdue'
        : daysUntil == 0
            ? 'Due today'
            : 'Due in $daysUntil days';

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Row(
        children: [
          Icon(
            isOverdue ? Icons.warning_amber_rounded : Icons.schedule,
            color: color,
            size: 20,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  message,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: color,
                  ),
                ),
                Text(
                  Formatters.currency(amount),
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
              ],
            ),
          ),
          Icon(Icons.chevron_right, color: color, size: 20),
        ],
      ),
    );
  }
}
