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

final _leasesProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_leaseServiceProvider);
  return service.getAllLeases();
});

/// Manager Leases list — redesigned to the handoff design
/// (mobile-manager.jsx · ManagerLeases). Layout from top:
///   1. Header: serif "Leases" title + filter icon
///   2. Search bar (surface card with leading magnifier)
///   3. Filter pills: All / Active / Expiring / Drafts with item counts
///   4. List of lease cards, each with a colored status accent bar,
///      avatar (initials), tenant + unit, and rent + days-left.
class LeasesScreen extends ConsumerStatefulWidget {
  const LeasesScreen({super.key});

  @override
  ConsumerState<LeasesScreen> createState() => _LeasesScreenState();
}

enum _LeaseFilter { all, active, expiring, drafts }

class _LeasesScreenState extends ConsumerState<LeasesScreen> {
  String _searchQuery = '';
  _LeaseFilter _filter = _LeaseFilter.all;

  Future<void> _refresh() async {
    ref.invalidate(_leasesProvider);
    await ref.read(_leasesProvider.future);
  }

  bool _isExpiring(Map<String, dynamic> lease) {
    final endRaw = lease['endDate']?.toString();
    if (endRaw == null || endRaw.isEmpty) return false;
    try {
      final days = DateTime.parse(endRaw).difference(DateTime.now()).inDays;
      return lease['status'] == 'ACTIVE' && days >= 0 && days <= 30;
    } catch (_) {
      return false;
    }
  }

  bool _matchesFilter(Map<String, dynamic> lease, _LeaseFilter f) {
    switch (f) {
      case _LeaseFilter.all:
        return true;
      case _LeaseFilter.active:
        return lease['status'] == 'ACTIVE';
      case _LeaseFilter.expiring:
        return _isExpiring(lease);
      case _LeaseFilter.drafts:
        return lease['status'] == 'DRAFT' ||
            lease['status'] == 'PENDING_SIGNATURE';
    }
  }

  bool _matchesSearch(Map<String, dynamic> lease) {
    if (_searchQuery.isEmpty) return true;
    final q = _searchQuery.toLowerCase();
    return [
      lease['renterName'],
      lease['unitIdentifier'] ?? lease['unitNumber'],
      lease['propertyName'],
    ].whereType<String>().any((s) => s.toLowerCase().contains(q));
  }

  @override
  Widget build(BuildContext context) {
    final leasesAsync = ref.watch(_leasesProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Header(),
            _SearchBar(
              value: _searchQuery,
              onChanged: (v) => setState(() => _searchQuery = v),
            ),
            const SizedBox(height: 4),
            leasesAsync.when(
              loading: () => const _Pills(filter: _LeaseFilter.all, counts: {}),
              error: (_, _) =>
                  const _Pills(filter: _LeaseFilter.all, counts: {}),
              data: (leases) {
                final counts = <_LeaseFilter, int>{
                  for (final f in _LeaseFilter.values)
                    f: leases
                        .whereType<Map<String, dynamic>>()
                        .where((l) => _matchesFilter(l, f))
                        .length,
                };
                return _Pills(
                  filter: _filter,
                  counts: counts,
                  onSelect: (f) => setState(() => _filter = f),
                );
              },
            ),
            const SizedBox(height: 4),
            Expanded(
              child: RefreshIndicator(
                onRefresh: _refresh,
                color: AppColors.primary,
                child: leasesAsync.when(
                  loading: () => const _LeasesShimmer(),
                  error: (_, _) => ErrorState(
                    message: 'Failed to load leases',
                    onRetry: _refresh,
                  ),
                  data: (leases) {
                    final filtered = leases
                        .whereType<Map<String, dynamic>>()
                        .where((l) =>
                            _matchesFilter(l, _filter) && _matchesSearch(l))
                        .toList();
                    if (filtered.isEmpty) {
                      return ListView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        children: const [
                          SizedBox(height: 80),
                          EmptyState(
                            icon: Icons.description_outlined,
                            title: 'No matching leases',
                          ),
                        ],
                      );
                    }
                    return ListView.separated(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.fromLTRB(20, 4, 20, 110),
                      itemCount: filtered.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 8),
                      itemBuilder: (context, i) => _LeaseCard(
                        lease: filtered[i],
                        onTap: () =>
                            context.push('/leases/${filtered[i]['id']}'),
                      ),
                    );
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Header ─────────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
      child: Row(
        children: [
          Expanded(
            child: Text(
              'Leases',
              style: GoogleFonts.sourceSerif4(
                fontSize: 22,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
                letterSpacing: -0.4,
              ),
            ),
          ),
          InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Filters coming soon')),
              );
            },
            child: Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: AppColors.surface,
                border: Border.all(color: AppColors.border),
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Icon(Icons.tune,
                  size: 16, color: AppColors.textPrimary),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Search bar ─────────────────────────────────────────────────────────────

class _SearchBar extends StatelessWidget {
  final String value;
  final ValueChanged<String> onChanged;
  const _SearchBar({required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14),
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            const Icon(Icons.search, size: 14, color: AppColors.textMuted),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                onChanged: onChanged,
                style: GoogleFonts.inter(
                    fontSize: 13, color: AppColors.textPrimary),
                decoration: InputDecoration(
                  border: InputBorder.none,
                  isDense: true,
                  hintText: 'Search tenant, unit, cheque…',
                  hintStyle: GoogleFonts.inter(
                      fontSize: 13, color: AppColors.textMuted),
                ),
              ),
            ),
            if (value.isNotEmpty)
              GestureDetector(
                onTap: () => onChanged(''),
                child: const Icon(Icons.close,
                    size: 14, color: AppColors.textMuted),
              ),
          ],
        ),
      ),
    );
  }
}

