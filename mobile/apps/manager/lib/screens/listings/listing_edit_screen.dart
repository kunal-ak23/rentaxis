import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

// ── Providers ─────────────────────────────────────────────────────────────────

final _listingDetailProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>?, String>((ref, id) async {
  if (id == 'new') return null;
  final service = ref.watch(listingApiServiceProvider);
  return service.getListing(id);
});

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingEditScreen extends ConsumerStatefulWidget {
  final String listingId; // 'new' for create
  const ListingEditScreen({super.key, required this.listingId});

  @override
  ConsumerState<ListingEditScreen> createState() =>
      _ListingEditScreenState();
}

class _ListingEditScreenState extends ConsumerState<ListingEditScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabs;
  bool _saving = false;
  bool _publishing = false;

  // ── Form state ───────────────────────────────────────────────────────────

  // Details tab
  final _titleEnCtrl = TextEditingController();
  final _titleArCtrl = TextEditingController();
  final _descEnCtrl = TextEditingController();
  final _descArCtrl = TextEditingController();
  int? _bedrooms;
  int? _bathrooms;
  double? _sizeSqft;
  int? _floor;
  int? _parkingSpaces;
  String? _furnishing; // UNFURNISHED | SEMI_FURNISHED | FULLY_FURNISHED
  String? _viewType;

  // Pricing tab
  final _rentCtrl = TextEditingController();
  final _depositCtrl = TextEditingController();
  int? _minLeaseMonths;
  int? _cheques;
  bool _dewaIncluded = false;
  bool _chillerIncluded = false;
  String? _availableFrom;

  // Location tab
  double? _lat;
  double? _lng;

  // Amenities tab
  final Set<String> _amenities = {};

  // Media tab
  List<Map<String, dynamic>> _existingMedia = [];
  final List<File> _pendingUploads = [];
  bool _uploadingMedia = false;

  // SEO tab
  final _seoTitleCtrl = TextEditingController();
  final _seoDescCtrl = TextEditingController();
  final _seoKeywordsCtrl = TextEditingController();

  String? _listingId;
  bool _loaded = false;

  static const _furnishingOptions = [
    ('UNFURNISHED', 'Unfurnished'),
    ('SEMI_FURNISHED', 'Semi-furnished'),
    ('FULLY_FURNISHED', 'Fully furnished'),
  ];

  static const _viewTypeOptions = [
    ('SEA', 'Sea'),
    ('CITY', 'City'),
    ('POOL', 'Pool'),
    ('GARDEN', 'Garden'),
    ('STREET', 'Street'),
    ('COMMUNITY', 'Community'),
    ('OTHER', 'Other'),
  ];


  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 5, vsync: this);
    _listingId = widget.listingId == 'new' ? null : widget.listingId;
  }

  @override
  void dispose() {
    _tabs.dispose();
    _titleEnCtrl.dispose();
    _titleArCtrl.dispose();
    _descEnCtrl.dispose();
    _descArCtrl.dispose();
    _rentCtrl.dispose();
    _depositCtrl.dispose();
    _seoTitleCtrl.dispose();
    _seoDescCtrl.dispose();
    _seoKeywordsCtrl.dispose();
    super.dispose();
  }

  void _populateFromListing(Map<String, dynamic> l) {
    if (_loaded) return;
    _loaded = true;
    _titleEnCtrl.text = l['titleEn'] as String? ?? '';
    _titleArCtrl.text = l['titleAr'] as String? ?? '';
    _descEnCtrl.text = l['descriptionEn'] as String? ?? '';
    _descArCtrl.text = l['descriptionAr'] as String? ?? '';
    _bedrooms = l['bedrooms'] as int?;
    _bathrooms = l['bathrooms'] as int?;
    _sizeSqft = (l['sizeSqft'] as num?)?.toDouble();
    _floor = l['floor'] as int?;
    _parkingSpaces = l['parkingSpaces'] as int?;
    _furnishing = l['furnishing'] as String?;
    _viewType = l['viewType'] as String?;
    _rentCtrl.text = (l['annualRent'] as num?)?.toString() ?? '';
    _depositCtrl.text = (l['securityDeposit'] as num?)?.toString() ?? '';
    _minLeaseMonths = l['minLeaseMonths'] as int?;
    _cheques = l['chequesAccepted'] as int?;
    _dewaIncluded = l['dewaIncluded'] as bool? ?? false;
    _chillerIncluded = l['chillerIncluded'] as bool? ?? false;
    _availableFrom = l['availableFrom'] as String?;
    _lat = (l['lat'] as num?)?.toDouble();
    _lng = (l['lng'] as num?)?.toDouble();
    _seoTitleCtrl.text = l['seoTitle'] as String? ?? '';
    _seoDescCtrl.text = l['seoDescription'] as String? ?? '';
    _seoKeywordsCtrl.text = l['seoKeywords'] as String? ?? '';
    _existingMedia = (l['media'] as List? ?? []).cast<Map<String, dynamic>>();
    final amenities = (l['amenities'] as List? ?? []).cast<Map<String, dynamic>>();
    _amenities.addAll(amenities.map((a) => a['amenity'] as String));
  }

  @override
  Widget build(BuildContext context) {
    final isNew = _listingId == null;
    final detailAsync = ref.watch(_listingDetailProvider(widget.listingId));

    if (isNew) _loaded = true;

    // Populate form fields when listing data loads — use ref.listen to keep
    // side effects out of the build phase.
    ref.listen<AsyncValue<Map<String, dynamic>?>>(
      _listingDetailProvider(widget.listingId),
      (_, next) {
        if (!isNew) next.whenData((l) { if (l != null) _populateFromListing(l); });
      },
    );

    final status = isNew
        ? 'DRAFT'
        : (detailAsync.valueOrNull?['status'] as String? ?? 'DRAFT');

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        elevation: 0,
        title: Text(
          isNew ? 'New Listing' : 'Edit Listing',
          style: GoogleFonts.cinzel(
              fontSize: 18,
              fontWeight: FontWeight.w700,
              color: AppColors.textPrimary),
        ),
        actions: [
          // Publish / Unlist button
          if (!isNew) ...[
            TextButton.icon(
              onPressed: _publishing ? null : () => _togglePublish(status),
              icon: _publishing
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : Icon(
                      status == 'PUBLISHED'
                          ? Icons.visibility_off_outlined
                          : Icons.publish_outlined,
                      size: 18),
              label: Text(
                status == 'PUBLISHED' ? 'Unlist' : 'Publish',
                style: GoogleFonts.josefinSans(fontWeight: FontWeight.w600),
              ),
            ),
          ],
          // Save button
          TextButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : Text(
                    'Save',
                    style: GoogleFonts.josefinSans(
                      fontWeight: FontWeight.w700,
                      color: AppColors.primary,
                      fontSize: 15,
                    ),
                  ),
          ),
        ],
        bottom: TabBar(
          controller: _tabs,
          isScrollable: true,
          labelStyle: GoogleFonts.josefinSans(
              fontSize: 13, fontWeight: FontWeight.w600),
          unselectedLabelStyle:
              GoogleFonts.josefinSans(fontSize: 13),
          labelColor: AppColors.primary,
          unselectedLabelColor: AppColors.textMuted,
          indicatorColor: AppColors.primary,
          tabs: const [
            Tab(text: 'Details'),
            Tab(text: 'Pricing'),
            Tab(text: 'Location'),
            Tab(text: 'Amenities'),
            Tab(text: 'Media'),
          ],
        ),
      ),
      body: detailAsync.when(
        loading: () => isNew
            ? _buildForm()
            : const Center(child: CircularProgressIndicator()),
        error: (e, _) => ErrorState(
          message: 'Failed to load listing',
          onRetry: () =>
              ref.invalidate(_listingDetailProvider(widget.listingId)),
        ),
        data: (_) => _buildForm(),
      ),
    );
  }

  Widget _buildForm() {
    return TabBarView(
      controller: _tabs,
      children: [
        _DetailsTab(state: this),
        _PricingTab(state: this),
        _LocationTab(state: this),
        _AmenitiesTab(state: this),
        _MediaTab(state: this),
      ],
    );
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  Future<void> _save() async {
    if (_titleEnCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Title (English) is required')),
      );
      return;
    }
    setState(() => _saving = true);
    try {
      final service = ref.read(listingApiServiceProvider);
      final data = _buildPayload();

      if (_listingId == null) {
        final result = await service.createListing(data);
        _listingId = result['id'] as String?;
        // Upload any pending media
        await _flushPendingUploads();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Listing created')));
          context.go('/listings/$_listingId');
        }
      } else {
        await service.updateListing(_listingId!, data);
        await _flushPendingUploads();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Saved')));
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Error: $e')));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _togglePublish(String currentStatus) async {
    if (_listingId == null) return;
    setState(() => _publishing = true);
    try {
      final service = ref.read(listingApiServiceProvider);
      if (currentStatus == 'PUBLISHED') {
        await service.unlistListing(_listingId!);
      } else {
        await service.publishListing(_listingId!);
      }
      ref.invalidate(_listingDetailProvider(widget.listingId));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Error: $e')));
      }
    } finally {
      if (mounted) setState(() => _publishing = false);
    }
  }

  Map<String, dynamic> _buildPayload() {
    return {
      'titleEn': _titleEnCtrl.text.trim(),
      if (_titleArCtrl.text.isNotEmpty) 'titleAr': _titleArCtrl.text.trim(),
      if (_descEnCtrl.text.isNotEmpty) 'descriptionEn': _descEnCtrl.text.trim(),
      if (_descArCtrl.text.isNotEmpty) 'descriptionAr': _descArCtrl.text.trim(),
      if (_bedrooms != null) 'bedrooms': _bedrooms,
      if (_bathrooms != null) 'bathrooms': _bathrooms,
      if (_sizeSqft != null) 'sizeSqft': _sizeSqft,
      if (_floor != null) 'floor': _floor,
      if (_parkingSpaces != null) 'parkingSpaces': _parkingSpaces,
      if (_furnishing != null) 'furnishing': _furnishing,
      if (_viewType != null) 'viewType': _viewType,
      if (_rentCtrl.text.isNotEmpty)
        'annualRent': double.tryParse(_rentCtrl.text),
      if (_depositCtrl.text.isNotEmpty)
        'securityDeposit': double.tryParse(_depositCtrl.text),
      if (_minLeaseMonths != null) 'minLeaseMonths': _minLeaseMonths,
      if (_cheques != null) 'chequesAccepted': _cheques,
      'dewaIncluded': _dewaIncluded,
      'chillerIncluded': _chillerIncluded,
      if (_availableFrom != null) 'availableFrom': _availableFrom,
      if (_lat != null) 'lat': _lat,
      if (_lng != null) 'lng': _lng,
      if (_seoTitleCtrl.text.isNotEmpty) 'seoTitle': _seoTitleCtrl.text.trim(),
      if (_seoDescCtrl.text.isNotEmpty)
        'seoDescription': _seoDescCtrl.text.trim(),
      if (_seoKeywordsCtrl.text.isNotEmpty)
        'seoKeywords': _seoKeywordsCtrl.text.trim(),
      'amenities': _amenities
          .map((a) => {'amenity': a, 'customLabel': null})
          .toList(),
    };
  }

  Future<void> _flushPendingUploads() async {
    if (_listingId == null || _pendingUploads.isEmpty) return;
    final service = ref.read(listingApiServiceProvider);
    for (final file in List.of(_pendingUploads)) {
      final bytes = await file.readAsBytes();
      final name = file.path.split('/').last;
      await service.uploadMedia(_listingId!, bytes, name,
          isCover: _existingMedia.isEmpty && _pendingUploads.first == file);
      _pendingUploads.remove(file);
    }
    ref.invalidate(_listingDetailProvider(widget.listingId));
  }
}

