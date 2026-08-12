import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:photo_view/photo_view.dart';
import 'package:photo_view/photo_view_gallery.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

// ── Strings (EN/AR) ─────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get tenantNotConfigured =>
      ar ? 'المستأجر غير مهيّأ' : 'Tenant not configured';
  String get failedToLoad =>
      ar ? 'فشل تحميل القائمة' : 'Failed to load listing';
  String get listingFallback => ar ? 'قائمة' : 'Listing';
  String failedToUpdateWishlist(Object e) =>
      ar ? 'تعذّر تحديث المفضلة: $e' : 'Failed to update wishlist: $e';

  String get description => ar ? 'الوصف' : 'Description';
  String get amenities => ar ? 'المرافق' : 'Amenities';
  String get location => ar ? 'الموقع' : 'Location';
  String get getDirections => ar ? 'الاتجاهات' : 'Get directions';
  String get moreMedia => ar ? 'المزيد من الوسائط' : 'More media';
  String get floorPlan => ar ? 'مخطط الطابق' : 'Floor plan';
  String get videoTour => ar ? 'جولة فيديو' : 'Video tour';
  String get tour360 => ar ? 'جولة 360°' : '360° tour';

  String get beds => ar ? 'غرف' : 'BEDS';
  String get baths => ar ? 'حمامات' : 'BATHS';
  String get sqft => ar ? 'قدم مربع' : 'SQ FT';
  String get parking => ar ? 'موقف' : 'PARK';

  String get perYear => ar ? '/سنة' : '/year';
  String get yrSuffix => ar ? '/سنة' : '/yr';
  String get from => ar ? 'ابتداءً من' : 'FROM';
  String get scheduleVisit => ar ? 'حجز موعد معاينة' : 'Schedule a visit';

  static const _viewsAr = {
    'sea': 'بحرية',
    'city': 'على المدينة',
    'garden': 'على الحديقة',
    'pool': 'على المسبح',
    'community': 'على المجمّع',
    'golf': 'على ملعب الجولف',
    'canal': 'على القناة',
    'street': 'على الشارع',
    'road': 'على الشارع',
  };

  String viewLabel(String viewType) =>
      ar ? 'إطلالة ${_viewsAr[viewType] ?? 'مميزة'}' : '$viewType view';
  String availableFrom(String date) => ar ? 'من $date' : 'From $date';
  String depositLabel(String amount) => ar ? 'التأمين $amount' : 'Dep. $amount';
  String chequesLabel(int n) =>
      ar ? '$n شيكات' : '$n cheque${n != 1 ? 's' : ''}';

  String get furnishingUnfurnished => ar ? 'غير مفروش' : 'unfurnished';
  String get furnishingSemi => ar ? 'مفروش جزئياً' : 'semi furnished';
  String get furnishingFully => ar ? 'مفروش بالكامل' : 'fully furnished';

  String furnishingLabel(String value) => switch (value) {
    'UNFURNISHED' => furnishingUnfurnished,
    'SEMI_FURNISHED' => furnishingSemi,
    'FULLY_FURNISHED' => furnishingFully,
    _ => value.replaceAll('_', ' ').toLowerCase(),
  };
}

