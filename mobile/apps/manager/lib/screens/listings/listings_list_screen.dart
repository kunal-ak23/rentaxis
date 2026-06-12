import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// ── Providers ─────────────────────────────────────────────────────────────────

final _listingsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(listingApiServiceProvider);
      final data = await service.getListings(size: 100);
      return (data['content'] as List? ?? []).cast<Map<String, dynamic>>();
    });

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingsListScreen extends ConsumerWidget {
  const ListingsListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final listingsAsync = ref.watch(_listingsProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        elevation: 0,
        title: Text(
          'Listings',
          style: GoogleFonts.cinzel(
            fontSize: 18,
            fontWeight: FontWeight.w700,
            color: AppColors.textPrimary,
          ),
        ),
      ),
      body: listingsAsync.when(
        loading: () => ListView.builder(
          padding: EdgeInsets.fromLTRB(16, 16, 16, AppInsets.bottomNav(context)),
          itemCount: 5,
          itemBuilder: (_, __) => Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: ShimmerLoading(height: 100, width: double.infinity),
          ),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load listings',
          onRetry: () => ref.invalidate(_listingsProvider),
        ),
        data: (items) {
          if (items.isEmpty) {
            return EmptyState(
              icon: Icons.apartment_outlined,
              title: 'No listings yet',
              subtitle: 'Create your first listing to get started',
            );
          }
          final truncated = items.length >= 100;
          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(_listingsProvider),
            child: ListView.builder(
              padding: EdgeInsets.fromLTRB(16, 16, 16, AppInsets.bottomNav(context)),
              itemCount: items.length + (truncated ? 1 : 0),
              itemBuilder: (_, i) {
                if (truncated && i == items.length) {
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text(
                      'Showing first 100 listings',
                      textAlign: TextAlign.center,
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textMuted,
                      ),
                    ),
                  );
                }
                return AnimatedListItem(
                  index: i,
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 14),
                    child: _ListingRow(
                      listing: items[i],
                      onTap: () => context.push('/listings/${items[i]['id']}'),
                      onInterests: () =>
                          context.push('/listings/${items[i]['id']}/interests'),
                    ),
                  ),
                );
              },
            ),
          );
        },
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/listings/new'),
        backgroundColor: AppColors.primary,
        icon: const Icon(Icons.add, color: Colors.white),
        label: Text(
          'New listing',
          style: GoogleFonts.josefinSans(
            color: Colors.white,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

// ── Row card ──────────────────────────────────────────────────────────────────

class _ListingRow extends StatelessWidget {
  final Map<String, dynamic> listing;
  final VoidCallback onTap;
  final VoidCallback onInterests;

  const _ListingRow({
    required this.listing,
    required this.onTap,
    required this.onInterests,
  });

  @override
  Widget build(BuildContext context) {
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? 'Listing';
    final status = listing['status'] as String? ?? 'DRAFT';
    final rent = listing['annualRent'] as num?;
    final interests = listing['interestsCount'] as int? ?? 0;
    final createdAt = listing['createdAt'] as String?;

    final statusColor = switch (status) {
      'PUBLISHED' => AppColors.success,
      'UPCOMING' => AppColors.accent,
      'UNLISTED' => const Color(0xFFF59E0B), // amber
      'DRAFT' => AppColors.textMuted,
      _ => AppColors.textMuted,
    };

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(16),
          boxShadow: AppShadows.soft,
        ),
        child: Row(
          children: [
            // Cover thumbnail
            ClipRRect(
              borderRadius: const BorderRadius.horizontal(
                left: Radius.circular(16),
              ),
              child: coverUrl != null
                  ? Image.network(
                      coverUrl,
                      width: 90,
                      height: 90,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => _Placeholder(),
                    )
                  : _Placeholder(),
            ),

            // Info
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: GoogleFonts.josefinSans(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              color: AppColors.textPrimary,
                            ),
                          ),
                        ),
                        StatusBadge(label: status, color: statusColor),
                      ],
                    ),
                    const SizedBox(height: 4),
                    if (rent != null)
                      Text(
                        Formatters.currencyCompact(rent),
                        style: GoogleFonts.josefinSans(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: AppColors.primary,
                        ),
                      ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        if (createdAt != null)
                          Text(
                            Formatters.timeAgo(createdAt),
                            style: GoogleFonts.josefinSans(
                              fontSize: 11,
                              color: AppColors.textMuted,
                            ),
                          ),
                        const Spacer(),
                        // Interests badge
                        GestureDetector(
                          onTap: onInterests,
                          child: Row(
                            children: [
                              const Icon(
                                Icons.people_outline,
                                size: 15,
                                color: AppColors.textSecondary,
                              ),
                              const SizedBox(width: 3),
                              Text(
                                '$interests',
                                style: GoogleFonts.josefinSans(
                                  fontSize: 12,
                                  color: AppColors.textSecondary,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 8),
                        const Icon(
                          Icons.chevron_right,
                          size: 18,
                          color: AppColors.textMuted,
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
    );
  }
}

class _Placeholder extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      width: 90,
      height: 90,
      color: AppColors.background,
      child: const Icon(Icons.apartment_outlined, color: AppColors.textMuted),
    );
  }
}
