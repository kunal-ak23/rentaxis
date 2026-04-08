import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'browse_filters_sheet.dart';
import 'browse_map.dart';

// ── Providers ────────────────────────────────────────────────────────────────

final _listingApiServiceProvider = Provider<ListingApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return ListingApiService(client.dio);
});

final browseFiltersProvider =
    StateProvider<BrowseFilters>((ref) => const BrowseFilters());

final browseListingsProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(_listingApiServiceProvider);
  final filters = ref.watch(browseFiltersProvider);
  final authState = ref.watch(authProvider);
  final tenantSlug = _resolveTenantSlug(authState);
  if (tenantSlug == null) return {'content': [], 'totalElements': 0};

  return service.getMarketplaceListings(
    tenantSlug,
    minBedrooms: filters.minBedrooms,
    minRent: filters.minRent,
    maxRent: filters.maxRent,
    furnishing: filters.furnishing,
    availableNow: filters.availableNow,
    nearLat: filters.nearLat,
    nearLng: filters.nearLng,
    radiusKm: filters.radiusKm,
    page: 0,
    size: 50,
  );
});

String? _resolveTenantSlug(AuthState auth) {
  if (auth.tenants.isEmpty) return null;
  final match = auth.tenants.where((t) =>
      t['tenantId'] == auth.tenantId || t['id'] == auth.tenantId);
  final tenant = match.isNotEmpty ? match.first : auth.tenants.first;
  return tenant['slug'] as String?;
}

// ── Screen ───────────────────────────────────────────────────────────────────

class BrowseScreen extends ConsumerStatefulWidget {
  const BrowseScreen({super.key});

  @override
  ConsumerState<BrowseScreen> createState() => _BrowseScreenState();
}

class _BrowseScreenState extends ConsumerState<BrowseScreen> {
  bool _mapMode = false;
  final _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final listingsAsync = ref.watch(browseListingsProvider);
    final filters = ref.watch(browseFiltersProvider);
    final activeFilterCount = filters.activeCount;

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _SearchBar(
              controller: _searchController,
              mapMode: _mapMode,
              activeFilterCount: activeFilterCount,
              onToggleMap: () => setState(() => _mapMode = !_mapMode),
              onFilterTap: () => _openFilters(context),
            ),
            _ActiveFilterChips(filters: filters),
            Expanded(
              child: listingsAsync.when(
                loading: () => _buildShimmer(),
                error: (e, _) => ErrorState(
                  message: 'Failed to load listings',
                  onRetry: () => ref.invalidate(browseListingsProvider),
                ),
                data: (data) {
                  final items =
                      (data['content'] as List? ?? []).cast<Map<String, dynamic>>();
                  if (items.isEmpty) {
                    return EmptyState(
                      icon: Icons.apartment_outlined,
                      title: 'No listings found',
                      subtitle: 'Try adjusting your filters',
                    );
                  }
                  if (_mapMode) {
                    return BrowseMap(listings: items);
                  }
                  return RefreshIndicator(
                    onRefresh: () async =>
                        ref.invalidate(browseListingsProvider),
                    child: _ListingListView(listings: items),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _openFilters(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => BrowseFiltersSheet(
        current: ref.read(browseFiltersProvider),
        onApply: (f) {
          ref.read(browseFiltersProvider.notifier).state = f;
          ref.invalidate(browseListingsProvider);
        },
      ),
    );
  }

  Widget _buildShimmer() {
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
      itemCount: 6,
      itemBuilder: (_, i) => Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: ShimmerLoading(height: 220, width: double.infinity),
      ),
    );
  }
}

// ── Search Bar ───────────────────────────────────────────────────────────────

class _SearchBar extends StatelessWidget {
  final TextEditingController controller;
  final bool mapMode;
  final int activeFilterCount;
  final VoidCallback onToggleMap;
  final VoidCallback onFilterTap;

