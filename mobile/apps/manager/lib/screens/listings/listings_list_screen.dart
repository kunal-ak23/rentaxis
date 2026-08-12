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

// ── Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief. ─

class _L {
  _L(this.ar);
  final bool ar;

  String get listings => ar ? 'الإعلانات العقارية' : 'LISTINGS';
  String get newListing => ar ? 'إعلان جديد' : 'New listing';
  String get loadFailed =>
      ar ? 'فشل تحميل الإعلانات' : 'Failed to load listings';
  String get noListingsTitle => ar ? 'لا توجد إعلانات بعد' : 'No listings yet';
  String get noListingsDesc => ar
      ? 'أنشئ أول إعلان عقاري لك للبدء'
      : 'Create your first listing to get started';
  String showingFirst(int n) =>
      ar ? 'عرض أول $n إعلان' : 'Showing first $n listings';
  String get listing => ar ? 'إعلان' : 'Listing';

  String status(String value) => switch (value) {
    'PUBLISHED' => ar ? 'منشور' : 'PUBLISHED',
    'UPCOMING' => ar ? 'قادم' : 'UPCOMING',
    'UNLISTED' => ar ? 'غير منشور' : 'UNLISTED',
    'DRAFT' => ar ? 'مسودة' : 'DRAFT',
    _ => value.replaceAll('_', ' '),
  };
}

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingsListScreen extends ConsumerWidget {
  const ListingsListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final listingsAsync = ref.watch(_listingsProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ChromeHeader(l: l),
          Expanded(
            child: listingsAsync.when(
              loading: () => ListView.builder(
                padding: EdgeInsets.fromLTRB(
                  16,
                  16,
                  16,
                  AppInsets.bottomNav(context),
                ),
                itemCount: 5,
                itemBuilder: (_, _) => Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: ShimmerLoading(height: 100, width: double.infinity),
                ),
              ),
              error: (e, _) => ErrorState(
                message: l.loadFailed,
                onRetry: () => ref.invalidate(_listingsProvider),
              ),
              data: (items) {
                if (items.isEmpty) {
                  return EmptyState(
                    icon: Icons.apartment_outlined,
                    title: l.noListingsTitle,
                    subtitle: l.noListingsDesc,
                  );
                }
                final truncated = items.length >= 100;
                return RefreshIndicator(
                  color: AppColors.accent,
                  onRefresh: () async => ref.invalidate(_listingsProvider),
                  child: ListView.builder(
                    padding: EdgeInsets.fromLTRB(
                      16,
                      16,
                      16,
                      AppInsets.bottomNav(context),
                    ),
                    itemCount: items.length + (truncated ? 1 : 0),
                    itemBuilder: (_, i) {
                      if (truncated && i == items.length) {
                        return Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: Text(
                            l.showingFirst(100),
                            textAlign: TextAlign.center,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 12,
                                    color: m.textMuted,
                                  )
                                : GoogleFonts.plusJakartaSans(
                                    fontSize: 12,
                                    color: m.textMuted,
                                  ),
                          ),
                        );
                      }
                      return AnimatedListItem(
                        index: i,
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: _ListingRow(
                            listing: items[i],
                            l: l,
                            onTap: () =>
                                context.push('/listings/${items[i]['id']}'),
                            onInterests: () => context.push(
                              '/listings/${items[i]['id']}/interests',
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/listings/new'),
        backgroundColor: AppColors.primary,
        icon: const Icon(Icons.add, color: AppColors.accent),
        label: Text(
          l.newListing,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  color: AppColors.accent,
                  fontWeight: FontWeight.w600,
                )
              : GoogleFonts.plusJakartaSans(
                  color: AppColors.accent,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.6,
                ),
        ),
      ),
    );
  }
}

// ── Chrome header ─────────────────────────────────────────────────────────────

class _ChromeHeader extends StatelessWidget {
  final _L l;
  const _ChromeHeader({required this.l});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 14, 20, 18),
          child: Text(
            l.listings,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 17,
                    fontWeight: FontWeight.w600,
                    color: Colors.white,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 16,
                    letterSpacing: 3.2,
                    color: Colors.white,
                  ),
          ),
        ),
      ),
    );
  }
}