/// Wide-tracked display heading — Cinzel (EN) / Noto Naskh Arabic (AR),
/// dropping letter-spacing for AR per arabic-brief typography rules.
TextStyle _display(
  bool ar, {
  double fontSize = 16,
  double letterSpacing = 0.8,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: fontSize + 1,
        fontWeight: FontWeight.w600,
        color: color,
      )
    : GoogleFonts.cinzel(
        fontSize: fontSize,
        fontWeight: FontWeight.w600,
        letterSpacing: letterSpacing,
        color: color,
      );

/// Wide-tracked overline label — Josefin Sans, dropping letter-spacing for AR.
TextStyle _overline(
  bool ar, {
  double fontSize = 11,
  double letterSpacing = 2.0,
  Color? color,
}) => GoogleFonts.josefinSans(
  fontSize: fontSize,
  fontWeight: FontWeight.w500,
  letterSpacing: ar ? 0 : letterSpacing,
  color: color,
);

// ── Providers ─────────────────────────────────────────────────────────────────

final _listingDetailProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, _DetailParams>((ref, params) async {
      final service = ref.watch(listingApiServiceProvider);
      return service.getMarketplaceListing(params.tenantSlug, params.slug);
    });

class _DetailParams {
  final String tenantSlug;
  final String slug;
  const _DetailParams(this.tenantSlug, this.slug);
  @override
  bool operator ==(Object o) =>
      o is _DetailParams && tenantSlug == o.tenantSlug && slug == o.slug;
  @override
  int get hashCode => Object.hash(tenantSlug, slug);
}

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingDetailScreen extends ConsumerWidget {
  final String slug;
  const ListingDetailScreen({super.key, required this.slug});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);
    final tenantSlug = resolveTenantSlug(authState);

    if (tenantSlug == null) {
      return Scaffold(
        body: Center(child: Text(_L(context.isAr).tenantNotConfigured)),
      );
    }

    final detailAsync = ref.watch(
      _listingDetailProvider(_DetailParams(tenantSlug, slug)),
    );

    return detailAsync.when(
      loading: () => Scaffold(
        appBar: AppBar(backgroundColor: Colors.transparent),
        body: Center(child: CircularProgressIndicator(color: AppColors.accent)),
      ),
      error: (e, _) => Scaffold(
        appBar: AppBar(),
        body: ErrorState(
          message: _L(context.isAr).failedToLoad,
          onRetry: () => ref.invalidate(
            _listingDetailProvider(_DetailParams(tenantSlug, slug)),
          ),
        ),
      ),
      data: (listing) => _ListingDetailView(listing: listing),
    );
  }
}

// ── Detail view ───────────────────────────────────────────────────────────────

class _ListingDetailView extends ConsumerStatefulWidget {
  final Map<String, dynamic> listing;
  const _ListingDetailView({required this.listing});

  @override
  ConsumerState<_ListingDetailView> createState() => _ListingDetailViewState();
}

class _ListingDetailViewState extends ConsumerState<_ListingDetailView> {
  bool _wishlistLoading = false;
  late final List<Map<String, dynamic>> _photos;

  String get _listingId => widget.listing['id'] as String? ?? '';

