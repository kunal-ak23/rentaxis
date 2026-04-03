import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

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

final _contactServiceProvider = Provider<PropertyContactService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyContactService(client.dio);
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
      body: RefreshIndicator(
        color: AppColors.primary,
        onRefresh: () async {
          ref.invalidate(_myLeasesProvider);
          ref.invalidate(_myPaymentsProvider);
          ref.read(notificationProvider.notifier).fetchUnreadCount();
        },
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 150),
          children: [
            // Greeting
            AnimatedListItem(
              index: 0,
              child: _buildGreeting(context, authState),
            ),
            const SizedBox(height: 24),

            // Quick Actions
            AnimatedListItem(
              index: 1,
              child: _buildQuickActions(context),
            ),
            const SizedBox(height: 28),

            // Active Leases
            AnimatedListItem(
              index: 2,
              child: Text('My Leases',
                  style: Theme.of(context).textTheme.headlineSmall),
            ),
            const SizedBox(height: 14),

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
                  children: leases.asMap().entries.map((entry) {
                    return AnimatedListItem(
                      index: entry.key + 3,
                      child: _LeaseCard(
                        lease: entry.value,
                        nextPayment: _findNextPayment(
                          paymentsAsync.valueOrNull ?? [],
                          entry.value['id'],
                        ),
                      ),
                    );
                  }).toList(),
                );
              },
              loading: () => const ListShimmer(itemCount: 2),
              error: (err, _) => ErrorState(
                message: 'Failed to load leases',
                onRetry: () => ref.invalidate(_myLeasesProvider),
              ),
            ),

            // Key Contacts section
            const SizedBox(height: 28),
            leasesAsync.when(
              data: (leases) {
                if (leases.isEmpty) return const SizedBox.shrink();
                // Get unique property IDs from leases
                final propertyIds = leases
                    .map((l) => l['propertyId'] ?? l['property']?['id'] ?? l['unit']?['propertyId'])
                    .whereType<String>()
                    .toSet();
                if (propertyIds.isEmpty) return const SizedBox.shrink();
                return _KeyContactsSection(
                  propertyIds: propertyIds,
                  contactService: ref.read(_contactServiceProvider),
                );
              },
              loading: () => const SizedBox.shrink(),
              error: (_, __) => const SizedBox.shrink(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildGreeting(BuildContext context, AuthState authState) {
    final firstName = (authState.name ?? 'there').split(' ').first;
    final hour = DateTime.now().hour;
    final greeting = hour < 12
        ? 'Good Morning'
        : hour < 17
            ? 'Good Afternoon'
            : 'Good Evening';
    final today = DateFormat('EEEE, d MMMM yyyy').format(DateTime.now());

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '$greeting, $firstName',
          style: GoogleFonts.inter(
            fontSize: 22,
            fontWeight: FontWeight.w700,
            color: AppColors.textPrimary,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          today,
          style: Theme.of(context)
              .textTheme
              .bodyMedium
              ?.copyWith(color: AppColors.textMuted),
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

class _QuickActionButton extends StatefulWidget {
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
  State<_QuickActionButton> createState() => _QuickActionButtonState();
}

class _QuickActionButtonState extends State<_QuickActionButton> {
  double _scale = 1.0;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GestureDetector(
        onTapDown: (_) => setState(() => _scale = 0.95),
        onTapUp: (_) {
          setState(() => _scale = 1.0);
          widget.onTap();
        },
        onTapCancel: () => setState(() => _scale = 1.0),
        child: AnimatedScale(
          scale: _scale,
          duration: const Duration(milliseconds: 150),
          curve: Curves.easeOut,
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 18),
            decoration: BoxDecoration(
              color: widget.color.withValues(alpha: 0.06),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Column(
              children: [
                Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: widget.color.withValues(alpha: 0.1),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(widget.icon, color: widget.color, size: 24),
                ),
                const SizedBox(height: 10),
                Text(
                  widget.label,
                  style: GoogleFonts.josefinSans(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: widget.color,
                  ),
                ),
              ],
            ),
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
        lease['propertyName'] ?? lease['property']?['nameEn'] ?? lease['property']?['name'] ?? 'Property';
    final unitNumber =
        lease['unitIdentifier'] ?? lease['unit']?['unitNumber'] ?? lease['unitNumber'] ?? '';
    final status = lease['status'] ?? 'ACTIVE';
    final monthlyRent = lease['monthlyRent'] ?? lease['rentAmount'] ?? 0;
    final startDate = Formatters.date(lease['startDate']);
    final endDate = Formatters.date(lease['endDate']);

    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: IntrinsicHeight(
        child: Row(
          children: [
            // Teal accent stripe
            Container(
              width: 4,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [AppColors.primary, AppColors.primaryLight],
                ),
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(16),
                  bottomLeft: Radius.circular(16),
                ),
              ),
            ),
            // Content
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(18),
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
                                style:
                                    Theme.of(context).textTheme.titleLarge,
                                overflow: TextOverflow.ellipsis,
                              ),
                              const SizedBox(height: 3),
                              Text(
                                'Unit $unitNumber',
                                style: Theme.of(context)
                                    .textTheme
                                    .bodySmall
                                    ?.copyWith(color: AppColors.textMuted),
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
                    const SizedBox(height: 14),
                    Divider(
                      height: 1,
                      color: AppColors.border.withValues(alpha: 0.5),
                    ),
                    const SizedBox(height: 14),

                    // Rent & Dates
                    Row(
                      children: [
                        _InfoItem(
                          label: 'Monthly Rent',
                          value: Formatters.currency(monthlyRent),
                          valueStyle: GoogleFonts.josefinSans(
                            fontSize: 17,
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
                      const SizedBox(height: 14),
                      _NextPaymentAlert(payment: nextPayment!),
                    ],
                  ],
                ),
              ),
            ),
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

  const _InfoItem(
      {required this.label, required this.value, this.valueStyle});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelSmall),
        const SizedBox(height: 3),
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

    final message = isOverdue
        ? '${daysUntil.abs()} days overdue'
        : daysUntil == 0
            ? 'Due today'
            : 'Due in $daysUntil days';

    return GestureDetector(
      onTap: () => GoRouter.of(context).go('/payments'),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: [
              AppColors.navyDark,
              AppColors.navyDark.withValues(alpha: 0.9),
            ],
          ),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Icon(
              isOverdue ? Icons.warning_amber_rounded : Icons.schedule,
              color: isOverdue ? AppColors.danger : AppColors.accent,
              size: 20,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    message,
                    style: GoogleFonts.josefinSans(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: Colors.white.withValues(alpha: 0.7),
                    ),
                  ),
                  Text(
                    Formatters.currency(amount),
                    style: GoogleFonts.josefinSans(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      color: Colors.white,
                    ),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right,
                color: Colors.white.withValues(alpha: 0.5), size: 20),
          ],
        ),
      ),
    );
  }
}

