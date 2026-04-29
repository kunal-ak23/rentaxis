import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:photo_view/photo_view.dart';
import 'package:photo_view/photo_view_gallery.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

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
      return const Scaffold(body: Center(child: Text('Tenant not configured')));
    }

    final detailAsync = ref.watch(
      _listingDetailProvider(_DetailParams(tenantSlug, slug)),
    );

    return detailAsync.when(
      loading: () => Scaffold(
        appBar: AppBar(backgroundColor: Colors.transparent),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Scaffold(
        appBar: AppBar(),
        body: ErrorState(
          message: 'Failed to load listing',
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
    final l = widget.listing;
    final status = l['status'] as String? ?? '';
    final isUpcoming = status == 'UPCOMING';
    // Derive initial wishlist state from the shared provider (loaded at startup)
    final _wishlisted = ref.watch(wishlistIdsProvider).contains(_listingId);

    return Scaffold(
      backgroundColor: AppColors.background,
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: GestureDetector(
          onTap: () => context.pop(),
          child: Container(
            margin: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.4),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.arrow_back, color: Colors.white, size: 20),
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
                padding: const EdgeInsets.fromLTRB(16, 20, 16, 120),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
                    _QuickFacts(listing: l),
                    const SizedBox(height: 20),
                    if (l['descriptionEn'] != null) ...[
                      _SectionTitle('Description'),
                      const SizedBox(height: 8),
                      Text(
                        l['descriptionEn'] as String,
                        style: GoogleFonts.josefinSans(
                          fontSize: 14,
                          color: AppColors.textSecondary,
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
                    const SizedBox(height: 16),
                    _ScheduleVisitButton(),
                    const SizedBox(height: 20),
                    _MediaLinks(listing: l),
                  ]),
                ),
              ),
            ],
          ),
          // Save / Notify me FAB — positioned directly to avoid Scaffold FAB
          // placement issues when nested inside a ShellRoute with extendBody:true
          Positioned(
            right: 16,
            bottom: MediaQuery.of(context).viewPadding.bottom + 160,
            child: FloatingActionButton(
              onPressed: _wishlistLoading ? null : () => _toggleWishlist(_wishlisted),
              backgroundColor: isUpcoming ? AppColors.accent : AppColors.primary,
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
                          : (_wishlisted ? Icons.favorite : Icons.favorite_border),
                      color: Colors.white,
                    ),
            ),
          ),
        ],
      ),
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
          SnackBar(content: Text('Failed to update wishlist: $e')),
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
    if (widget.photos.isEmpty) {
      return Container(
        height: 280,
        color: AppColors.background,
        child: const Center(
          child: Icon(
            Icons.apartment_outlined,
            size: 64,
            color: AppColors.textMuted,
          ),
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
                  errorBuilder: (_, __, ___) => Container(
                    color: AppColors.background,
                    child: const Icon(
                      Icons.broken_image_outlined,
                      size: 48,
                      color: AppColors.textMuted,
                    ),
                  ),
                ),
              );
            },
          ),
          if (widget.photos.length > 1)
            Positioned(
              bottom: 12,
              left: 0,
              right: 0,
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
          Positioned(
            bottom: 12,
            right: 12,
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
    final title = listing['titleEn'] as String? ?? 'Listing';
    final status = listing['status'] as String? ?? '';
    final rent = listing['annualRent'] as num?;
    final beds = listing['bedrooms'] as int?;
    final baths = listing['bathrooms'] as int?;
    final size = listing['sizeSqft'] as num?;
    final furnishing = listing['furnishing'] as String?;
    final viewType = listing['viewType'] as String?;
    final availableFrom = listing['availableFrom'] as String?;
    final deposit = listing['securityDeposit'] as num?;
    final cheques = listing['chequesAccepted'] as int?;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  title,
                  style: GoogleFonts.cinzel(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
                ),
              ),
              _StatusBadgeInline(status: status),
            ],
          ),
          if (rent != null) ...[
            const SizedBox(height: 8),
            Text(
              '${Formatters.currencyCompact(rent)}/year',
              style: GoogleFonts.josefinSans(
                fontSize: 20,
                fontWeight: FontWeight.w700,
                color: AppColors.primary,
              ),
            ),
          ],
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              if (beds != null)
                _FactChip(
                  Icons.bed_outlined,
                  '$beds bed${beds != 1 ? 's' : ''}',
                ),
              if (baths != null)
                _FactChip(
                  Icons.bathtub_outlined,
                  '$baths bath${baths != 1 ? 's' : ''}',
                ),
              if (size != null)
                _FactChip(Icons.square_foot, '${size.round()} sqft'),
              if (furnishing != null)
                _FactChip(
                  Icons.chair_outlined,
                  furnishing.replaceAll('_', ' ').toLowerCase(),
                ),
              if (viewType != null)
                _FactChip(
                  Icons.landscape_outlined,
                  '${viewType.toLowerCase()} view',
                ),
            ],
          ),
          if (availableFrom != null || deposit != null || cheques != null) ...[
            const Divider(height: 24),
            Wrap(
              spacing: 16,
              runSpacing: 8,
              children: [
                if (availableFrom != null)
                  _FactChip(
                    Icons.calendar_today_outlined,
                    'From ${Formatters.date(availableFrom)}',
                  ),
                if (deposit != null)
                  _FactChip(
                    Icons.security_outlined,
                    'Dep. ${Formatters.currencyCompact(deposit)}',
                  ),
                if (cheques != null)
                  _FactChip(
                    Icons.receipt_long_outlined,
                    '$cheques cheque${cheques != 1 ? 's' : ''}',
                  ),
              ],
            ),
          ],
        ],
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
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16, color: AppColors.textMuted),
        const SizedBox(width: 4),
        Text(
          label,
          style: GoogleFonts.josefinSans(
            fontSize: 13,
            color: AppColors.textSecondary,
          ),
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
      _ => AppColors.textMuted,
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
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle('Amenities'),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: amenities.map((a) {
            final name = (a['amenity'] as String? ?? '')
                .replaceAll('_', ' ')
                .toLowerCase();
            final label = a['customLabel'] as String? ?? name;
            return Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: AppColors.primary.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                  color: AppColors.primary.withValues(alpha: 0.2),
                ),
              ),
              child: Text(
                label,
                style: GoogleFonts.josefinSans(
                  fontSize: 12,
                  color: AppColors.primary,
                  fontWeight: FontWeight.w500,
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
    final lat = listing['lat'] as num?;
    final lng = listing['lng'] as num?;
    if (lat == null || lng == null) return const SizedBox.shrink();

    final position = LatLng(lat.toDouble(), lng.toDouble());

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle('Location'),
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
            'Get directions',
            style: GoogleFonts.josefinSans(
              fontSize: 13,
              color: AppColors.primary,
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

    if (floorPlans.isEmpty && videos.isEmpty && tours.isEmpty)
      return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle('More media'),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final fp in floorPlans)
              _MediaBtn(
                icon: Icons.architecture_outlined,
                label: 'Floor plan',
                url: fp['url'] as String,
              ),
            for (final v in videos)
              _MediaBtn(
                icon: Icons.play_circle_outline,
                label: 'Video tour',
                url: v['url'] as String,
              ),
            for (final t in tours)
              _MediaBtn(
                icon: Icons.threesixty,
                label: '360° tour',
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
    return GestureDetector(
      onTap: () async {
        final uri = Uri.parse(url);
        if (await canLaunchUrl(uri)) await launchUrl(uri);
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          boxShadow: AppShadows.soft,
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 18, color: AppColors.primary),
            const SizedBox(width: 6),
            Text(
              label,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                color: AppColors.primary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Schedule Visit Button ─────────────────────────────────────────────────────

class _ScheduleVisitButton extends StatelessWidget {
  const _ScheduleVisitButton();

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: ElevatedButton.icon(
        onPressed: () => context.push('/meetings/create'),
        icon: const Icon(Icons.calendar_month_outlined, color: Colors.white, size: 18),
        label: Text(
          'Schedule a Visit',
          style: GoogleFonts.josefinSans(
            color: Colors.white,
            fontWeight: FontWeight.w600,
            fontSize: 15,
          ),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.primary,
          padding: const EdgeInsets.symmetric(vertical: 14),
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
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
    return Text(
      text,
      style: GoogleFonts.cinzel(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        color: AppColors.textPrimary,
      ),
    );
  }
}
