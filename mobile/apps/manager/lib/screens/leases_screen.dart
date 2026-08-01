import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

final _leasesProvider = FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_leaseServiceProvider);
  return service.getAllLeases();
});

/// Manager Leases list, per admin design 1d: dark chrome header (overline +
/// Cinzel title + filter chips), search bar, cheque-progress lease cards.
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
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: context.miftah.background,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            leasesAsync.when(
              loading: () => _ChromeHeader(
                l: l,
                activeCount: null,
                filter: _filter,
                counts: const {},
              ),
              error: (_, _) => _ChromeHeader(
                l: l,
                activeCount: null,
                filter: _filter,
                counts: const {},
              ),
              data: (leases) {
                final typed = leases.whereType<Map<String, dynamic>>();
                final counts = <_LeaseFilter, int>{
                  for (final f in _LeaseFilter.values)
                    f: typed.where((lse) => _matchesFilter(lse, f)).length,
                };
                final activeCount = typed
                    .where((lse) => lse['status'] == 'ACTIVE')
                    .length;
                return _ChromeHeader(
                  l: l,
                  activeCount: activeCount,
                  filter: _filter,
                  counts: counts,
                  onSelect: (f) => setState(() => _filter = f),
                );
              },
            ),
            _SearchBar(
              value: _searchQuery,
              onChanged: (v) => setState(() => _searchQuery = v),
              l: l,
            ),
            Expanded(
              child: RefreshIndicator(
                onRefresh: _refresh,
                color: AppColors.accent,
                child: leasesAsync.when(
                  loading: () => const _LeasesShimmer(),
                  error: (_, _) =>
                      ErrorState(message: l.failedToLoad, onRetry: _refresh),
                  data: (leases) {
                    final filtered = leases
                        .whereType<Map<String, dynamic>>()
                        .where(
                          (lse) =>
                              _matchesFilter(lse, _filter) &&
                              _matchesSearch(lse),
                        )
                        .toList();
                    if (filtered.isEmpty) {
                      return ListView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        children: [
                          const SizedBox(height: 80),
                          EmptyState(
                            icon: Icons.description_outlined,
                            title: l.noMatchingLeases,
                          ),
                        ],
                      );
                    }
                    return ListView.separated(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: EdgeInsets.fromLTRB(
                        16,
                        12,
                        16,
                        AppInsets.bottomNav(context),
                      ),
                      itemCount: filtered.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 9),
                      itemBuilder: (context, i) => _LeaseCard(
                        lease: filtered[i],
                        l: l,
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

// ─── Chrome header (dark chrome + filter chips) ────────────────────────────

class _ChromeHeader extends StatelessWidget {
  final _L l;
  final int? activeCount;
  final _LeaseFilter filter;
  final Map<_LeaseFilter, int> counts;
  final ValueChanged<_LeaseFilter>? onSelect;

  const _ChromeHeader({
    required this.l,
    required this.activeCount,
    required this.filter,
    required this.counts,
    this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.16)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            activeCount != null
                ? l.activeCountLabel(activeCount!)
                : l.leasesOverline,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 12,
                    color: AppColors.goldMid,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 9.5,
                    letterSpacing: 2.4,
                    color: AppColors.goldMid,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    color: AppColors.gold400,
                  )
                : GoogleFonts.cinzel(fontSize: 22, color: AppColors.gold400),
          ),
          const SizedBox(height: 12),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: _LeaseFilter.values.map((f) {
                final selected = filter == f;
                final label = l.filterLabel(f);
                final count = counts[f];
                return Padding(
                  padding: const EdgeInsetsDirectional.only(end: 7),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(999),
                    onTap: onSelect == null ? null : () => onSelect!(f),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        gradient: selected ? MiftahGradients.gold : null,
                        color: selected ? null : Colors.transparent,
                        border: selected
                            ? null
                            : Border.all(
                                color: Colors.white.withValues(alpha: 0.14),
                              ),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        count != null && count > 0 ? '$label · $count' : label,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 11.5,
                                fontWeight: FontWeight.w600,
                                color: selected
                                    ? AppColors.primary
                                    : Colors.white.withValues(alpha: 0.6),
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 9.5,
                                letterSpacing: 1.4,
                                fontWeight: FontWeight.w600,
                                color: selected
                                    ? AppColors.primary
                                    : Colors.white.withValues(alpha: 0.6),
                              ),
                      ),
                    ),
                  ),
                );
              }).toList(),
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
  final _L l;
  const _SearchBar({
    required this.value,
    required this.onChanged,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
      child: Container(
        padding: const EdgeInsetsDirectional.only(start: 14, end: 10),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Icon(Icons.search, size: 15, color: m.textMuted),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                onChanged: onChanged,
                textAlign: l.ar ? TextAlign.right : TextAlign.left,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 13.5,
                        color: m.textPrimary,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 13,
                        color: m.textPrimary,
                      ),
                decoration: InputDecoration(
                  border: InputBorder.none,
                  isDense: true,
                  hintText: l.searchHint,
                  hintStyle: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13.5,
                          color: m.textMuted,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 13,
                          color: m.textMuted,
                        ),
                ),
              ),
            ),
            if (value.isNotEmpty)
              GestureDetector(
                onTap: () => onChanged(''),
                child: Icon(Icons.close, size: 15, color: m.textMuted),
              ),
          ],
        ),
      ),
    );
  }
}