// ── Tab: Details ──────────────────────────────────────────────────────────────

class _DetailsTab extends StatefulWidget {
  final _ListingEditScreenState state;
  const _DetailsTab({required this.state});

  @override
  State<_DetailsTab> createState() => _DetailsTabState();
}

class _DetailsTabState extends State<_DetailsTab> {
  _ListingEditScreenState get s => widget.state;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 100),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel('Title'),
          _Field(controller: s._titleEnCtrl, hint: 'Title (English) *'),
          const SizedBox(height: 10),
          _Field(
              controller: s._titleArCtrl,
              hint: 'العنوان (Arabic)',
              textDirection: TextDirection.rtl),
          const SizedBox(height: 20),
          _SectionLabel('Description'),
          _Field(
              controller: s._descEnCtrl,
              hint: 'Description (English)',
              maxLines: 4),
          const SizedBox(height: 10),
          _Field(
              controller: s._descArCtrl,
              hint: 'الوصف (Arabic)',
              maxLines: 4,
              textDirection: TextDirection.rtl),
          const SizedBox(height: 20),
          _SectionLabel('Specifications'),
          _NumRow(
            children: [
              _NumField(
                  label: 'Bedrooms',
                  value: s._bedrooms,
                  onChanged: (v) => setState(() => s._bedrooms = v)),
              _NumField(
                  label: 'Bathrooms',
                  value: s._bathrooms,
                  onChanged: (v) => setState(() => s._bathrooms = v)),
              _NumField(
                  label: 'Floor',
                  value: s._floor,
                  onChanged: (v) => setState(() => s._floor = v)),
            ],
          ),
          const SizedBox(height: 10),
          _NumRow(
            children: [
              _NumField(
                  label: 'Size (sqft)',
                  value: s._sizeSqft?.round(),
                  onChanged: (v) =>
                      setState(() => s._sizeSqft = v?.toDouble())),
              _NumField(
                  label: 'Parking',
                  value: s._parkingSpaces,
                  onChanged: (v) => setState(() => s._parkingSpaces = v)),
            ],
          ),
          const SizedBox(height: 20),
          _SectionLabel('Furnishing'),
          const SizedBox(height: 8),
          _OptionPills(
            options: _ListingEditScreenState._furnishingOptions,
            selected: s._furnishing,
            onSelect: (v) => setState(() => s._furnishing = v),
          ),
          const SizedBox(height: 20),
          _SectionLabel('View type'),
          const SizedBox(height: 8),
          _OptionPills(
            options: _ListingEditScreenState._viewTypeOptions,
            selected: s._viewType,
            onSelect: (v) => setState(() => s._viewType = v),
          ),
        ],
      ),
    );
  }
}