// ─── Filter pills ───────────────────────────────────────────────────────────

class _Pills extends StatelessWidget {
  final _LeaseFilter filter;
  final Map<_LeaseFilter, int> counts;
  final ValueChanged<_LeaseFilter>? onSelect;
  const _Pills({required this.filter, required this.counts, this.onSelect});

  @override
  Widget build(BuildContext context) {
    final labels = {
      _LeaseFilter.all: 'All',
      _LeaseFilter.active: 'Active',
      _LeaseFilter.expiring: 'Expiring',
      _LeaseFilter.drafts: 'Drafts',
    };
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
      child: Row(
        children: _LeaseFilter.values.map((f) {
          final selected = filter == f;
          final label = labels[f]!;
          final count = counts[f];
          final pill = '$label${count != null ? ' · $count' : ''}';
          return Padding(
            padding: const EdgeInsets.only(right: 6),
            child: InkWell(
              borderRadius: BorderRadius.circular(999),
              onTap: onSelect == null ? null : () => onSelect!(f),
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: selected ? AppColors.primary : AppColors.surface,
                  border: Border.all(
                    color: selected ? AppColors.primary : AppColors.border,
                  ),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  pill,
                  style: GoogleFonts.inter(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    color:
                        selected ? Colors.white : AppColors.textSecondary,
                  ),
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

// ─── Lease card ─────────────────────────────────────────────────────────────

class _LeaseCard extends StatelessWidget {
  final Map<String, dynamic> lease;
  final VoidCallback onTap;
  const _LeaseCard({required this.lease, required this.onTap});

  Color _statusColor(String status) {
    switch (status) {
      case 'ACTIVE':
        return AppColors.success;
      case 'DRAFT':
      case 'PENDING_SIGNATURE':
        return AppColors.accentDark;
      case 'EXPIRED':
      case 'NOTICE_GIVEN':
        return AppColors.warning;
      case 'TERMINATED':
      case 'CLOSED':
        return AppColors.textMuted;
      default:
        return AppColors.textSecondary;
    }
  }

  String _initials(String name) {
    // "".split(RegExp(r"\s+")) returns [""], not [], so isEmpty alone
    // would let us fall into substring(0,1) on an empty string. Guard
    // against both an empty list AND an empty first token.
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.isEmpty || parts.first.isEmpty) return '?';
    if (parts.length == 1) return parts.first.substring(0, 1).toUpperCase();
    return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
  }

  String _expiryLabel(String? endDateIso) {
    if (endDateIso == null || endDateIso.isEmpty) return '';
    try {
      final dt = DateTime.parse(endDateIso);
      final days = dt.difference(DateTime.now()).inDays;
      if (days < 0) return 'Expired';
      if (days < 31) return '${days}d left';
      if (days < 365) return '${(days / 30).round()}m left';
      return '${(days / 365).round()}y left';
    } catch (_) {
      return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final renterName = (lease['renterName'] ?? 'Unknown').toString();
    final unitNumber =
        (lease['unitIdentifier'] ?? lease['unitNumber'] ?? '').toString();
    final propertyName = (lease['propertyName'] ?? '').toString();
    final unitLine = [propertyName, if (unitNumber.isNotEmpty) unitNumber]
        .where((s) => s.isNotEmpty)
        .join(' · ');
    final rent = (lease['rentAmount'] ?? 0) as num;
    final status = (lease['status'] ?? 'DRAFT').toString();
    final accent = _statusColor(status);
    final expiry = _expiryLabel(lease['endDate']?.toString());

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: IntrinsicHeight(
          child: Row(
            children: [
              Container(
                width: 4,
                margin: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(
                  color: accent,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: _Avatar(initials: _initials(renterName)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        renterName,
                        style: GoogleFonts.inter(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textPrimary,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (unitLine.isNotEmpty) ...[
                        const SizedBox(height: 1),
                        Text(
                          unitLine,
                          style: GoogleFonts.inter(
                            fontSize: 11.5,
                            color: AppColors.textMuted,
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.fromLTRB(0, 12, 14, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'AED ${_formatRent(rent)}',
                      style: GoogleFonts.jetBrainsMono(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    if (expiry.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        expiry,
                        style: GoogleFonts.inter(
                          fontSize: 11,
                          color: AppColors.textMuted,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatRent(num n) => NumberFormat('#,##0').format(n);
}

class _Avatar extends StatelessWidget {
  final String initials;
  const _Avatar({required this.initials});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 36,
      height: 36,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
        shape: BoxShape.circle,
        color: Color(0xFFECF0F5), // ink-100
      ),
      child: Text(
        initials,
        style: GoogleFonts.inter(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: AppColors.primaryLight, // ink-700
        ),
      ),
    );
  }
}

// ─── Shimmer ────────────────────────────────────────────────────────────────

class _LeasesShimmer extends StatelessWidget {
  const _LeasesShimmer();

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 110),
      itemCount: 6,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (_, _) => const ShimmerLoading(
        height: 64,
        width: double.infinity,
        borderRadius: 12,
      ),
    );
  }
}