// ── Row card ──────────────────────────────────────────────────────────────────

class _ListingRow extends StatelessWidget {
  final Map<String, dynamic> listing;
  final _L l;
  final VoidCallback onTap;
  final VoidCallback onInterests;

  const _ListingRow({
    required this.listing,
    required this.l,
    required this.onTap,
    required this.onInterests,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? l.listing;
    final status = listing['status'] as String? ?? 'DRAFT';
    final rent = listing['annualRent'] as num?;
    final interests = listing['interestsCount'] as int? ?? 0;
    final createdAt = listing['createdAt'] as String?;

    final statusColor = switch (status) {
      'PUBLISHED' => m.success,
      'UPCOMING' => AppColors.accent,
      'UNLISTED' => m.warning,
      'DRAFT' => m.textMuted,
      _ => m.textMuted,
    };
    final isDraft = status == 'DRAFT';

    // A rounded border can't mix colors per side, so the status accent is an
    // inner strip clipped to the card's radius instead of a left BorderSide.
    return Container(
      decoration: BoxDecoration(
        color: isDraft ? m.surfaceDim : m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Opacity(
        opacity: isDraft ? 0.85 : 1,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            child: Stack(
              children: [
                PositionedDirectional(
                  start: 0,
                  top: 0,
                  bottom: 0,
                  child: Container(width: 2, color: statusColor),
                ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Cover thumbnail
                    Padding(
                      padding: const EdgeInsetsDirectional.only(start: 2),
                      child: ClipRRect(
                        borderRadius: const BorderRadiusDirectional.horizontal(
                          start: Radius.circular(12),
                        ),
                        child: coverUrl != null
                            ? Image.network(
                                coverUrl,
                                width: 90,
                                height: 90,
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) => _Placeholder(m: m),
                              )
                            : _Placeholder(m: m),
                      ),
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
                                    style: l.ar
                                        ? GoogleFonts.notoNaskhArabic(
                                            fontSize: 14.5,
                                            fontWeight: FontWeight.w600,
                                            color: m.textPrimary,
                                          )
                                        : GoogleFonts.plusJakartaSans(
                                            fontSize: 14,
                                            fontWeight: FontWeight.w600,
                                            color: m.textPrimary,
                                          ),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                _StatusPill(
                                  label: l.status(status),
                                  color: statusColor,
                                  ar: l.ar,
                                ),
                              ],
                            ),
                            const SizedBox(height: 5),
                            if (rent != null)
                              Text(
                                Formatters.currencyCompact(rent),
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                  color: AppColors.accentDark,
                                ),
                              ),
                            const SizedBox(height: 5),
                            Row(
                              children: [
                                if (createdAt != null)
                                  Text(
                                    Formatters.timeAgo(createdAt, ar: l.ar),
                                    style: GoogleFonts.plusJakartaSans(
                                      fontSize: 11,
                                      color: m.textMuted,
                                    ),
                                  ),
                                const Spacer(),
                                // Interests badge
                                GestureDetector(
                                  onTap: onInterests,
                                  child: Row(
                                    children: [
                                      Icon(
                                        Icons.people_outline,
                                        size: 15,
                                        color: m.textSecondary,
                                      ),
                                      const SizedBox(width: 3),
                                      Text(
                                        '$interests',
                                        style: GoogleFonts.plusJakartaSans(
                                          fontSize: 12,
                                          color: m.textSecondary,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Icon(
                                  Icons.chevron_right,
                                  size: 18,
                                  color: m.textMuted,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  final bool ar;

  const _StatusPill({
    required this.label,
    required this.color,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.plusJakartaSans(
                fontSize: 9.5,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.0,
                color: color,
              ),
      ),
    );
  }
}

class _Placeholder extends StatelessWidget {
  final LegacyMiftahColors m;
  const _Placeholder({required this.m});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 90,
      height: 90,
      color: m.surfaceAlt,
      child: Icon(Icons.apartment_outlined, color: m.textMuted),
    );
  }
}