// ── Tab: Pricing ──────────────────────────────────────────────────────────────

class _PricingTab extends StatefulWidget {
  final _ListingEditScreenState state;
  const _PricingTab({required this.state});

  @override
  State<_PricingTab> createState() => _PricingTabState();
}

class _PricingTabState extends State<_PricingTab> {
  _ListingEditScreenState get s => widget.state;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 100),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel('Rent'),
          _Field(
              controller: s._rentCtrl,
              hint: 'Annual rent (AED)',
              keyboardType: TextInputType.number),
          const SizedBox(height: 10),
          _Field(
              controller: s._depositCtrl,
              hint: 'Security deposit (AED)',
              keyboardType: TextInputType.number),
          const SizedBox(height: 20),
          _SectionLabel('Payment terms'),
          const SizedBox(height: 8),
          _NumRow(
            children: [
              _NumField(
                  label: 'Min lease (months)',
                  value: s._minLeaseMonths,
                  onChanged: (v) =>
                      setState(() => s._minLeaseMonths = v)),
              _NumField(
                  label: 'Cheques accepted',
                  value: s._cheques,
                  onChanged: (v) => setState(() => s._cheques = v)),
            ],
          ),
          const SizedBox(height: 20),
          _SectionLabel('Utilities included'),
          const SizedBox(height: 8),
          _SwitchRow(
            label: 'DEWA (electricity & water)',
            value: s._dewaIncluded,
            onChanged: (v) => setState(() => s._dewaIncluded = v),
          ),
          const SizedBox(height: 8),
          _SwitchRow(
            label: 'District cooling (chiller)',
            value: s._chillerIncluded,
            onChanged: (v) => setState(() => s._chillerIncluded = v),
          ),
          const SizedBox(height: 20),
          _SectionLabel('Availability'),
          const SizedBox(height: 8),
          GestureDetector(
            onTap: () async {
              final date = await showDatePicker(
                context: context,
                initialDate: DateTime.now(),
                firstDate: DateTime.now(),
                lastDate: DateTime.now().add(const Duration(days: 730)),
              );
              if (date != null) {
                setState(() => s._availableFrom =
                    date.toIso8601String().split('T').first);
              }
            },
            child: Container(
              padding: const EdgeInsets.symmetric(
                  horizontal: 14, vertical: 14),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.border),
              ),
              child: Row(
                children: [
                  const Icon(Icons.calendar_today_outlined,
                      size: 18, color: AppColors.textMuted),
                  const SizedBox(width: 10),
                  Text(
                    s._availableFrom ?? 'Available from (optional)',
                    style: GoogleFonts.josefinSans(
                      fontSize: 14,
                      color: s._availableFrom != null
                          ? AppColors.textPrimary
                          : AppColors.textMuted,
                    ),
                  ),
                  if (s._availableFrom != null) ...[
                    const Spacer(),
                    GestureDetector(
                      onTap: () =>
                          setState(() => s._availableFrom = null),
                      child: const Icon(Icons.close,
                          size: 16, color: AppColors.textMuted),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Tab: Location ─────────────────────────────────────────────────────────────

class _LocationTab extends StatefulWidget {
  final _ListingEditScreenState state;
  const _LocationTab({required this.state});

  @override
  State<_LocationTab> createState() => _LocationTabState();
}

class _LocationTabState extends State<_LocationTab> {
  _ListingEditScreenState get s => widget.state;

  static const _defaultPos = LatLng(25.2048, 55.2708); // Dubai

  LatLng get _markerPos => s._lat != null && s._lng != null
      ? LatLng(s._lat!, s._lng!)
      : _defaultPos;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 100),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel('Pin location'),
          const SizedBox(height: 4),
          Text(
            'Tap the map to set the exact location',
            style: GoogleFonts.josefinSans(
                fontSize: 12, color: AppColors.textMuted),
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: SizedBox(
              height: 280,
              child: GoogleMap(
                initialCameraPosition: CameraPosition(
                    target: _markerPos, zoom: s._lat != null ? 15 : 11),
                markers: s._lat != null
                    ? {
                        Marker(
                          markerId: const MarkerId('pin'),
                          position: _markerPos,
                          draggable: true,
                          onDragEnd: (pos) => setState(() {
                            s._lat = pos.latitude;
                            s._lng = pos.longitude;
                          }),
                        )
                      }
                    : {},
                onTap: (pos) => setState(() {
                  s._lat = pos.latitude;
                  s._lng = pos.longitude;
                }),
                myLocationButtonEnabled: false,
                zoomControlsEnabled: true,
              ),
            ),
          ),
          if (s._lat != null) ...[
            const SizedBox(height: 12),
            Text(
              'Lat: ${s._lat!.toStringAsFixed(6)}, Lng: ${s._lng!.toStringAsFixed(6)}',
              style: GoogleFonts.josefinSans(
                  fontSize: 12, color: AppColors.textMuted),
            ),
            TextButton(
              onPressed: () => setState(() {
                s._lat = null;
                s._lng = null;
              }),
              child: const Text('Clear location'),
            ),
          ],
        ],
      ),
    );
  }
}