// ─── Lease card ─────────────────────────────────────────────────────────────

class _LeaseCard extends StatelessWidget {
  final Map<String, dynamic> lease;
  final _L l;
  final VoidCallback onTap;
  const _LeaseCard({required this.lease, required this.l, required this.onTap});

  Color _statusColor(MiftahColors m, String status) {
    switch (status) {
      case 'ACTIVE':
        return m.success;
      case 'DRAFT':
      case 'PENDING_SIGNATURE':
        return AppColors.accentDark;
      case 'EXPIRED':
      case 'NOTICE_GIVEN':
        return m.warning;
      case 'TERMINATED':
      case 'CLOSED':
        return m.textMuted;
      default:
        return m.textSecondary;
    }
  }

  /// Cheque totals from either a raw `payments`/`cheques` list on the lease
  /// map, or `numberOfPayments`/`clearedPayments` counters — whichever the
  /// API happens to expose. Falls back gracefully when absent.
  (int total, int cleared, bool hasBounced) _chequeProgress() {
    final rawList = (lease['payments'] ?? lease['cheques']) as List?;
    if (rawList != null && rawList.isNotEmpty) {
      final total = rawList.length;
      var cleared = 0;
      var bounced = false;
      for (final p in rawList) {
        final status = (p is Map ? p['status'] : null)?.toString();
        if (status == 'CLEARED') cleared++;
        if (status == 'BOUNCED') bounced = true;
      }
      return (total, cleared, bounced);
    }
    final total = (lease['numberOfPayments'] as num?)?.toInt() ?? 0;
    final cleared = (lease['clearedPayments'] as num?)?.toInt() ?? 0;
    final bounced = lease['hasBouncedCheque'] == true;
    return (total, cleared, bounced);
  }

