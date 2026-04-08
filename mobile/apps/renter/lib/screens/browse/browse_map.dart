import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class BrowseMap extends ConsumerStatefulWidget {
  final List<Map<String, dynamic>> listings;
  const BrowseMap({super.key, required this.listings});

  @override
  ConsumerState<BrowseMap> createState() => _BrowseMapState();
}

class _BrowseMapState extends ConsumerState<BrowseMap> {
  GoogleMapController? _mapController;
  Map<String, dynamic>? _selectedListing;
  Set<Marker> _markers = {};

  static const _initialPosition = CameraPosition(
    target: LatLng(25.2048, 55.2708), // Dubai
    zoom: 11,
  );

  @override
  void initState() {
    super.initState();
    _buildMarkers();
  }

  @override
  void didUpdateWidget(BrowseMap old) {
    super.didUpdateWidget(old);
    if (old.listings != widget.listings) _buildMarkers();
  }

  void _buildMarkers() {
    final markers = <Marker>{};
    for (final listing in widget.listings) {
      final lat = listing['lat'] as num?;
      final lng = listing['lng'] as num?;
      if (lat == null || lng == null) continue;
      markers.add(Marker(
        markerId: MarkerId(listing['id'] as String),
        position: LatLng(lat.toDouble(), lng.toDouble()),
        onTap: () {
          setState(() => _selectedListing = listing);
          _mapController?.animateCamera(
            CameraUpdate.newLatLng(LatLng(lat.toDouble(), lng.toDouble())),
          );
        },
        infoWindow: InfoWindow(
          title: listing['title'] as String? ?? '',
        ),
      ));
    }
    setState(() => _markers = markers);
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        GoogleMap(
          initialCameraPosition: _initialPosition,
          markers: _markers,
          myLocationButtonEnabled: false,
          zoomControlsEnabled: false,
          mapToolbarEnabled: false,
          onMapCreated: (c) => _mapController = c,
          onTap: (_) => setState(() => _selectedListing = null),
        ),
        if (_selectedListing != null)
          Positioned(
            left: 16,
            right: 16,
            bottom: 100,
            child: _PeekCard(
              listing: _selectedListing!,
              onTap: () =>
                  context.push('/browse/${_selectedListing!['slug']}'),
              onClose: () => setState(() => _selectedListing = null),
            ),
          ),
      ],
    );
  }
}

class _PeekCard extends StatelessWidget {
  final Map<String, dynamic> listing;
  final VoidCallback onTap;
  final VoidCallback onClose;

  const _PeekCard({
    required this.listing,
    required this.onTap,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    final coverUrl = listing['coverPhotoUrl'] as String?;
    final title = listing['title'] as String? ?? 'Listing';
    final rent = listing['annualRent'] as num?;
    final beds = listing['bedrooms'] as int?;
    final propertyName = listing['propertyName'] as String?;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 90,
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(16),
          boxShadow: AppShadows.medium,
        ),
        child: Row(
          children: [
            ClipRRect(
              borderRadius:
                  const BorderRadius.horizontal(left: Radius.circular(16)),
              child: coverUrl != null
                  ? Image.network(coverUrl,
                      width: 90, height: 90, fit: BoxFit.cover)
                  : Container(
                      width: 90, height: 90, color: AppColors.background),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: GoogleFonts.josefinSans(
                          fontSize: 14, fontWeight: FontWeight.w600),
                    ),
                    if (propertyName != null)
                      Text(propertyName,
                          maxLines: 1,
                          style: GoogleFonts.josefinSans(
                              fontSize: 12, color: AppColors.textMuted)),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        if (beds != null)
                          Text('$beds bd · ',
                              style: GoogleFonts.josefinSans(
                                  fontSize: 12,
                                  color: AppColors.textSecondary)),
                        if (rent != null)
                          Text(
                            Formatters.currencyCompact(rent.toDouble()),
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
              padding: const EdgeInsets.only(right: 8),
              child: GestureDetector(
                onTap: onClose,
                child: const Icon(Icons.close,
                    size: 20, color: AppColors.textMuted),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