// ── Tab: Amenities ────────────────────────────────────────────────────────────

class _AmenitiesTab extends StatefulWidget {
  final _ListingEditScreenState state;
  const _AmenitiesTab({required this.state});

  @override
  State<_AmenitiesTab> createState() => _AmenitiesTabState();
}

class _AmenitiesTabState extends State<_AmenitiesTab> {
  _ListingEditScreenState get s => widget.state;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 100),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel(
              '${s._amenities.length} amenities selected'),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: kAllAmenities.map((a) {
              final isSelected = s._amenities.contains(a);
              final label = a
                  .split('_')
                  .map((w) =>
                      w[0].toUpperCase() + w.substring(1).toLowerCase())
                  .join(' ');
              return GestureDetector(
                onTap: () => setState(() {
                  if (isSelected) {
                    s._amenities.remove(a);
                  } else {
                    s._amenities.add(a);
                  }
                }),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 180),
                  padding: const EdgeInsets.symmetric(
                      horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: isSelected
                        ? AppColors.primary
                        : AppColors.surface,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(
                      color: isSelected
                          ? AppColors.primary
                          : AppColors.border,
                    ),
                    boxShadow: isSelected ? [] : AppShadows.soft,
                  ),
                  child: Text(
                    label,
                    style: GoogleFonts.josefinSans(
                      fontSize: 12,
                      color: isSelected
                          ? Colors.white
                          : AppColors.textSecondary,
                      fontWeight: isSelected
                          ? FontWeight.w600
                          : FontWeight.w400,
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
        ],
      ),
    );
  }
}

