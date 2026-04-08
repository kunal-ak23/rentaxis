import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// ── Providers ─────────────────────────────────────────────────────────────────

final _wishlistProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
  final service = ref.watch(listingApiServiceProvider);
  final data = await service.getWishlist(size: 100);
  return (data['content'] as List? ?? []).cast<Map<String, dynamic>>();
});

// ── Screen ────────────────────────────────────────────────────────────────────

class WishlistScreen extends ConsumerStatefulWidget {
  const WishlistScreen({super.key});

  @override
  ConsumerState<WishlistScreen> createState() => _WishlistScreenState();
}

class _WishlistScreenState extends ConsumerState<WishlistScreen> {
  // Track locally removed items for optimistic UI
  final Set<String> _removedIds = {};

  @override
  Widget build(BuildContext context) {
    final wishlistAsync = ref.watch(_wishlistProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        elevation: 0,
        title: Text(
          'Wishlist',
          style: GoogleFonts.cinzel(
            fontSize: 18,
            fontWeight: FontWeight.w700,
            color: AppColors.textPrimary,
          ),
        ),
      ),
      body: wishlistAsync.when(
        loading: () => ListView.builder(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
          itemCount: 4,
          itemBuilder: (_, __) => Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: ShimmerLoading(height: 100, width: double.infinity),
          ),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load wishlist',
          onRetry: () => ref.invalidate(_wishlistProvider),
        ),
        data: (items) {
          final visible = items
              .where((i) => !_removedIds.contains(i['id'] as String?))
              .toList();

          if (visible.isEmpty) {
            return EmptyState(
              icon: Icons.favorite_border,
              title: 'Your wishlist is empty',
              subtitle: 'Browse listings and tap the heart to save them here',
              actionLabel: 'Browse listings',
              onAction: () => context.go('/browse'),
            );
          }

          return RefreshIndicator(
            onRefresh: () async {
              _removedIds.clear();
              ref.invalidate(_wishlistProvider);
            },
            child: ListView.builder(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
              itemCount: visible.length,
              itemBuilder: (_, i) {
                final item = visible[i];
                final id = item['id'] as String? ?? '';
                return AnimatedListItem(
                  index: i,
                  child: Dismissible(
                    key: Key(id),
                    direction: DismissDirection.endToStart,
                    background: Container(
                      alignment: Alignment.centerRight,
                      padding: const EdgeInsets.only(right: 24),
                      margin: const EdgeInsets.only(bottom: 14),
                      decoration: BoxDecoration(
                        color: AppColors.danger,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: const Icon(Icons.delete_outline,
                          color: Colors.white, size: 24),
                    ),
                    onDismissed: (_) => _removeItem(id),
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 14),
                      child: _WishlistItem(
                        listing: item,
                        onTap: () =>
                            context.push('/browse/${item['slug']}'),
                        onRemove: () => _removeItem(id),
                      ),
                    ),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  Future<void> _removeItem(String id) async {
    setState(() => _removedIds.add(id));
    try {
      final service = ref.read(listingApiServiceProvider);
      await service.removeInterest(id);
    } catch (e) {
      setState(() => _removedIds.remove(id));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to remove from wishlist')),
        );
      }
    }
  }
}

// ── Wishlist item card ────────────────────────────────────────────────────────

class _WishlistItem extends StatelessWidget {
  final Map<String, dynamic> listing;
  final VoidCallback onTap;
  final VoidCallback onRemove;

  const _WishlistItem({
    required this.listing,
    required this.onTap,
    required this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? 'Listing';
    final rent = listing['annualRent'] as num?;
    final beds = listing['bedrooms'] as int?;
    final status = listing['status'] as String? ?? '';
    final propertyName = listing['propertyName'] as String?;

    final statusColor = switch (status) {
      'PUBLISHED' => AppColors.success,
      'UPCOMING' => AppColors.accent,
      'UNLISTED' => AppColors.textMuted,
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
            ClipRRect(
              borderRadius:
                  const BorderRadius.horizontal(left: Radius.circular(16)),
              child: coverUrl != null
                  ? Image.network(coverUrl,
                      width: 100, height: 100, fit: BoxFit.cover)
                  : Container(
                      width: 100,
                      height: 100,
                      color: AppColors.background,
                      child: const Icon(Icons.apartment_outlined,
                          color: AppColors.textMuted),
                    ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(12),
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
                                color: AppColors.textPrimary),
                          ),
                        ),
                        StatusBadge(label: status, color: statusColor),
                      ],
                    ),
                    if (propertyName != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        propertyName,
                        maxLines: 1,
                        style: GoogleFonts.josefinSans(
                            fontSize: 12, color: AppColors.textMuted),
                      ),
                    ],
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        if (beds != null) ...[
                          const Icon(Icons.bed_outlined,
                              size: 14, color: AppColors.textMuted),
                          const SizedBox(width: 3),
                          Text('$beds',
                              style: GoogleFonts.josefinSans(
                                  fontSize: 12,
                                  color: AppColors.textSecondary)),
                          const SizedBox(width: 8),
                        ],
                        if (rent != null)
                          Text(
                            Formatters.currencyCompact(rent),
                            style: GoogleFonts.josefinSans(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                              color: AppColors.primary,
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(right: 12),
              child: GestureDetector(
                onTap: onRemove,
                child: const Icon(Icons.favorite,
                    size: 22, color: AppColors.danger),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
