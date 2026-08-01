import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/l10n.dart';
import 'distance_chip.dart';
import 'price_label.dart';
import 'shimmer_loading.dart';

/// Screen strings (EN/AR). Lightweight per-widget pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get listingFallback => ar ? 'قائمة' : 'Listing';
  String get statusLive => ar ? 'متاح' : 'Live';
  String get statusUpcoming => ar ? 'قادم' : 'Upcoming';
  String get statusUnlisted => ar ? 'غير مدرج' : 'Unlisted';
  String get statusDraft => ar ? 'مسودة' : 'Draft';
}

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
    final l = _L(context.isAr);
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? l.listingFallback;
    final propertyName = listing['propertyName'] as String?;
    final beds = listing['bedrooms'] as int?;
    final rent = listing['annualRent'] as num?;
    final status = listing['status'] as String? ?? '';
    final interestsCount = listing['interestsCount'] as int? ?? 0;

    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: m.border),
          boxShadow: m.isDark ? null : AppShadows.soft,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Cover image
            Stack(
              children: [
                ClipRRect(
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(18),
                  ),
                  child: coverUrl != null
                      ? CachedNetworkImage(
                          imageUrl: coverUrl,
                          height: 180,
                          width: double.infinity,
                          fit: BoxFit.cover,
                          placeholder: (_, __) => ShimmerLoading(
                            height: 180,
                            width: double.infinity,
                          ),
                          errorWidget: (_, __, ___) => _PlaceholderImage(),
                        )
                      : _PlaceholderImage(),
                ),
                // Status badge, leading edge
                PositionedDirectional(
                  top: 10,
                  start: 10,
                  child: _StatusPill(status: status, l: l),
                ),
                // Wishlist heart, trailing edge
                if (onWishlistToggle != null)
                  PositionedDirectional(
                    top: 8,
                    end: 8,
                    child: _WishlistHeart(
                      isWishlisted: isWishlisted ?? false,
                      onTap: onWishlistToggle!,
                    ),
                  ),
                // Distance chip, leading edge
                if (distanceKm != null)
                  PositionedDirectional(
                    bottom: 8,
                    start: 10,
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
                      color: m.textPrimary,
                    ),
                  ),
                  if (propertyName != null) ...[
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        Icon(
                          Icons.location_on_outlined,
                          size: 13,
                          color: m.textMuted,
                        ),
                        const SizedBox(width: 2),
                        Expanded(
                          child: Text(
                            propertyName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: GoogleFonts.josefinSans(
                              fontSize: 12,
                              color: m.textMuted,
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
                        Icon(Icons.bed_outlined, size: 15, color: m.textMuted),
                        const SizedBox(width: 3),
                        Text(
                          '$beds',
                          style: GoogleFonts.josefinSans(
                            fontSize: 12,
                            color: m.textSecondary,
                          ),
                        ),
                        const SizedBox(width: 10),
                      ],
                      if (interestsCount > 0) ...[
                        Icon(
                          Icons.favorite_border,
                          size: 14,
                          color: m.textMuted,
                        ),
                        const SizedBox(width: 3),
                        Text(
                          '$interestsCount',
                          style: GoogleFonts.josefinSans(
                            fontSize: 12,
                            color: m.textSecondary,
                          ),
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
    final m = context.miftah;
    return Container(
      height: 180,
      width: double.infinity,
      color: m.surfaceAlt,
      child: Icon(Icons.apartment_outlined, size: 48, color: m.textMuted),
    );
  }
}

class _StatusPill extends StatelessWidget {
  final String status;
  final _L l;
  const _StatusPill({required this.status, required this.l});

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      'PUBLISHED' => (l.statusLive, AppColors.success),
      'UPCOMING' => (l.statusUpcoming, AppColors.accent),
      'UNLISTED' => (l.statusUnlisted, const Color(0xFFF59E0B)), // amber
      'DRAFT' => (l.statusDraft, AppColors.textMuted),
      _ => (status, AppColors.textMuted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: GoogleFonts.josefinSans(
          fontSize: 10,
          color: Colors.white,
          fontWeight: FontWeight.w600,
        ),
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