// ── Tab: Media ────────────────────────────────────────────────────────────────

class _MediaTab extends StatefulWidget {
  final _ListingEditScreenState state;
  const _MediaTab({required this.state});

  @override
  State<_MediaTab> createState() => _MediaTabState();
}

class _MediaTabState extends State<_MediaTab> {
  _ListingEditScreenState get s => widget.state;
  final _picker = ImagePicker();

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 100),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _SectionLabel(
                  '${s._existingMedia.length + s._pendingUploads.length} photos'),
              const Spacer(),
              if (s._uploadingMedia)
                const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2)),
            ],
          ),
          const SizedBox(height: 12),

          // Existing server media
          if (s._existingMedia.isNotEmpty)
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate:
                  const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: 8,
                crossAxisSpacing: 8,
                childAspectRatio: 1,
              ),
              itemCount: s._existingMedia.length,
              itemBuilder: (_, i) {
                final media = s._existingMedia[i];
                final url = media['url'] as String? ?? '';
                final isCover = media['isCover'] as bool? ?? false;
                return Stack(
                  fit: StackFit.expand,
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(10),
                      child: Image.network(url, fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) =>
                              Container(color: AppColors.background)),
                    ),
                    if (isCover)
                      Positioned(
                        top: 4,
                        left: 4,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppColors.primary,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text('Cover',
                              style: GoogleFonts.josefinSans(
                                  fontSize: 9,
                                  color: Colors.white,
                                  fontWeight: FontWeight.w600)),
                        ),
                      ),
                    Positioned(
                      top: 4,
                      right: 4,
                      child: GestureDetector(
                        onTap: () => _deleteExisting(
                            media['id'] as String? ?? ''),
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.55),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.close,
                              size: 14, color: Colors.white),
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),

          // Pending local uploads
          if (s._pendingUploads.isNotEmpty) ...[
            const SizedBox(height: 12),
            _SectionLabel('Pending upload (${s._pendingUploads.length})'),
            const SizedBox(height: 8),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate:
                  const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: 8,
                crossAxisSpacing: 8,
                childAspectRatio: 1,
              ),
              itemCount: s._pendingUploads.length,
              itemBuilder: (_, i) => Stack(
                fit: StackFit.expand,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.file(s._pendingUploads[i],
                        fit: BoxFit.cover),
                  ),
                  Positioned(
                    top: 4,
                    right: 4,
                    child: GestureDetector(
                      onTap: () => setState(
                          () => s._pendingUploads.removeAt(i)),
                      child: Container(
                        width: 24,
                        height: 24,
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.55),
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.close,
                            size: 14, color: Colors.white),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],

          const SizedBox(height: 16),

          // Add photos row
          Row(
            children: [
              Expanded(
                child: _AddPhotoBtn(
                  icon: Icons.photo_library_outlined,
                  label: 'Gallery',
                  onTap: () => _pick(ImageSource.gallery),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _AddPhotoBtn(
                  icon: Icons.camera_alt_outlined,
                  label: 'Camera',
                  onTap: () => _pick(ImageSource.camera),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Photos are uploaded when you tap Save.',
            style: GoogleFonts.josefinSans(
                fontSize: 11, color: AppColors.textMuted),
          ),
        ],
      ),
    );
  }

  Future<void> _pick(ImageSource source) async {
    try {
      if (source == ImageSource.gallery) {
        final files = await _picker.pickMultiImage(imageQuality: 80);
        if (files.isEmpty) return;
        setState(() => s._pendingUploads
            .addAll(files.map((f) => File(f.path))));
      } else {
        final file =
            await _picker.pickImage(source: source, imageQuality: 80);
        if (file == null) return;
        setState(() => s._pendingUploads.add(File(file.path)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Could not pick image: $e')));
      }
    }
  }

  Future<void> _deleteExisting(String mediaId) async {
    if (s._listingId == null) return;
    try {
      final service = s.ref.read(listingApiServiceProvider);
      await service.deleteMedia(s._listingId!, mediaId);
      setState(() =>
          s._existingMedia.removeWhere((m) => m['id'] == mediaId));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Delete failed: $e')));
      }
    }
  }
}

