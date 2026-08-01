import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// ── Strings (EN/AR) ─────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المحفوظة' : 'SAVED';
  String get failedToLoad =>
      ar ? 'فشل تحميل المفضلة' : 'Failed to load wishlist';
  String get showingFirst100 =>
      ar ? 'عرض أول 100 قائمة محفوظة' : 'Showing first 100 saved listings';
  String get removeFailed =>
      ar ? 'تعذّرت إزالة القائمة من المفضلة' : 'Failed to remove from wishlist';
  String get emptyTitle => ar ? 'مفضلتك فارغة' : 'YOUR WISHLIST IS EMPTY';
  String get emptySubtitle => ar
      ? 'تصفح القوائم واضغط على القلب لحفظها هنا'
      : 'Browse listings and tap the heart to save them here';
  String get browseListings => ar ? 'تصفح القوائم' : 'Browse listings';
  String get listingFallback => ar ? 'قائمة' : 'Listing';
}

TextStyle _display(
  bool ar, {
  double size = 16,
  double letterSpacing = 0,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: FontWeight.w600,
        color: color,
      )
    : GoogleFonts.cinzel(
        fontSize: size,
        fontWeight: FontWeight.w600,
        letterSpacing: letterSpacing,
        color: color,
      );

// ── Providers ─────────────────────────────────────────────────────────────────

final _wishlistProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(listingApiServiceProvider);
      final items = await service.getWishlist();
      return items.cast<Map<String, dynamic>>();
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
    final m = context.miftah;
    final l = _L(context.isAr);
    final wishlistAsync = ref.watch(_wishlistProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: _display(l.ar, size: 16, letterSpacing: 3.2),
        ),
      ),
      body: wishlistAsync.when(
        loading: () => ListView.builder(
          padding: EdgeInsets.fromLTRB(
            16,
            16,
            16,
            AppInsets.bottomNav(context),
          ),
          itemCount: 4,
          itemBuilder: (_, __) => Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: ShimmerLoading(height: 100, width: double.infinity),
          ),
        ),
        error: (e, _) => ErrorState(
          message: l.failedToLoad,
          onRetry: () => ref.invalidate(_wishlistProvider),
        ),
        data: (items) {
          final visible = items
              .where((i) => !_removedIds.contains(i['id'] as String?))
              .toList();

          if (visible.isEmpty) {
            return _WishlistEmptyState(onBrowse: () => context.go('/browse'));
          }

          final truncated = items.length >= 100;
          return RefreshIndicator(
            onRefresh: () async {
              _removedIds.clear();
              ref.invalidate(_wishlistProvider);
            },
            child: ListView.builder(
              padding: EdgeInsets.fromLTRB(
                16,
                16,
                16,
                AppInsets.bottomNav(context),
              ),
              itemCount: visible.length + (truncated ? 1 : 0),
              itemBuilder: (_, i) {
                if (truncated && i == visible.length) {
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text(
                      l.showingFirst100,
                      textAlign: TextAlign.center,
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: m.textMuted,
                      ),
                    ),
                  );
                }
                final item = visible[i];
                final id = item['id'] as String? ?? '';
                return AnimatedListItem(
                  index: i,
                  child: Dismissible(
                    key: Key(id),
                    direction: DismissDirection.endToStart,
                    background: Container(
                      alignment: AlignmentDirectional.centerEnd,
                      padding: const EdgeInsetsDirectional.only(end: 24),
                      margin: const EdgeInsets.only(bottom: 14),
                      decoration: BoxDecoration(
                        color: AppColors.danger,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: const Icon(
                        Icons.delete_outline,
                        color: Colors.white,
                        size: 24,
                      ),
                    ),
                    onDismissed: (_) => _removeItem(id),
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 14),
                      child: _WishlistItem(
                        listing: item,
                        onTap: () => context.push('/browse/${item['slug']}'),
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
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_L(context.isAr).removeFailed)));
      }
    }
  }
}

// ── Empty state ──────────────────────────────────────────────────────────────

/// "Nothing saved yet" state, styled per design mock 1l: Cinzel heading,
/// muted copy, gold CTA back to Browse.
class _WishlistEmptyState extends StatelessWidget {
  final VoidCallback onBrowse;
  const _WishlistEmptyState({required this.onBrowse});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.favorite_border,
              size: 48,
              color: m.textMuted.withValues(alpha: 0.5),
            ),
            const SizedBox(height: 18),
            Text(
              l.emptyTitle,
              textAlign: TextAlign.center,
              style: _display(
                l.ar,
                size: 17,
                letterSpacing: 1.6,
                color: m.textPrimary,
              ),
            ),
            const SizedBox(height: 12),
            Text(
              l.emptySubtitle,
              textAlign: TextAlign.center,
              style: GoogleFonts.josefinSans(
                fontSize: 13.5,
                height: 1.6,
                color: m.textSecondary,
              ),
            ),
            const SizedBox(height: 24),
            GoldButton(
              label: l.browseListings,
              expanded: false,
              onPressed: onBrowse,
            ),
          ],
        ),
      ),
    );
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
    final m = context.miftah;
    final l = _L(context.isAr);
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? l.listingFallback;
    final rent = listing['annualRent'] as num?;
    final beds = listing['bedrooms'] as int?;
    final status = listing['status'] as String? ?? '';
    final propertyName = listing['propertyName'] as String?;

    final statusColor = switch (status) {
      'PUBLISHED' => AppColors.success,
      'UPCOMING' => AppColors.accent,
      'UNLISTED' => const Color(0xFFF59E0B), // amber
      _ => m.textMuted,
    };

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: m.border),
        ),
        child: Row(
          children: [
            ClipRRect(
              // Directional so the outer corner mirrors correctly in RTL.
              borderRadius: const BorderRadiusDirectional.horizontal(
                start: Radius.circular(16),
              ),
              child: coverUrl != null
                  ? Image.network(
                      coverUrl,
                      width: 100,
                      height: 100,
                      fit: BoxFit.cover,
                    )
                  : Container(
                      width: 100,
                      height: 100,
                      color: m.background,
                      child: Icon(Icons.apartment_outlined, color: m.textMuted),
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
                              color: m.textPrimary,
                            ),
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
                          fontSize: 12,
                          color: m.textMuted,
                        ),
                      ),
                    ],
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        if (beds != null) ...[
                          Icon(
                            Icons.bed_outlined,
                            size: 14,
                            color: m.textMuted,
                          ),
                          const SizedBox(width: 3),
                          Text(
                            '$beds',
                            style: GoogleFonts.josefinSans(
                              fontSize: 12,
                              color: m.textSecondary,
                            ),
                          ),
                          const SizedBox(width: 8),
                        ],
                        if (rent != null)
                          Text(
                            Formatters.currencyCompact(rent),
                            style: GoogleFonts.josefinSans(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                              color: m.isDark
                                  ? AppColors.accent
                                  : AppColors.primary,
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsetsDirectional.only(end: 12),
              child: GestureDetector(
                onTap: onRemove,
                child: const Icon(
                  Icons.favorite,
                  size: 22,
                  color: AppColors.danger,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
