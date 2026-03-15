import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

final _leasesProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_leaseServiceProvider);
  return service.getAllLeases();
});

class LeasesScreen extends ConsumerStatefulWidget {
  const LeasesScreen({super.key});

  @override
  ConsumerState<LeasesScreen> createState() => _LeasesScreenState();
}

class _LeasesScreenState extends ConsumerState<LeasesScreen> {
  String _searchQuery = '';
  String _statusFilter = 'ALL';

  final _filters = [
    'ALL',
    'DRAFT',
    'ACTIVE',
    'EXPIRED',
    'TERMINATED',
    'PENDING_SIGNATURE',
  ];

  Future<void> _refresh() async {
    ref.invalidate(_leasesProvider);
  }

  @override
  Widget build(BuildContext context) {
    final leasesAsync = ref.watch(_leasesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Leases')),
      body: Column(
        children: [
          // Search bar
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: TextField(
              onChanged: (v) => setState(() => _searchQuery = v.toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Search by renter, unit, property...',
                prefixIcon:
                    const Icon(Icons.search, color: AppColors.textMuted),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        onPressed: () => setState(() => _searchQuery = ''),
                      )
                    : null,
              ),
            ),
          ),

          // Filter chips
          SizedBox(
            height: 44,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: _filters.length,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final filter = _filters[index];
                final isSelected = _statusFilter == filter;
                return FilterChip(
                  label: Text(filter.replaceAll('_', ' ')),
                  selected: isSelected,
                  onSelected: (_) =>
                      setState(() => _statusFilter = filter),
                  selectedColor: AppColors.primary.withValues(alpha: 0.15),
                  checkmarkColor: AppColors.primary,
                  labelStyle: TextStyle(
                    fontSize: 12,
                    fontWeight:
                        isSelected ? FontWeight.w600 : FontWeight.w400,
                    color: isSelected
                        ? AppColors.primary
                        : AppColors.textSecondary,
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 8),

          Expanded(
            child: leasesAsync.when(
              loading: () => const Center(
                child: CircularProgressIndicator(color: AppColors.primary),
              ),
              error: (e, _) => ErrorState(
                message: 'Failed to load leases',
                onRetry: _refresh,
              ),
              data: (leases) {
                var filtered = leases.where((l) {
                  if (_statusFilter != 'ALL' &&
                      l['status'] != _statusFilter) {
                    return false;
                  }
                  if (_searchQuery.isNotEmpty) {
                    final renter = (l['renterName'] ?? '').toString().toLowerCase();
                    final unit = (l['unitNumber'] ?? '').toString().toLowerCase();
                    final property =
                        (l['propertyName'] ?? '').toString().toLowerCase();
                    return renter.contains(_searchQuery) ||
                        unit.contains(_searchQuery) ||
                        property.contains(_searchQuery);
                  }
                  return true;
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.description_outlined,
                    title: _searchQuery.isEmpty && _statusFilter == 'ALL'
                        ? 'No leases yet'
                        : 'No matching leases',
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 80),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final lease = filtered[index];
                      return _LeaseCard(
                        lease: lease,
                        onTap: () =>
                            context.push('/leases/${lease['id']}'),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Create lease coming soon')),
          );
        },
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }
}

class _LeaseCard extends StatelessWidget {
  final Map<String, dynamic> lease;
  final VoidCallback onTap;

  const _LeaseCard({required this.lease, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final status = lease['status'] ?? 'DRAFT';
    final statusColor = StatusHelper.getLeaseStatusColor(status);
    final annualRent = (lease['annualRent'] ?? lease['totalRent'] ?? 0).toDouble();

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          lease['renterName'] ?? 'Unknown Renter',
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 15,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            const Icon(Icons.apartment_outlined,
                                size: 14, color: AppColors.textMuted),
                            const SizedBox(width: 4),
                            Flexible(
                              child: Text(
                                '${lease['propertyName'] ?? '-'} - Unit ${lease['unitNumber'] ?? '-'}',
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: AppColors.textSecondary,
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  StatusBadge(label: status, color: statusColor),
                ],
              ),
              const Divider(height: 20),
              Row(
                children: [
                  _LeaseInfo(
                    label: 'Rent',
                    value: Formatters.currency(annualRent),
                    icon: Icons.attach_money,
                  ),
                  const Spacer(),
                  _LeaseInfo(
                    label: 'Start',
                    value: Formatters.dateShort(lease['startDate']),
                    icon: Icons.calendar_today_outlined,
                  ),
                  const SizedBox(width: 16),
                  _LeaseInfo(
                    label: 'End',
                    value: Formatters.dateShort(lease['endDate']),
                    icon: Icons.event_outlined,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _LeaseInfo extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;

  const _LeaseInfo({
    required this.label,
    required this.value,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: AppColors.textMuted),
        const SizedBox(width: 4),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label,
                style: const TextStyle(
                    fontSize: 10, color: AppColors.textMuted)),
            Text(value,
                style: const TextStyle(
                    fontSize: 12, fontWeight: FontWeight.w600)),
          ],
        ),
      ],
    );
  }
}