  @override
  void initState() {
    super.initState();
    final media = (widget.listing['media'] as List? ?? [])
        .cast<Map<String, dynamic>>();
    _photos = media.where((m) => m['mediaType'] == 'PHOTO').toList();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final strings = _L(context.isAr);
    final l = widget.listing;
    final status = l['status'] as String? ?? '';
    final isUpcoming = status == 'UPCOMING';
    // Derive initial wishlist state from the shared provider (loaded at startup)
    final wishlisted = ref.watch(wishlistIdsProvider).contains(_listingId);

    return Scaffold(
      backgroundColor: m.background,
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: GestureDetector(
          onTap: () => context.pop(),
          child: Container(
            margin: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: const Color(0xFF111111).withValues(alpha: 0.72),
              shape: BoxShape.circle,
              border: Border.all(
                color: AppColors.accent.withValues(alpha: 0.4),
              ),
            ),
            child: const Icon(
              Icons.arrow_back,
              color: AppColors.accent,
              size: 20,
            ),
          ),
        ),
      ),
      body: Stack(
        children: [
          CustomScrollView(
            slivers: [
              SliverToBoxAdapter(
                child: _HeroCarousel(photos: _photos, listing: l),
              ),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, 20, 16, 24),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
                    _QuickFacts(listing: l),
                    const SizedBox(height: 20),
                    if (l['descriptionEn'] != null) ...[
                      _SectionTitle(strings.description),
                      const SizedBox(height: 8),
                      Text(
                        l['descriptionEn'] as String,
                        style: GoogleFonts.josefinSans(
                          fontSize: 14,
                          color: m.textSecondary,
                          height: 1.6,
                        ),
                      ),
                      const SizedBox(height: 20),
                    ],
                    _AmenitiesGrid(
                      amenities: (l['amenities'] as List? ?? [])
                          .cast<Map<String, dynamic>>(),
                    ),
                    const SizedBox(height: 20),
                    _MapCard(listing: l),
                    const SizedBox(height: 20),
                    _MediaLinks(listing: l),
                  ]),
                ),
              ),
            ],
          ),
          // Save / Notify me FAB — positioned directly to avoid Scaffold FAB
          // placement issues when nested inside a ShellRoute with extendBody:true
          PositionedDirectional(
            end: 16,
            bottom: 108,
            child: FloatingActionButton(
              onPressed: _wishlistLoading
                  ? null
                  : () => _toggleWishlist(wishlisted),
              backgroundColor: isUpcoming
                  ? AppColors.accent
                  : AppColors.primary,
              elevation: 4,
              child: _wishlistLoading
                  ? const SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(
                        color: Colors.white,
                        strokeWidth: 2.5,
                      ),
                    )
                  : Icon(
                      isUpcoming
                          ? Icons.notifications_outlined
                          : (wishlisted
                                ? Icons.favorite
                                : Icons.favorite_border),
                      color: Colors.white,
                    ),
            ),
          ),
        ],
      ),
      bottomNavigationBar: _BookingBar(listing: l),
    );
  }

  Future<void> _toggleWishlist(bool currentlyWishlisted) async {
    if (_listingId.isEmpty) return;
    setState(() => _wishlistLoading = true);
    final notifier = ref.read(wishlistIdsProvider.notifier);
    try {
      final service = ref.read(listingApiServiceProvider);
      if (currentlyWishlisted) {
        notifier.remove(_listingId); // optimistic
        await service.removeInterest(_listingId);
      } else {
        notifier.add(_listingId); // optimistic
        await service.addInterest(_listingId);
      }
    } catch (e) {
      // Roll back optimistic update
      if (currentlyWishlisted) {
        notifier.add(_listingId);
      } else {
        notifier.remove(_listingId);
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_L(context.isAr).failedToUpdateWishlist(e))),
        );
      }
    } finally {
      if (mounted) setState(() => _wishlistLoading = false);
    }
  }
}

// ── Hero Carousel ─────────────────────────────────────────────────────────────

class _HeroCarousel extends StatefulWidget {
  final List<Map<String, dynamic>> photos;
  final Map<String, dynamic> listing;
  const _HeroCarousel({required this.photos, required this.listing});

  @override
  State<_HeroCarousel> createState() => _HeroCarouselState();
}

class _HeroCarouselState extends State<_HeroCarousel> {
  int _current = 0;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    if (widget.photos.isEmpty) {
      return Container(
        height: 280,
        color: m.background,
        child: Center(
          child: Icon(Icons.apartment_outlined, size: 64, color: m.textMuted),
        ),
      );
    }

    return SizedBox(
      height: 300,
      child: Stack(
        children: [
          PageView.builder(
            itemCount: widget.photos.length,
            onPageChanged: (i) => setState(() => _current = i),
            itemBuilder: (_, i) {
              final url = widget.photos[i]['url'] as String;
              return GestureDetector(
                onTap: () => _openGallery(context, i),
                child: Image.network(
                  url,
                  fit: BoxFit.cover,
                  width: double.infinity,
                  errorBuilder: (_, _, _) => Container(
                    color: m.background,
                    child: Icon(
                      Icons.broken_image_outlined,
                      size: 48,
                      color: m.textMuted,
                    ),
                  ),
                ),
              );
            },
          ),
          if (widget.photos.length > 1)
            PositionedDirectional(
              bottom: 12,
              start: 0,
              end: 0,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(widget.photos.length.clamp(0, 8), (i) {
                  final activeDot = _current.clamp(0, 7);
                  return AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    margin: const EdgeInsets.symmetric(horizontal: 3),
                    width: activeDot == i ? 16 : 6,
                    height: 6,
                    decoration: BoxDecoration(
                      color: activeDot == i
                          ? Colors.white
                          : Colors.white.withValues(alpha: 0.5),
                      borderRadius: BorderRadius.circular(3),
                    ),
                  );
                }),
              ),
            ),
          PositionedDirectional(
            bottom: 12,
            end: 12,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                '${_current + 1}/${widget.photos.length}',
                style: GoogleFonts.josefinSans(
                  fontSize: 12,
                  color: Colors.white,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _openGallery(BuildContext context, int initial) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) =>
            _FullscreenGallery(photos: widget.photos, initialIndex: initial),
      ),
    );
  }
}

