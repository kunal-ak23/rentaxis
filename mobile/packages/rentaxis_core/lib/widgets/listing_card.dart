import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/formatters.dart';
import 'distance_chip.dart';
import 'price_label.dart';
import 'shimmer_loading.dart';
import 'status_badge.dart';

/// Card showing a `UnitListingSummaryDTO` in browse/list views.
/// Pass [distanceKm] to show the distance chip.
/// Pass [onWishlistToggle] + [isWishlisted] for the heart icon.
class ListingCard extends StatelessWidget {
  final Map<String, dynamic> listing;
  final VoidCallback onTap;
  final double? distanceKm;
  final bool? isWishlisted;
  final VoidCallback? onWishlistToggle;

  const ListingCard({
    super.key,
    required this.listing,
    required this.onTap,
    this.distanceKm,
    this.isWishlisted,
    this.onWishlistToggle,
  });

  @override
  Widget build(BuildContext context) {
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? 'Listing';
    final propertyName = listing['propertyName'] as String?;
    final beds = listing['bedrooms'] as int?;
    final rent = listing['annualRent'] as num?;
    final status = listing['status'] as String? ?? '';
    final interestsCount = listing['interestsCount'] as int? ?? 0;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(18),
          boxShadow: AppShadows.soft,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Cover image
            Stack(
              children: [
                ClipRRect(
                  borderRadius:
                      const BorderRadius.vertical(top: Radius.circular(18)),
                  child: coverUrl != null
                      ? CachedNetworkImage(
                          imageUrl: coverUrl,
                          height: 180,
                          width: double.infinity,
                          fit: BoxFit.cover,
                          placeholder: (_, __) =>
                              ShimmerLoading(height: 180, width: double.infinity),
                          errorWidget: (_, __, ___) => _PlaceholderImage(),
                        )
                      : _PlaceholderImage(),
                ),
                // Status badge top-left
                Positioned(
                  top: 10,
                  left: 10,
                  child: _StatusPill(status: status),
                ),
                // Wishlist heart top-right
                if (onWishlistToggle != null)
                  Positioned(
                    top: 8,
                    right: 8,
                    child: _WishlistHeart(
                      isWishlisted: isWishlisted ?? false,
                      onTap: onWishlistToggle!,
                    ),
                  ),
                // Distance chip bottom-left
                if (distanceKm != null)
                  Positioned(
                    bottom: 8,
                    left: 10,
                    child: DistanceChip(km: distanceKm!),
                  ),
              ],
            ),

            // Info section
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: GoogleFonts.josefinSans(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  if (propertyName != null) ...[
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        const Icon(Icons.location_on_outlined,
                            size: 13, color: AppColors.textMuted),
                        const SizedBox(width: 2),
                        Expanded(
                          child: Text(
                            propertyName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: GoogleFonts.josefinSans(
                              fontSize: 12,
                              color: AppColors.textMuted,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      if (beds != null) ...[
                        const Icon(Icons.bed_outlined,
                            size: 15, color: AppColors.textMuted),
                        const SizedBox(width: 3),
                        Text(
                          '$beds',
                          style: GoogleFonts.josefinSans(
                              fontSize: 12, color: AppColors.textSecondary),
                        ),
                        const SizedBox(width: 10),
                      ],
                      if (interestsCount > 0) ...[
                        const Icon(Icons.favorite_border,
                            size: 14, color: AppColors.textMuted),
                        const SizedBox(width: 3),
                        Text(
                          '$interestsCount',
                          style: GoogleFonts.josefinSans(
                              fontSize: 12, color: AppColors.textSecondary),
                        ),
                        const SizedBox(width: 10),
                      ],
                      const Spacer(),
                      if (rent != null) PriceLabel(annualRent: rent.toDouble()),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PlaceholderImage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      height: 180,
      width: double.infinity,
      color: AppColors.background,
      child: const Icon(Icons.apartment_outlined,
          size: 48, color: AppColors.textMuted),
    );
  }
}

class _StatusPill extends StatelessWidget {
  final String status;
  const _StatusPill({required this.status});

  @override
  Widget build(BuildContext context) {
    if (status == 'PUBLISHED') return const SizedBox.shrink();
    final color = switch (status) {
      'UPCOMING' => AppColors.accent,
      'DRAFT' => AppColors.textMuted,
      _ => AppColors.textMuted,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        status,
        style: GoogleFonts.josefinSans(
            fontSize: 10, color: Colors.white, fontWeight: FontWeight.w600),
      ),
    );
  }
}

class _WishlistHeart extends StatelessWidget {
  final bool isWishlisted;
  final VoidCallback onTap;
  const _WishlistHeart({required this.isWishlisted, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 34,
        height: 34,
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.35),
          shape: BoxShape.circle,
        ),
        child: Icon(
          isWishlisted ? Icons.favorite : Icons.favorite_border,
          size: 18,
          color: isWishlisted ? AppColors.danger : Colors.white,
        ),
      ),
    );
  }
}