  String _expiryLabel(String? endDateIso) {
    if (endDateIso == null || endDateIso.isEmpty) return '';
    try {
      final dt = DateTime.parse(endDateIso);
      final days = dt.difference(DateTime.now()).inDays;
      if (days < 0) return l.expired;
      return l.daysLeft(days);
    } catch (_) {
      return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final renterName = (lease['renterName'] ?? l.unknown).toString();
    final unitNumber = (lease['unitIdentifier'] ?? lease['unitNumber'] ?? '')
        .toString();
    final propertyName = (lease['propertyName'] ?? '').toString();
    final unitLine = [
      propertyName,
      if (unitNumber.isNotEmpty) unitNumber,
    ].where((s) => s.isNotEmpty).join(' · ');
    final rent = (lease['rentAmount'] ?? 0) as num;
    final status = (lease['status'] ?? 'DRAFT').toString();
    final accent = _statusColor(m, status);
    final expiry = _expiryLabel(lease['endDate']?.toString());
    final (total, cleared, hasBounced) = _chequeProgress();

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(14),
        ),
        padding: const EdgeInsets.all(13),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        renterName,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                                color: m.textPrimary,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 14.5,
                                fontWeight: FontWeight.w600,
                                color: m.textPrimary,
                              ),
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (unitLine.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          unitLine,
                          style: l.ar
                              ? GoogleFonts.notoNaskhArabic(
                                  fontSize: 12.5,
                                  color: m.textMuted,
                                )
                              : GoogleFonts.josefinSans(
                                  fontSize: 11.5,
                                  color: m.textMuted,
                                ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                _StatusPill(
                  label: l.leaseStatusLabel(status),
                  color: accent,
                  m: m,
                  ar: l.ar,
                ),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(
                  child: total > 0
                      ? _ChequeProgressBar(total: total, cleared: cleared, m: m)
                      : Text(
                          Formatters.currency(rent),
                          style: GoogleFonts.cinzel(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                        ),
                ),
                const SizedBox(width: 8),
                Text(
                  total > 0
                      ? l.chequesCleared(cleared, total)
                      : (expiry.isNotEmpty ? expiry : ''),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 11,
                          color: m.textMuted,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 10.5,
                          color: m.textMuted,
                        ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ChequeProgressBar extends StatelessWidget {
  final int total;
  final int cleared;
  final MiftahColors m;
  const _ChequeProgressBar({
    required this.total,
    required this.cleared,
    required this.m,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: List.generate(total, (i) {
        final isCleared = i < cleared;
        return Expanded(
          child: Container(
            height: 6,
            margin: EdgeInsetsDirectional.only(end: i == total - 1 ? 0 : 4),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(999),
              gradient: isCleared ? MiftahGradients.gold : null,
              color: isCleared ? null : m.surfaceDim,
            ),
          ),
        );
      }),
    );
  }
}

class _StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  final MiftahColors m;
  final bool ar;
  const _StatusPill({
    required this.label,
    required this.color,
    required this.m,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: m.isDark ? 0.14 : 0.08),
        border: Border.all(color: color.withValues(alpha: 0.3)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.josefinSans(
                fontSize: 9,
                letterSpacing: 1.2,
                fontWeight: FontWeight.w600,
                color: color,
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
      padding: EdgeInsets.fromLTRB(16, 12, 16, AppInsets.bottomNav(context)),
      itemCount: 6,
      separatorBuilder: (_, _) => const SizedBox(height: 9),
      itemBuilder: (_, _) => const ShimmerLoading(
        height: 72,
        width: double.infinity,
        borderRadius: 14,
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'عقود الإيجار' : 'Leases';
  String get leasesOverline => ar ? 'عقود الإيجار' : 'LEASES';
  String get searchHint =>
      ar ? 'بحث عن مستأجر، وحدة، شيك…' : 'Search tenant, unit, cheque…';
  String get noMatchingLeases =>
      ar ? 'لا توجد عقود مطابقة' : 'No matching leases';
  String get failedToLoad => ar ? 'تعذر تحميل العقود' : 'Failed to load leases';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get expired => ar ? 'منتهي' : 'Expired';

  String activeCountLabel(int n) => ar ? '$n عقد نشط' : '$n ACTIVE';

  String filterLabel(_LeaseFilter f) {
    switch (f) {
      case _LeaseFilter.all:
        return ar ? 'الكل' : 'All';
      case _LeaseFilter.active:
        return ar ? 'نشط' : 'Active';
      case _LeaseFilter.expiring:
        return ar ? 'قريب الانتهاء' : 'Expiring';
      case _LeaseFilter.drafts:
        return ar ? 'بانتظار التوقيع' : 'Pending sig.';
    }
  }

  String leaseStatusLabel(String status) {
    switch (status) {
      case 'ACTIVE':
        return ar ? 'ساري' : 'Current';
      case 'DRAFT':
        return ar ? 'مسودة' : 'Draft';
      case 'PENDING_SIGNATURE':
        return ar ? 'بانتظار التوقيع' : 'Awaiting sig.';
      case 'EXPIRED':
        return ar ? 'منتهي' : 'Expired';
      case 'NOTICE_GIVEN':
        return ar ? 'إشعار إنهاء' : 'Notice given';
      case 'TERMINATED':
        return ar ? 'منهى' : 'Terminated';
      case 'CLOSED':
        return ar ? 'مغلق' : 'Closed';
      default:
        return status;
    }
  }

  // Arabic numeral–noun agreement for "N days left".
  String daysLeft(int n) {
    if (!ar) return '${n}d left';
    if (n == 1) return 'يوم واحد متبقٍ';
    if (n == 2) return 'يومان متبقيان';
    if (n >= 3 && n <= 10) return '$n أيام متبقية';
    return '$n يوماً متبقياً';
  }

  String chequesCleared(int cleared, int total) =>
      ar ? '$cleared/$total مصروف' : '$cleared/$total cleared';
}