class _FullscreenGallery extends StatefulWidget {
  final List<Map<String, dynamic>> photos;
  final int initialIndex;
  const _FullscreenGallery({required this.photos, required this.initialIndex});

  @override
  State<_FullscreenGallery> createState() => _FullscreenGalleryState();
}

class _FullscreenGalleryState extends State<_FullscreenGallery> {
  late final PageController _pageController;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(initialPage: widget.initialIndex);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: PhotoViewGallery.builder(
        itemCount: widget.photos.length,
        pageController: _pageController,
        builder: (_, i) => PhotoViewGalleryPageOptions(
          imageProvider: NetworkImage(widget.photos[i]['url'] as String),
          minScale: PhotoViewComputedScale.contained,
          maxScale: PhotoViewComputedScale.covered * 2,
        ),
        backgroundDecoration: const BoxDecoration(color: Colors.black),
      ),
    );
  }
}

// ── Quick Facts ───────────────────────────────────────────────────────────────

class _QuickFacts extends StatelessWidget {
  final Map<String, dynamic> listing;
  const _QuickFacts({required this.listing});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final title = listing['titleEn'] as String? ?? l.listingFallback;
    final status = listing['status'] as String? ?? '';
    final rent = listing['annualRent'] as num?;
    final beds = listing['bedrooms'] as int?;
    final baths = listing['bathrooms'] as int?;
    final size = listing['sizeSqft'] as num?;
    final parking = listing['parkingSpaces'] as int?;
    final furnishing = listing['furnishing'] as String?;
    final viewType = listing['viewType'] as String?;
    final availableFrom = listing['availableFrom'] as String?;
    final deposit = listing['securityDeposit'] as num?;
    final cheques = listing['chequesAccepted'] as int?;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                title,
                style: LegacyMiftahType.display(fontSize: 22, color: m.textPrimary),
              ),
            ),
            const SizedBox(width: 8),
            _StatusBadgeInline(status: status),
          ],
        ),
        if (rent != null) ...[
          const SizedBox(height: 6),
          Text(
            '${Formatters.currencyCompact(rent)}${l.perYear}',
            style: GoogleFonts.josefinSans(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: m.isDark ? AppColors.accent : AppColors.accentDark,
            ),
          ),
        ],
        const SizedBox(height: 16),
        // Spec strip — beds/baths/sqft/parking, Cinzel numbers over tracked
        // labels, separated by hairlines (design mock 1e).
        if (beds != null || baths != null || size != null || parking != null)
          Container(
            decoration: BoxDecoration(
              color: m.surface,
              border: Border.all(color: m.border),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              children: [
                if (beds != null)
                  _SpecCell(
                    value: '$beds',
                    label: l.beds,
                    ar: l.ar,
                    border: true,
                  ),
                if (baths != null)
                  _SpecCell(
                    value: '$baths',
                    label: l.baths,
                    ar: l.ar,
                    border: true,
                  ),
                if (size != null)
                  _SpecCell(
                    value: size.round().toString(),
                    label: l.sqft,
                    ar: l.ar,
                    border: parking != null,
                  ),
                if (parking != null)
                  _SpecCell(
                    value: '$parking',
                    label: l.parking,
                    ar: l.ar,
                    border: false,
                  ),
              ],
            ),
          ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            if (furnishing != null)
              _FactChip(Icons.chair_outlined, l.furnishingLabel(furnishing)),
            if (viewType != null)
              _FactChip(
                Icons.landscape_outlined,
                l.viewLabel(viewType.toLowerCase()),
              ),
          ],
        ),
        if (availableFrom != null || deposit != null || cheques != null) ...[
          Divider(height: 24, color: m.divider),
          Wrap(
            spacing: 16,
            runSpacing: 8,
            children: [
              if (availableFrom != null)
                _FactChip(
                  Icons.calendar_today_outlined,
                  l.availableFrom(Formatters.date(availableFrom, ar: l.ar)),
                ),
              if (deposit != null)
                _FactChip(
                  Icons.security_outlined,
                  l.depositLabel(Formatters.currencyCompact(deposit)),
                ),
              if (cheques != null)
                _FactChip(Icons.receipt_long_outlined, l.chequesLabel(cheques)),
            ],
          ),
        ],
      ],
    );
  }
}