  const _SearchBar({
    required this.controller,
    required this.mapMode,
    required this.activeFilterCount,
    required this.onToggleMap,
    required this.onFilterTap,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
      child: Row(
        children: [
          Expanded(
            child: Container(
              height: 48,
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(14),
                boxShadow: AppShadows.soft,
              ),
              child: TextField(
                controller: controller,
                style: GoogleFonts.josefinSans(fontSize: 14),
                decoration: InputDecoration(
                  hintText: 'Search listings…',
                  hintStyle: GoogleFonts.josefinSans(
                    fontSize: 14,
                    color: AppColors.textMuted,
                  ),
                  prefixIcon: const Icon(Icons.search,
                      size: 20, color: AppColors.textMuted),
                  border: InputBorder.none,
                  contentPadding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),
          _IconBtn(
            icon: mapMode ? Icons.list_rounded : Icons.map_outlined,
            onTap: onToggleMap,
            tooltip: mapMode ? 'List view' : 'Map view',
          ),
          const SizedBox(width: 8),
          Badge(
            isLabelVisible: activeFilterCount > 0,
            label: Text('$activeFilterCount',
                style: const TextStyle(fontSize: 9, color: Colors.white)),
            backgroundColor: AppColors.primary,
            child: _IconBtn(
              icon: Icons.tune_rounded,
              onTap: onFilterTap,
              tooltip: 'Filters',
            ),
          ),
        ],
      ),
    );
  }
}

class _IconBtn extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  final String tooltip;

  const _IconBtn(
      {required this.icon, required this.onTap, required this.tooltip});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          boxShadow: AppShadows.soft,
        ),
        child: Icon(icon, size: 20, color: AppColors.textSecondary),
      ),
    );
  }
}

// ── Active filter chips row ───────────────────────────────────────────────────

class _ActiveFilterChips extends ConsumerWidget {
  final BrowseFilters filters;
  const _ActiveFilterChips({required this.filters});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final chips = <_ChipData>[];
    if (filters.minBedrooms != null)
      chips.add(_ChipData(
        '${filters.minBedrooms}+ bed',
        () => ref.read(browseFiltersProvider.notifier).state =
            filters.copyWith(clearMinBedrooms: true),
      ));
    if (filters.furnishing != null)
      chips.add(_ChipData(
        filters.furnishing!.replaceAll('_', ' ').toLowerCase(),
        () => ref.read(browseFiltersProvider.notifier).state =
            filters.copyWith(clearFurnishing: true),
      ));
    if (filters.availableNow == true)
      chips.add(_ChipData(
        'Available now',
        () => ref.read(browseFiltersProvider.notifier).state =
            filters.copyWith(clearAvailableNow: true),
      ));
    if (filters.minRent != null || filters.maxRent != null) {
      final label = [
        if (filters.minRent != null) 'AED ${(filters.minRent! / 1000).round()}k+',
        if (filters.maxRent != null) '≤AED ${(filters.maxRent! / 1000).round()}k',
      ].join(' ');
      chips.add(_ChipData(
        label,
        () => ref.read(browseFiltersProvider.notifier).state =
            filters.copyWith(clearRent: true),
      ));
    }
    if (chips.isEmpty) return const SizedBox.shrink();

    return SizedBox(
      height: 36,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: chips.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (_, i) => _FilterChip(data: chips[i]),
      ),
    );
  }
}

class _ChipData {
  final String label;
  final VoidCallback onRemove;
  const _ChipData(this.label, this.onRemove);
}

class _FilterChip extends StatelessWidget {
  final _ChipData data;
  const _FilterChip({required this.data});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: data.onRemove,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: AppColors.primary,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              data.label,
              style: GoogleFonts.josefinSans(
                fontSize: 12,
                color: Colors.white,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(width: 4),
            const Icon(Icons.close, size: 14, color: Colors.white),
          ],
        ),
      ),
    );
  }
}

// ── List view ─────────────────────────────────────────────────────────────────

class _ListingListView extends StatelessWidget {
  final List<Map<String, dynamic>> listings;
  const _ListingListView({required this.listings});

  @override
  Widget build(BuildContext context) {
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
      itemCount: listings.length,
      itemBuilder: (_, i) => AnimatedListItem(
        index: i,
        child: Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: ListingCard(
            listing: listings[i],
            onTap: () => context.push('/browse/${listings[i]['slug']}'),
          ),
        ),
      ),
    );
  }
}