class _AddPhotoBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  const _AddPhotoBtn(
      {required this.icon, required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.primary.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
              color: AppColors.primary.withValues(alpha: 0.25),
              style: BorderStyle.solid),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 20, color: AppColors.primary),
            const SizedBox(width: 8),
            Text(label,
                style: GoogleFonts.josefinSans(
                    fontSize: 13,
                    color: AppColors.primary,
                    fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

// ── Reusable form helpers ─────────────────────────────────────────────────────

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        text,
        style: GoogleFonts.josefinSans(
          fontSize: 13,
          fontWeight: FontWeight.w600,
          color: AppColors.textMuted,
          letterSpacing: 0.3,
        ),
      ),
    );
  }
}

class _Field extends StatelessWidget {
  final TextEditingController controller;
  final String hint;
  final int maxLines;
  final TextInputType keyboardType;
  final TextDirection? textDirection;

  const _Field({
    required this.controller,
    required this.hint,
    this.maxLines = 1,
    this.keyboardType = TextInputType.text,
    this.textDirection,
  });

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      maxLines: maxLines,
      keyboardType: keyboardType,
      textDirection: textDirection,
      style: GoogleFonts.josefinSans(fontSize: 14),
      decoration: InputDecoration(
        hintText: hint,
        hintStyle: GoogleFonts.josefinSans(
            fontSize: 14, color: AppColors.textMuted),
        filled: true,
        fillColor: AppColors.surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide:
              const BorderSide(color: AppColors.primary, width: 1.5),
        ),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      ),
    );
  }
}