class _SpecCell extends StatelessWidget {
  final String value;
  final String label;
  final bool ar;
  final bool border;
  const _SpecCell({
    required this.value,
    required this.label,
    required this.ar,
    required this.border,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 8),
        decoration: BoxDecoration(
          border: border
              ? BorderDirectional(end: BorderSide(color: m.divider))
              : null,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              value,
              style: LegacyMiftahType.display(
                fontSize: 17,
                letterSpacing: 0.4,
                color: m.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: _overline(
                ar,
                fontSize: 10.5,
                letterSpacing: 1.6,
                color: m.textMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FactChip extends StatelessWidget {
  final IconData icon;
  final String label;
  const _FactChip(this.icon, this.label);

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16, color: m.textMuted),
        const SizedBox(width: 4),
        Text(
          label,
          style: GoogleFonts.josefinSans(fontSize: 13, color: m.textSecondary),
        ),
      ],
    );
  }
}

class _StatusBadgeInline extends StatelessWidget {
  final String status;
  const _StatusBadgeInline({required this.status});

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'PUBLISHED' => AppColors.success,
      'UPCOMING' => AppColors.accent,
      'UNLISTED' => const Color(0xFFF59E0B), // amber
      _ => context.miftah.textMuted,
    };
    return StatusBadge(label: status, color: color);
  }
}

// ── Amenities Grid ────────────────────────────────────────────────────────────

class _AmenitiesGrid extends StatelessWidget {
  final List<Map<String, dynamic>> amenities;
  const _AmenitiesGrid({required this.amenities});

  @override
  Widget build(BuildContext context) {
    if (amenities.isEmpty) return const SizedBox.shrink();
    final isAr = context.isAr;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle(_L(isAr).amenities),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: amenities.map((a) {
            final label = amenityLabel(
              a['amenity'] as String? ?? '',
              ar: isAr,
              customLabel: a['customLabel'] as String?,
            );
            final m = context.miftah;
            return Container(
              padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: m.borderStrong),
              ),
              child: Text(
                label,
                // Naskh + no tracking for Arabic labels (joining).
                style: context.isAr
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 12,
                        color: m.textPrimary,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 11.5,
                        letterSpacing: 0.6,
                        color: m.textPrimary,
                      ),
              ),
            );
          }).toList(),
        ),
      ],
    );
  }
}

// ── Map Card ──────────────────────────────────────────────────────────────────

class _MapCard extends StatelessWidget {
  final Map<String, dynamic> listing;
  const _MapCard({required this.listing});

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final lat = listing['lat'] as num?;
    final lng = listing['lng'] as num?;
    if (lat == null || lng == null) return const SizedBox.shrink();

