import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
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
                return GestureDetector(
                  onTap: () => setState(() => _statusFilter = filter),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: isSelected ? AppColors.primary : AppColors.surface,
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(color: isSelected ? AppColors.primary : AppColors.border),
                    ),
                    child: Center(
                      child: Text(
                        filter.replaceAll('_', ' '),
                        style: GoogleFonts.inter(
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                          color: isSelected ? Colors.white : AppColors.textSecondary,
                        ),
                      ),
                    ),
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
    final statusColor = StatusHelper.getLeaseStatusColor(status.toString());
    final rent = (lease['rentAmount'] ?? 0).toDouble();
    final renterName = (lease['renterName'] ?? 'Unknown Renter').toString();
    final unitNumber = (lease['unitNumber'] ?? '-').toString();
    final propertyName = (lease['propertyName'] ?? '-').toString();
    final endDateRaw = lease['endDate']?.toString();
    int? daysLeft;
    if (endDateRaw != null && endDateRaw.isNotEmpty) {
      try {
        daysLeft = DateTime.parse(endDateRaw).difference(DateTime.now()).inDays;
      } catch (_) {}
    }

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: IntrinsicHeight(
          child: Row(
            children: [
              Container(
                width: 4,
                decoration: BoxDecoration(
                  color: statusColor,
                  borderRadius: const BorderRadius.horizontal(left: Radius.circular(12)),
                ),
              ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Row(
                    children: [
                      Container(
                        width: 36,
                        height: 36,
                        decoration: const BoxDecoration(color: AppColors.surface2, shape: BoxShape.circle),
                        child: Center(
                          child: Text(
                            _initials(renterName),
                            style: GoogleFonts.inter(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.textSecondary),
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(renterName, style: GoogleFonts.inter(fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.textPrimary)),
                            Text('$propertyName · Unit $unitNumber', style: GoogleFonts.inter(fontSize: 11.5, color: AppColors.textMuted)),
                            const SizedBox(height: 4),
                            Text(status.toString().replaceAll('_', ' '), style: GoogleFonts.inter(fontSize: 10.5, color: statusColor, fontWeight: FontWeight.w600)),
                          ],
                        ),
                      ),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            rent > 0 ? Formatters.currencyCompact(rent) : '—',
                            style: GoogleFonts.jetBrainsMono(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
                          ),
                          Text(
                            daysLeft == null ? '—' : '${daysLeft < 0 ? 0 : daysLeft} left',
                            style: GoogleFonts.inter(fontSize: 11, color: AppColors.textMuted),
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

  String _initials(String value) {
    final parts = value.split(' ').where((e) => e.isNotEmpty).toList();
    if (parts.isEmpty) return 'R';
    if (parts.length == 1) return parts.first.substring(0, 1).toUpperCase();
    return (parts.first.substring(0, 1) + parts.last.substring(0, 1)).toUpperCase();
  }
}
