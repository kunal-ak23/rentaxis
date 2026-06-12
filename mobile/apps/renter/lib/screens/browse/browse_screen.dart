import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'browse_filters.dart';
import 'browse_filters_sheet.dart';
import 'browse_map.dart';

// ── Providers ────────────────────────────────────────────────────────────────

final _listingApiProvider = Provider<ListingApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return ListingApiService(client.dio);
});

final browseFiltersProvider = StateProvider.autoDispose<BrowseFilters>(
  (ref) => const BrowseFilters(),
);

final browseListingsProvider = FutureProvider.autoDispose<Map<String, dynamic>>(
  (ref) async {
    final service = ref.watch(listingApiServiceProvider);
    final filters = ref.watch(browseFiltersProvider);
    final authState = ref.watch(authProvider);
    final tenantSlug = resolveTenantSlug(authState);
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
  },
);

// ── Screen ───────────────────────────────────────────────────────────────────

class BrowseScreen extends ConsumerStatefulWidget {
  const BrowseScreen({super.key});

  @override
  ConsumerState<BrowseScreen> createState() => _BrowseScreenState();
}

class _BrowseScreenState extends ConsumerState<BrowseScreen> {
  bool _mapMode = false;
  String _searchQuery = '';
  final _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _searchController.addListener(() {
      final q = _searchController.text.trim().toLowerCase();
      if (q != _searchQuery) setState(() => _searchQuery = q);
    });
    // Seed near-me filters from device location on first open.
    WidgetsBinding.instance.addPostFrameCallback((_) => _seedLocation());
  }

  Future<void> _seedLocation() async {
    // Only seed if user hasn't manually set a location filter.
    final current = ref.read(browseFiltersProvider);
    if (current.nearLat != null) return;
    try {
      final locationService = ref.read(locationServiceProvider);
      final pos = await locationService.getCurrentPosition();
      if (pos != null && mounted) {
        ref.read(browseFiltersProvider.notifier).state = current.copyWith(
          nearLat: pos.latitude,
          nearLng: pos.longitude,
          radiusKm: 10,
        );
      }
    } catch (_) {
      // Location permission denied or unavailable — browse without proximity.
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<Map<String, dynamic>> _applySearch(List<Map<String, dynamic>> items) {
    if (_searchQuery.isEmpty) return items;
    return items.where((l) {
      final title = (l['title'] as String? ?? '').toLowerCase();
      final property = (l['propertyName'] as String? ?? '').toLowerCase();
      return title.contains(_searchQuery) || property.contains(_searchQuery);
    }).toList();
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
                  final allItems = (data['content'] as List? ?? [])
                      .cast<Map<String, dynamic>>();
                  final items = _applySearch(allItems);
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
                    child: _ListingListView(
                      listings: items,
                      truncated: allItems.length >= 50,
                      serverCount: allItems.length,
                    ),
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
      padding: EdgeInsets.fromLTRB(16, 8, 16, AppInsets.bottomNav(context)),
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
                  prefixIcon: const Icon(
                    Icons.search,
                    size: 20,
                    color: AppColors.textMuted,
                  ),
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
            label: Text(
              '$activeFilterCount',
              style: const TextStyle(fontSize: 9, color: Colors.white),
            ),
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

  const _IconBtn({
    required this.icon,
    required this.onTap,
    required this.tooltip,
  });

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
      chips.add(
        _ChipData(
          '${filters.minBedrooms}+ bed',
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearMinBedrooms: true),
        ),
      );
    if (filters.furnishing != null)
      chips.add(
        _ChipData(
          filters.furnishing!.replaceAll('_', ' ').toLowerCase(),
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearFurnishing: true),
        ),
      );
    if (filters.availableNow == true)
      chips.add(
        _ChipData(
          'Available now',
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearAvailableNow: true),
        ),
      );
    if (filters.minRent != null || filters.maxRent != null) {
      final label = [
        if (filters.minRent != null)
          'AED ${(filters.minRent! / 1000).round()}k+',
        if (filters.maxRent != null)
          '≤AED ${(filters.maxRent! / 1000).round()}k',
      ].join(' ');
      chips.add(
        _ChipData(
          label,
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearRent: true),
        ),
      );
    }
    if (filters.nearLat != null)
      chips.add(
        _ChipData(
          'Near me${filters.radiusKm != null ? ' (${filters.radiusKm!.round()} km)' : ''}',
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearNearby: true),
        ),
      );
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

class _ListingListView extends ConsumerWidget {
  final List<Map<String, dynamic>> listings;
  final bool truncated;
  final int serverCount;
  const _ListingListView({
    required this.listings,
    this.truncated = false,
    this.serverCount = 0,
  });

  Future<void> _toggleWishlist(BuildContext context, WidgetRef ref,
      String listingId, bool isWishlisted) async {
    final notifier = ref.read(wishlistIdsProvider.notifier);
    final service = ref.read(_listingApiProvider);
    try {
      if (isWishlisted) {
        notifier.remove(listingId);
        await service.removeInterest(listingId);
      } else {
        notifier.add(listingId);
        await service.addInterest(listingId);
      }
    } catch (_) {
      // Roll back — and tell the user; a silent rollback looks like the
      // save simply never happened.
      if (isWishlisted) {
        notifier.add(listingId);
      } else {
        notifier.remove(listingId);
      }
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text('Could not update wishlist — please try again')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final wishlistedIds = ref.watch(wishlistIdsProvider);
    final count = listings.length + (truncated ? 1 : 0);
    return ListView.builder(
      padding: EdgeInsets.fromLTRB(16, 8, 16, AppInsets.bottomNav(context)),
      itemCount: count,
      itemBuilder: (_, i) {
        if (truncated && i == listings.length) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              'Showing first $serverCount results — refine filters to see more',
              textAlign: TextAlign.center,
              style: GoogleFonts.josefinSans(
                fontSize: 12,
                color: AppColors.textMuted,
              ),
            ),
          );
        }
        final listing = listings[i];
        final id = listing['id'] as String? ?? '';
        final isWishlisted = wishlistedIds.contains(id);
        return AnimatedListItem(
          index: i,
          child: Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: ListingCard(
              listing: listing,
              onTap: () => context.push('/browse/${listing['slug']}'),
              isWishlisted: isWishlisted,
              onWishlistToggle: id.isEmpty
                  ? null
                  : () => _toggleWishlist(context, ref, id, isWishlisted),
            ),
          ),
        );
      },
    );
  }
}