    final position = LatLng(lat.toDouble(), lng.toDouble());

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle(l.location),
        const SizedBox(height: 10),
        ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: SizedBox(
            height: 180,
            child: GoogleMap(
              initialCameraPosition: CameraPosition(target: position, zoom: 15),
              markers: {
                Marker(markerId: const MarkerId('loc'), position: position),
              },
              zoomControlsEnabled: false,
              myLocationButtonEnabled: false,
              mapToolbarEnabled: false,
              liteModeEnabled: true,
            ),
          ),
        ),
        const SizedBox(height: 8),
        GestureDetector(
          onTap: () => _openMaps(lat.toDouble(), lng.toDouble()),
          child: Text(
            l.getDirections,
            style: GoogleFonts.josefinSans(
              fontSize: 13,
              color: context.miftah.isDark
                  ? AppColors.accent
                  : AppColors.accentDark,
              fontWeight: FontWeight.w600,
              decoration: TextDecoration.underline,
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _openMaps(double lat, double lng) async {
    final uri = Uri.parse(
      'https://www.google.com/maps/search/?api=1&query=$lat,$lng',
    );
    if (await canLaunchUrl(uri)) await launchUrl(uri);
  }
}

// ── Media Links ───────────────────────────────────────────────────────────────

class _MediaLinks extends StatelessWidget {
  final Map<String, dynamic> listing;
  const _MediaLinks({required this.listing});

  @override
  Widget build(BuildContext context) {
    final media = (listing['media'] as List? ?? [])
        .cast<Map<String, dynamic>>();
    final floorPlans = media
        .where((m) => m['mediaType'] == 'FLOOR_PLAN')
        .toList();
    final videos = media.where((m) => m['mediaType'] == 'VIDEO_URL').toList();
    final tours = media.where((m) => m['mediaType'] == 'TOUR_360_URL').toList();

    if (floorPlans.isEmpty && videos.isEmpty && tours.isEmpty) {
      return const SizedBox.shrink();
    }

    final l = _L(context.isAr);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle(l.moreMedia),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final fp in floorPlans)
              _MediaBtn(
                icon: Icons.architecture_outlined,
                label: l.floorPlan,
                url: fp['url'] as String,
              ),
            for (final v in videos)
              _MediaBtn(
                icon: Icons.play_circle_outline,
                label: l.videoTour,
                url: v['url'] as String,
              ),
            for (final t in tours)
              _MediaBtn(
                icon: Icons.threesixty,
                label: l.tour360,
                url: t['url'] as String,
              ),
          ],
        ),
      ],
    );
  }
}

class _MediaBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final String url;
  const _MediaBtn({required this.icon, required this.label, required this.url});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final fg = m.isDark ? AppColors.accent : AppColors.primary;
    return GestureDetector(
      onTap: () async {
        final uri = Uri.parse(url);
        if (await canLaunchUrl(uri)) await launchUrl(uri);
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: m.border),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 18, color: fg),
            const SizedBox(width: 6),
            Text(
              label,
              style: GoogleFonts.josefinSans(fontSize: 13, color: fg),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Booking bar ───────────────────────────────────────────────────────────────

/// Fixed near-black footer with the annual rent and the visit-scheduling CTA,
/// per design mock 1e (FROM overline + Cinzel price + gold CTA).
class _BookingBar extends StatelessWidget {
  final Map<String, dynamic> listing;
  const _BookingBar({required this.listing});

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final rent = listing['annualRent'] as num?;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFF111111),
        border: Border(
          top: BorderSide(color: AppColors.accent.withValues(alpha: 0.18)),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 14, 20, 14),
          child: Row(
            children: [
              if (rent != null) ...[
                Column(
                  // min, or the bar expands to the Scaffold's full height and
                  // crushes the body to zero.
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l.from,
                      style: _overline(
                        l.ar,
                        fontSize: 10.5,
                        letterSpacing: 2.4,
                        color: Colors.white.withValues(alpha: 0.45),
                      ),
                    ),
                    const SizedBox(height: 3),
                    RichText(
                      text: TextSpan(
                        style: _display(
                          l.ar,
                          fontSize: 19,
                          color: Colors.white,
                        ),
                        children: [
                          TextSpan(text: Formatters.currencyCompact(rent)),
                          TextSpan(
                            text: ' ${l.yrSuffix}',
                            style: GoogleFonts.josefinSans(
                              fontSize: 12,
                              color: Colors.white.withValues(alpha: 0.5),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
              ],
              Expanded(
                child: GoldButton(
                  label: l.scheduleVisit,
                  onPressed: () => context.push('/meetings/create'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

class _SectionTitle extends StatelessWidget {
  final String text;
  const _SectionTitle(this.text);

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Text(
      ar ? text : text.toUpperCase(),
      style: _display(
        ar,
        fontSize: 13,
        letterSpacing: 1.8,
        color: context.miftah.textPrimary,
      ),
    );
  }
}