// --- Key Contacts Section ---

class _KeyContactsSection extends StatefulWidget {
  final Set<String> propertyIds;
  final PropertyContactService contactService;

  const _KeyContactsSection({
    required this.propertyIds,
    required this.contactService,
  });

  @override
  State<_KeyContactsSection> createState() => _KeyContactsSectionState();
}

class _KeyContactsSectionState extends State<_KeyContactsSection> {
  List<Map<String, dynamic>> _contacts = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadContacts();
  }

  Future<void> _loadContacts() async {
    try {
      final allContacts = <Map<String, dynamic>>[];
      for (final propId in widget.propertyIds) {
        final contacts = await widget.contactService.getContacts(propId);
        for (final c in contacts) {
          allContacts.add(Map<String, dynamic>.from(c));
        }
      }
      if (mounted) {
        setState(() {
          _contacts = allContacts;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  IconData _categoryIcon(String? category) {
    return switch (category?.toUpperCase()) {
      'PLUMBER' => Icons.plumbing,
      'ELECTRICIAN' => Icons.electrical_services,
      'HANDYMAN' => Icons.handyman,
      'SECURITY' => Icons.security,
      'HOSPITAL_CLINIC' => Icons.local_hospital,
      'PHARMACY' => Icons.local_pharmacy,
      'BUILDING_MAINTENANCE' => Icons.build_circle_outlined,
      'CIVIL_DEFENSE' => Icons.shield_outlined,
      _ => Icons.contacts_outlined,
    };
  }

  Color _categoryColor(String? category) {
    return switch (category?.toUpperCase()) {
      'PLUMBER' => AppColors.info,
      'ELECTRICIAN' => AppColors.warning,
      'HANDYMAN' => AppColors.primary,
      'SECURITY' => AppColors.danger,
      'HOSPITAL_CLINIC' => AppColors.danger,
      'PHARMACY' => AppColors.success,
      'BUILDING_MAINTENANCE' => AppColors.accent,
      'CIVIL_DEFENSE' => AppColors.danger,
      _ => AppColors.textSecondary,
    };
  }

  String _categoryLabel(String? category) {
    if (category == null) return 'Contact';
    return category
        .replaceAll('_', ' ')
        .split(' ')
        .map((w) => w.isEmpty ? '' : '${w[0].toUpperCase()}${w.substring(1).toLowerCase()}')
        .join(' ');
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Key Contacts', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 14),
          const ShimmerLoading(height: 70),
        ],
      );
    }

    if (_contacts.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AnimatedListItem(
          index: 6,
          child: Row(
            children: [
              const Icon(Icons.contacts_outlined, size: 20, color: AppColors.primary),
              const SizedBox(width: 8),
              Text('Key Contacts', style: Theme.of(context).textTheme.headlineSmall),
            ],
          ),
        ),
        const SizedBox(height: 14),
        ..._contacts.asMap().entries.map((entry) {
          final contact = entry.value;
          final category = contact['category'] as String?;
          final name = contact['name'] ?? 'Contact';
          final phone = contact['phone'] as String?;
          final role = contact['customLabel'] ?? _categoryLabel(category);

          return AnimatedListItem(
            index: entry.key + 7,
            child: Container(
              margin: const EdgeInsets.only(bottom: 10),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(16),
                boxShadow: AppShadows.soft,
              ),
              child: ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                leading: Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: _categoryColor(category).withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _categoryIcon(category),
                    color: _categoryColor(category),
                    size: 22,
                  ),
                ),
                title: Text(
                  name,
                  style: GoogleFonts.josefinSans(
                    fontWeight: FontWeight.w600,
                    fontSize: 14,
                  ),
                ),
                subtitle: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      role,
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                    if (phone != null && phone.isNotEmpty)
                      Text(
                        phone,
                        style: GoogleFonts.josefinSans(
                          fontSize: 12,
                          color: AppColors.textMuted,
                        ),
                      ),
                  ],
                ),
                trailing: phone != null && phone.isNotEmpty
                    ? IconButton(
                        icon: Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: AppColors.success.withValues(alpha: 0.1),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.phone, color: AppColors.success, size: 18),
                        ),
                        onPressed: () => launchUrl(Uri.parse('tel:$phone')),
                      )
                    : null,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16),
                ),
              ),
            ),
          );
        }),
      ],
    );
  }
}
