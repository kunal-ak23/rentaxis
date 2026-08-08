import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'browse_filters.dart';
import 'browse_filters_sheet.dart';
import 'browse_map.dart';

// ── Strings (EN/AR) ─────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get searchHint => ar ? 'ابحث عن قوائم…' : 'Search listings…';
  String get listView => ar ? 'عرض القائمة' : 'List view';
  String get mapView => ar ? 'عرض الخريطة' : 'Map view';
  String get filters => ar ? 'الفلاتر' : 'Filters';
  String get failedToLoad =>
      ar ? 'فشل تحميل القوائم' : 'Failed to load listings';
  String get noListingsFound => ar ? 'لا توجد قوائم' : 'No listings found';
  String get tryAdjustingFilters =>
      ar ? 'حاول تعديل الفلاتر' : 'Try adjusting your filters';
  String get bedSuffix => ar ? '+ غرفة' : '+ bed';
  String get availableNow => ar ? 'متاح الآن' : 'Available now';
  String get nearMe => ar ? 'بالقرب مني' : 'Near me';
  String kmSuffix(int km) => ar ? ' ($km كم)' : ' ($km km)';
  String showingFirst(int count) => ar
      ? 'عرض أول $count نتيجة — عدّل الفلاتر لرؤية المزيد'
      : 'Showing first $count results — refine filters to see more';
  String get wishlistUpdateFailed => ar
      ? 'تعذّر تحديث المفضلة — حاول مرة أخرى'
      : 'Could not update wishlist — please try again';
  String get furnishingUnfurnished => ar ? 'غير مفروش' : 'Unfurnished';
  String get furnishingSemi => ar ? 'مفروش جزئياً' : 'Semi furnished';
  String get furnishingFully => ar ? 'مفروش بالكامل' : 'Fully furnished';

  String furnishingLabel(String value) => switch (value) {
    'UNFURNISHED' => furnishingUnfurnished,
    'SEMI_FURNISHED' => furnishingSemi,
    'FULLY_FURNISHED' => furnishingFully,
    _ => value.replaceAll('_', ' ').toLowerCase(),
  };
}

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
    final m = context.miftah;
    final l = _L(context.isAr);
    final listingsAsync = ref.watch(browseListingsProvider);
    final filters = ref.watch(browseFiltersProvider);
    final activeFilterCount = filters.activeCount;

    return Scaffold(
      backgroundColor: m.background,
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
                  message: l.failedToLoad,
                  onRetry: () => ref.invalidate(browseListingsProvider),
                ),
                data: (data) {
                  final allItems = (data['content'] as List? ?? [])
                      .cast<Map<String, dynamic>>();
                  final items = _applySearch(allItems);
                  if (items.isEmpty) {
                    return EmptyState(
                      icon: Icons.apartment_outlined,
                      title: l.noListingsFound,
                      subtitle: l.tryAdjustingFilters,
                    );
                  }
                  // Cross-fade the list↔map mode switch instead of a hard cut
                  // (same AnimatedSwitcher pattern as shell_screen).
                  return AnimatedSwitcher(
                    duration: const Duration(milliseconds: 250),
                    child: _mapMode
                        ? BrowseMap(key: const ValueKey('map'), listings: items)
                        : RefreshIndicator(
                            key: const ValueKey('list'),
                            onRefresh: () async =>
                                ref.invalidate(browseListingsProvider),
                            child: _ListingListView(
                              listings: items,
                              truncated: allItems.length >= 50,
                              serverCount: allItems.length,
                            ),
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
    final m = context.miftah;
    final l = _L(context.isAr);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
      child: Row(
        children: [
          Expanded(
            child: Container(
              height: 48,
              decoration: BoxDecoration(
                color: m.surface,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: m.border),
              ),
              child: TextField(
                controller: controller,
                style: GoogleFonts.josefinSans(
                  fontSize: 14,
                  color: m.textPrimary,
                ),
                decoration: InputDecoration(
                  hintText: l.searchHint,
                  hintStyle: GoogleFonts.josefinSans(
                    fontSize: 14,
                    color: m.textMuted,
                  ),
                  prefixIcon: Icon(Icons.search, size: 20, color: m.textMuted),
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
            tooltip: mapMode ? l.listView : l.mapView,
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
              tooltip: l.filters,
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
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: m.border),
        ),
        child: Icon(icon, size: 20, color: m.textSecondary),
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
    final l = _L(context.isAr);
    final chips = <_ChipData>[];
    if (filters.minBedrooms != null) {
      chips.add(
        _ChipData(
          '${filters.minBedrooms}${l.bedSuffix}',
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearMinBedrooms: true),
        ),
      );
    }
    if (filters.furnishing != null) {
      chips.add(
        _ChipData(
          l.furnishingLabel(filters.furnishing!),
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearFurnishing: true),
        ),
      );
    }
    if (filters.availableNow == true) {
      chips.add(
        _ChipData(
          l.availableNow,
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearAvailableNow: true),
        ),
      );
    }
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
    if (filters.nearLat != null) {
      chips.add(
        _ChipData(
          '${l.nearMe}${filters.radiusKm != null ? l.kmSuffix(filters.radiusKm!.round()) : ''}',
          () => ref.read(browseFiltersProvider.notifier).state = filters
              .copyWith(clearNearby: true),
        ),
      );
    }
    if (chips.isEmpty) return const SizedBox.shrink();

    return SizedBox(
      height: 36,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: chips.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
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
                color: AppColors.accent,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(width: 4),
            const Icon(Icons.close, size: 14, color: AppColors.accent),
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

  Future<void> _toggleWishlist(
    BuildContext context,
    WidgetRef ref,
    String listingId,
    bool isWishlisted,
  ) async {
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
          SnackBar(content: Text(_L(context.isAr).wishlistUpdateFailed)),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
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
              l.showingFirst(serverCount),
              textAlign: TextAlign.center,
              style: GoogleFonts.josefinSans(fontSize: 12, color: m.textMuted),
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