class _NumField extends StatelessWidget {
  final String label;
  final int? value;
  final ValueChanged<int?> onChanged;
  const _NumField(
      {required this.label,
      required this.value,
      required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: TextField(
        controller: TextEditingController(
            text: value != null ? '$value' : ''),
        keyboardType: TextInputType.number,
        style: GoogleFonts.josefinSans(fontSize: 14),
        onChanged: (v) => onChanged(int.tryParse(v)),
        decoration: InputDecoration(
          labelText: label,
          labelStyle: GoogleFonts.josefinSans(
              fontSize: 12, color: AppColors.textMuted),
          filled: true,
          fillColor: AppColors.surface,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.border),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.border),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide:
                const BorderSide(color: AppColors.primary, width: 1.5),
          ),
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        ),
      ),
    );
  }
}

class _NumRow extends StatelessWidget {
  final List<Widget> children;
  const _NumRow({required this.children});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: children
          .expand((w) => [w, const SizedBox(width: 10)])
          .toList()
        ..removeLast(),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;
  const _SwitchRow(
      {required this.label,
      required this.value,
      required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(label,
                style: GoogleFonts.josefinSans(
                    fontSize: 14, color: AppColors.textPrimary)),
          ),
          Switch.adaptive(
            value: value,
            onChanged: onChanged,
            activeColor: AppColors.primary,
          ),
        ],
      ),
    );
  }
}

class _OptionPills extends StatelessWidget {
  final List<(String, String)> options;
  final String? selected;
  final ValueChanged<String?> onSelect;
  const _OptionPills(
      {required this.options,
      required this.selected,
      required this.onSelect});

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: options.map((opt) {
        final isSelected = selected == opt.$1;
        return GestureDetector(
          onTap: () => onSelect(isSelected ? null : opt.$1),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            padding:
                const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            decoration: BoxDecoration(
              color: isSelected ? AppColors.primary : AppColors.surface,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color:
                    isSelected ? AppColors.primary : AppColors.border,
              ),
              boxShadow: isSelected ? [] : AppShadows.soft,
            ),
            child: Text(
              opt.$2,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                color: isSelected
                    ? Colors.white
                    : AppColors.textSecondary,
                fontWeight: isSelected
                    ? FontWeight.w600
                    : FontWeight.w400,
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}
