import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// ── Providers ─────────────────────────────────────────────────────────────────

final _listingDetailProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>?, String>((ref, id) async {
      if (id == 'new') return null;
      final service = ref.watch(listingApiServiceProvider);
      return service.getListing(id);
    });

// ── Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief. ─

class _L {
  _L(this.ar);
  final bool ar;

  String get newListing => ar ? 'إعلان جديد' : 'NEW LISTING';
  String get editListing => ar ? 'تعديل الإعلان' : 'EDIT LISTING';
  String get unlist => ar ? 'إلغاء النشر' : 'Unlist';
  String get publish => ar ? 'نشر' : 'Publish';
  String get save => ar ? 'حفظ' : 'Save';
  String get loadFailed => ar ? 'فشل تحميل الإعلان' : 'Failed to load listing';

  String get tabDetails => ar ? 'التفاصيل' : 'Details';
  String get tabPricing => ar ? 'التسعير' : 'Pricing';
  String get tabLocation => ar ? 'الموقع' : 'Location';
  String get tabAmenities => ar ? 'المرافق' : 'Amenities';
  String get tabMedia => ar ? 'الوسائط' : 'Media';

  // Details tab
  String get titleSection => ar ? 'العنوان' : 'Title';
  String get titleEnHint =>
      ar ? 'العنوان (بالإنجليزية) *' : 'Title (English) *';
  String get titleArHint => ar ? 'العنوان (بالعربية)' : 'العنوان (Arabic)';
  String get descriptionSection => ar ? 'الوصف' : 'Description';
  String get descEnHint => ar ? 'الوصف (بالإنجليزية)' : 'Description (English)';
  String get descArHint => ar ? 'الوصف (بالعربية)' : 'الوصف (Arabic)';
  String get specificationsSection => ar ? 'المواصفات' : 'Specifications';
  String get bedrooms => ar ? 'غرف النوم' : 'Bedrooms';
  String get bathrooms => ar ? 'الحمامات' : 'Bathrooms';
  String get floor => ar ? 'الطابق' : 'Floor';
  String get sizeSqft => ar ? 'المساحة (قدم مربع)' : 'Size (sqft)';
  String get parking => ar ? 'مواقف السيارات' : 'Parking';
  String get furnishingSection => ar ? 'الأثاث' : 'Furnishing';
  String get viewTypeSection => ar ? 'نوع الإطلالة' : 'View type';

  // Pricing tab
  String get rentSection => ar ? 'الإيجار' : 'Rent';
  String get annualRentHint =>
      ar ? 'الإيجار السنوي (درهم)' : 'Annual rent (AED)';
  String get depositHint => ar ? 'التأمين (درهم)' : 'Security deposit (AED)';
  String get paymentTermsSection => ar ? 'شروط الدفع' : 'Payment terms';
  String get minLeaseMonths =>
      ar ? 'أقل مدة إيجار (أشهر)' : 'Min lease (months)';
  String get chequesAccepted =>
      ar ? 'عدد الشيكات المقبولة' : 'Cheques accepted';
  String get utilitiesSection => ar ? 'المرافق المشمولة' : 'Utilities included';
  String get dewaLabel =>
      ar ? 'الكهرباء والماء (ديوا)' : 'DEWA (electricity & water)';
  String get chillerLabel =>
      ar ? 'التبريد المركزي' : 'District cooling (chiller)';
  String get availabilitySection => ar ? 'التوفر' : 'Availability';
  String get availableFromHint =>
      ar ? 'متاح من (اختياري)' : 'Available from (optional)';

  // Location tab
  String get pinLocationSection => ar ? 'تحديد الموقع' : 'Pin location';
  String get tapMapHint => ar
      ? 'اضغط على الخريطة لتحديد الموقع الدقيق'
      : 'Tap the map to set the exact location';
  String latLng(String lat, String lng) =>
      ar ? 'خط العرض: $lat، خط الطول: $lng' : 'Lat: $lat, Lng: $lng';
  String get clearLocation => ar ? 'مسح الموقع' : 'Clear location';

  // Amenities tab
  String amenitiesSelected(int n) =>
      ar ? '$n مرفقًا مختارًا' : '$n amenities selected';

  // Media tab
  String photosCount(int n) => ar ? '$n صورة' : '$n photos';
  String pendingUpload(int n) => ar ? 'قيد الرفع ($n)' : 'Pending upload ($n)';
  String get gallery => ar ? 'المعرض' : 'Gallery';
  String get camera => ar ? 'الكاميرا' : 'Camera';
  String get uploadHint => ar
      ? 'يتم رفع الصور عند الضغط على حفظ.'
      : 'Photos are uploaded when you tap Save.';
  String get cover => ar ? 'الغلاف' : 'Cover';
  String get couldNotPickImage =>
      ar ? 'تعذّر اختيار الصورة' : 'Could not pick image';
  String get deleteFailed => ar ? 'فشل الحذف' : 'Delete failed';

  // Actions / messages
  String get titleRequired =>
      ar ? 'العنوان (بالإنجليزية) مطلوب' : 'Title (English) is required';
  String get listingCreated => ar ? 'تم إنشاء الإعلان' : 'Listing created';
  String get savedMsg => ar ? 'تم الحفظ' : 'Saved';
  String errorMsg(String e) => ar ? 'خطأ: $e' : 'Error: $e';

  String furnishing(String value) => switch (value) {
    'UNFURNISHED' => ar ? 'غير مفروش' : 'Unfurnished',
    'SEMI_FURNISHED' => ar ? 'مفروش جزئيًا' : 'Semi-furnished',
    'FULLY_FURNISHED' => ar ? 'مفروش بالكامل' : 'Fully furnished',
    _ => value,
  };

  String viewType(String value) => switch (value) {
    'SEA' => ar ? 'بحرية' : 'Sea',
    'CITY' => ar ? 'على المدينة' : 'City',
    'POOL' => ar ? 'على المسبح' : 'Pool',
    'GARDEN' => ar ? 'على الحديقة' : 'Garden',
    'STREET' => ar ? 'على الشارع' : 'Street',
    'COMMUNITY' => ar ? 'على المجتمع' : 'Community',
    'OTHER' => ar ? 'أخرى' : 'Other',
    _ => value,
  };
}

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingEditScreen extends ConsumerStatefulWidget {
  final String listingId; // 'new' for create
  const ListingEditScreen({super.key, required this.listingId});

  @override
  ConsumerState<ListingEditScreen> createState() => _ListingEditScreenState();
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
    final amenities = (l['amenities'] as List? ?? [])
        .cast<Map<String, dynamic>>();
    _amenities.addAll(
      amenities.map((a) => a['amenity'] as String?).whereType<String>(),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final isNew = _listingId == null;
    final detailAsync = ref.watch(_listingDetailProvider(widget.listingId));

    if (isNew) _loaded = true;

    // Populate form fields when listing data loads — use ref.listen to keep
    // side effects out of the build phase.
    ref.listen<AsyncValue<Map<String, dynamic>?>>(
      _listingDetailProvider(widget.listingId),
      (_, next) {
        if (!isNew) {
          next.whenData((l) {
            if (l != null) _populateFromListing(l);
          });
        }
      },
    );

    final status = isNew
        ? 'DRAFT'
        : (detailAsync.valueOrNull?['status'] as String? ?? 'DRAFT');

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ChromeHeader(
            l: l,
            isNew: isNew,
            status: status,
            saving: _saving,
            publishing: _publishing,
            onSave: _saving ? null : _save,
            onTogglePublish: (!isNew && !_publishing)
                ? () => _togglePublish(status)
                : null,
          ),
          _MiftahTabBar(tabs: _tabs, l: l),
          Expanded(
            child: detailAsync.when(
              loading: () => isNew
                  ? _buildForm(l)
                  : Center(
                      child: CircularProgressIndicator(color: AppColors.accent),
                    ),
              error: (e, _) => ErrorState(
                message: l.loadFailed,
                onRetry: () =>
                    ref.invalidate(_listingDetailProvider(widget.listingId)),
              ),
              data: (_) => _buildForm(l),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildForm(_L l) {
    return TabBarView(
      controller: _tabs,
      children: [
        _DetailsTab(state: this, l: l),
        _PricingTab(state: this, l: l),
        _LocationTab(state: this, l: l),
        _AmenitiesTab(state: this, l: l),
        _MediaTab(state: this, l: l),
      ],
    );
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  Future<void> _save() async {
    final l = _L(context.isAr);
    if (_titleEnCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.titleRequired)));
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
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(l.listingCreated)));
          context.go('/listings/$_listingId');
        }
      } else {
        await service.updateListing(_listingId!, data);
        await _flushPendingUploads();
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(l.savedMsg)));
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.errorMsg('$e'))));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _togglePublish(String currentStatus) async {
    if (_listingId == null) return;
    final l = _L(context.isAr);
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
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.errorMsg('$e'))));
      }
    } finally {
      if (mounted) setState(() => _publishing = false);
    }
  }

  Map<String, dynamic> _buildPayload() {
    final annualRent = double.tryParse(_rentCtrl.text.trim());
    final deposit = double.tryParse(_depositCtrl.text.trim());
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
      'annualRent': ?annualRent,
      'securityDeposit': ?deposit,
      if (_minLeaseMonths != null) 'minLeaseMonths': _minLeaseMonths,
      if (_cheques != null) 'chequesAccepted': _cheques,
      'dewaIncluded': _dewaIncluded,
      'chillerIncluded': _chillerIncluded,
      if (_availableFrom != null) 'availableFrom': _availableFrom,
      'lat': ?_lat,
      'lng': ?_lng,
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
    setState(() => _uploadingMedia = true);
    final service = ref.read(listingApiServiceProvider);
    try {
      for (final file in List.of(_pendingUploads)) {
        final bytes = await file.readAsBytes();
        final name = file.path.split('/').last;
        await service.uploadMedia(
          _listingId!,
          bytes,
          name,
          isCover: _existingMedia.isEmpty && _pendingUploads.first == file,
        );
        _pendingUploads.remove(file);
      }
      ref.invalidate(_listingDetailProvider(widget.listingId));
    } finally {
      if (mounted) {
        setState(() => _uploadingMedia = false);
      }
    }
  }
}

// ── Chrome header ─────────────────────────────────────────────────────────────

class _ChromeHeader extends StatelessWidget {
  final _L l;
  final bool isNew;
  final String status;
  final bool saving;
  final bool publishing;
  final VoidCallback? onSave;
  final VoidCallback? onTogglePublish;

  const _ChromeHeader({
    required this.l,
    required this.isNew,
    required this.status,
    required this.saving,
    required this.publishing,
    required this.onSave,
    required this.onTogglePublish,
  });

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
          padding: const EdgeInsets.fromLTRB(20, 14, 12, 14),
          child: Row(
            children: [
              IconButton(
                onPressed: () => context.pop(),
                icon: Icon(
                  context.isAr ? Icons.arrow_forward : Icons.arrow_back,
                  color: Colors.white,
                  size: 20,
                ),
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  isNew ? l.newListing : l.editListing,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        )
                      : GoogleFonts.cinzel(
                          fontSize: 15,
                          letterSpacing: 2.4,
                          color: Colors.white,
                        ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (!isNew) ...[
                _HeaderAction(
                  label: status == 'PUBLISHED' ? l.unlist : l.publish,
                  icon: status == 'PUBLISHED'
                      ? Icons.visibility_off_outlined
                      : Icons.publish_outlined,
                  loading: publishing,
                  ar: l.ar,
                  onTap: onTogglePublish,
                ),
                const SizedBox(width: 16),
              ],
              _HeaderAction(
                label: l.save,
                icon: Icons.check,
                loading: saving,
                emphasize: true,
                ar: l.ar,
                onTap: onSave,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HeaderAction extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool loading;
  final bool ar;
  final bool emphasize;
  final VoidCallback? onTap;

  const _HeaderAction({
    required this.label,
    required this.icon,
    required this.loading,
    required this.ar,
    this.emphasize = false,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = emphasize
        ? AppColors.accent
        : Colors.white.withValues(alpha: 0.82);
    return GestureDetector(
      onTap: onTap,
      child: Opacity(
        opacity: onTap == null && !loading ? 0.4 : 1,
        child: loading
            ? SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2, color: color),
              )
            : Text(
                ar ? label : label.toUpperCase(),
                style:
                    (ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                      fontSize: ar ? 13 : 11.5,
                      fontWeight: FontWeight.w600,
                      letterSpacing: ar ? 0 : 1.4,
                      color: color,
                    ),
              ),
      ),
    );
  }
}

class _MiftahTabBar extends StatelessWidget {
  final TabController tabs;
  final _L l;
  const _MiftahTabBar({required this.tabs, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      color: m.surface,
      child: TabBar(
        controller: tabs,
        isScrollable: true,
        labelStyle:
            (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              letterSpacing: l.ar ? 0 : 0.4,
            ),
        unselectedLabelStyle:
            (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
              fontSize: 13,
              letterSpacing: l.ar ? 0 : 0.4,
            ),
        labelColor: m.isDark ? AppColors.accent : AppColors.primary,
        unselectedLabelColor: m.textMuted,
        indicatorColor: AppColors.accent,
        tabs: [
          Tab(text: l.tabDetails),
          Tab(text: l.tabPricing),
          Tab(text: l.tabLocation),
          Tab(text: l.tabAmenities),
          Tab(text: l.tabMedia),
        ],
      ),
    );
  }
}

// ── Tab: Details ──────────────────────────────────────────────────────────────

class _DetailsTab extends StatefulWidget {
  final _ListingEditScreenState state;
  final _L l;
  const _DetailsTab({required this.state, required this.l});

  @override
  State<_DetailsTab> createState() => _DetailsTabState();
}

class _DetailsTabState extends State<_DetailsTab> {
  _ListingEditScreenState get s => widget.state;
  _L get l => widget.l;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 20, 16, AppInsets.bottomNav(context)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel(l.titleSection),
          _Field(controller: s._titleEnCtrl, hint: l.titleEnHint),
          const SizedBox(height: 10),
          _Field(
            controller: s._titleArCtrl,
            hint: l.titleArHint,
            textDirection: TextDirection.rtl,
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.descriptionSection),
          _Field(controller: s._descEnCtrl, hint: l.descEnHint, maxLines: 4),
          const SizedBox(height: 10),
          _Field(
            controller: s._descArCtrl,
            hint: l.descArHint,
            maxLines: 4,
            textDirection: TextDirection.rtl,
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.specificationsSection),
          _NumRow(
            children: [
              _NumField(
                label: l.bedrooms,
                value: s._bedrooms,
                onChanged: (v) => setState(() => s._bedrooms = v),
              ),
              _NumField(
                label: l.bathrooms,
                value: s._bathrooms,
                onChanged: (v) => setState(() => s._bathrooms = v),
              ),
              _NumField(
                label: l.floor,
                value: s._floor,
                onChanged: (v) => setState(() => s._floor = v),
              ),
            ],
          ),
          const SizedBox(height: 10),
          _NumRow(
            children: [
              _NumField(
                label: l.sizeSqft,
                value: s._sizeSqft?.round(),
                onChanged: (v) => setState(() => s._sizeSqft = v?.toDouble()),
              ),
              _NumField(
                label: l.parking,
                value: s._parkingSpaces,
                onChanged: (v) => setState(() => s._parkingSpaces = v),
              ),
            ],
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.furnishingSection),
          const SizedBox(height: 8),
          _OptionPills(
            options: _ListingEditScreenState._furnishingOptions
                .map((o) => (o.$1, l.furnishing(o.$1)))
                .toList(),
            selected: s._furnishing,
            onSelect: (v) => setState(() => s._furnishing = v),
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.viewTypeSection),
          const SizedBox(height: 8),
          _OptionPills(
            options: _ListingEditScreenState._viewTypeOptions
                .map((o) => (o.$1, l.viewType(o.$1)))
                .toList(),
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
  final _L l;
  const _PricingTab({required this.state, required this.l});

  @override
  State<_PricingTab> createState() => _PricingTabState();
}

class _PricingTabState extends State<_PricingTab> {
  _ListingEditScreenState get s => widget.state;
  _L get l => widget.l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 20, 16, AppInsets.bottomNav(context)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel(l.rentSection),
          _Field(
            controller: s._rentCtrl,
            hint: l.annualRentHint,
            keyboardType: TextInputType.number,
          ),
          const SizedBox(height: 10),
          _Field(
            controller: s._depositCtrl,
            hint: l.depositHint,
            keyboardType: TextInputType.number,
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.paymentTermsSection),
          const SizedBox(height: 8),
          _NumRow(
            children: [
              _NumField(
                label: l.minLeaseMonths,
                value: s._minLeaseMonths,
                onChanged: (v) => setState(() => s._minLeaseMonths = v),
              ),
              _NumField(
                label: l.chequesAccepted,
                value: s._cheques,
                onChanged: (v) => setState(() => s._cheques = v),
              ),
            ],
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.utilitiesSection),
          const SizedBox(height: 8),
          _SwitchRow(
            label: l.dewaLabel,
            value: s._dewaIncluded,
            onChanged: (v) => setState(() => s._dewaIncluded = v),
          ),
          const SizedBox(height: 8),
          _SwitchRow(
            label: l.chillerLabel,
            value: s._chillerIncluded,
            onChanged: (v) => setState(() => s._chillerIncluded = v),
          ),
          const SizedBox(height: 20),
          _SectionLabel(l.availabilitySection),
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
                setState(
                  () => s._availableFrom = date
                      .toIso8601String()
                      .split('T')
                      .first,
                );
              }
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
              decoration: BoxDecoration(
                color: m.surface,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: m.border),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.calendar_today_outlined,
                    size: 18,
                    color: m.textMuted,
                  ),
                  const SizedBox(width: 10),
                  Text(
                    s._availableFrom ?? l.availableFromHint,
                    style:
                        (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                          fontSize: 14,
                          color: s._availableFrom != null
                              ? m.textPrimary
                              : m.textMuted,
                        ),
                  ),
                  if (s._availableFrom != null) ...[
                    const Spacer(),
                    GestureDetector(
                      onTap: () => setState(() => s._availableFrom = null),
                      child: Icon(Icons.close, size: 16, color: m.textMuted),
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
  final _L l;
  const _LocationTab({required this.state, required this.l});

  @override
  State<_LocationTab> createState() => _LocationTabState();
}

class _LocationTabState extends State<_LocationTab> {
  _ListingEditScreenState get s => widget.state;
  _L get l => widget.l;

  static const _defaultPos = LatLng(25.2048, 55.2708); // Dubai

  LatLng get _markerPos =>
      s._lat != null && s._lng != null ? LatLng(s._lat!, s._lng!) : _defaultPos;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 20, 16, AppInsets.bottomNav(context)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel(l.pinLocationSection),
          const SizedBox(height: 4),
          Text(
            l.tapMapHint,
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.josefinSans)(fontSize: 12, color: m.textMuted),
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(14),
            child: Container(
              decoration: BoxDecoration(border: Border.all(color: m.border)),
              child: SizedBox(
                height: 280,
                child: GoogleMap(
                  initialCameraPosition: CameraPosition(
                    target: _markerPos,
                    zoom: s._lat != null ? 15 : 11,
                  ),
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
                          ),
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
          ),
          if (s._lat != null) ...[
            const SizedBox(height: 12),
            Text(
              l.latLng(s._lat!.toStringAsFixed(6), s._lng!.toStringAsFixed(6)),
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(fontSize: 12, color: m.textMuted),
            ),
            GestureDetector(
              onTap: () => setState(() {
                s._lat = null;
                s._lng = null;
              }),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Text(
                  l.clearLocation,
                  style:
                      (l.ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: m.isDark
                            ? AppColors.accent
                            : AppColors.accentDark,
                      ),
                ),
              ),
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
  final _L l;
  const _AmenitiesTab({required this.state, required this.l});

  @override
  State<_AmenitiesTab> createState() => _AmenitiesTabState();
}

class _AmenitiesTabState extends State<_AmenitiesTab> {
  _ListingEditScreenState get s => widget.state;
  _L get l => widget.l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 20, 16, AppInsets.bottomNav(context)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionLabel(l.amenitiesSelected(s._amenities.length)),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: kAllAmenities.map((a) {
              final isSelected = s._amenities.contains(a);
              final label = amenityLabel(a, ar: l.ar);
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
                    horizontal: 12,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: isSelected ? AppColors.primary : m.surface,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(
                      color: isSelected ? AppColors.accent : m.border,
                    ),
                  ),
                  child: Text(
                    label,
                    style:
                        (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                          fontSize: 12,
                          color: isSelected
                              ? AppColors.accent
                              : m.textSecondary,
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
  final _L l;
  const _MediaTab({required this.state, required this.l});

  @override
  State<_MediaTab> createState() => _MediaTabState();
}

class _MediaTabState extends State<_MediaTab> {
  _ListingEditScreenState get s => widget.state;
  _L get l => widget.l;
  final _picker = ImagePicker();

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 20, 16, AppInsets.bottomNav(context)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _SectionLabel(
                l.photosCount(
                  s._existingMedia.length + s._pendingUploads.length,
                ),
              ),
              const Spacer(),
              if (s._uploadingMedia)
                SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: AppColors.accent,
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),

          // Existing server media
          if (s._existingMedia.isNotEmpty)
            GridView.builder(
              // Nested in a scroll view: without this the sliver auto-pads
              // with MediaQuery.padding, which under extendBody carries the
              // floating nav height and opens a gap below the content.
              padding: EdgeInsets.zero,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
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
                      child: Image.network(
                        url,
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) =>
                            Container(color: m.surfaceAlt),
                      ),
                    ),
                    if (isCover)
                      PositionedDirectional(
                        top: 4,
                        start: 4,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: AppColors.primary,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            l.cover,
                            style:
                                (l.ar
                                ? GoogleFonts.notoNaskhArabic
                                : GoogleFonts.josefinSans)(
                                  fontSize: 9,
                                  color: AppColors.accent,
                                  fontWeight: FontWeight.w600,
                                ),
                          ),
                        ),
                      ),
                    PositionedDirectional(
                      top: 4,
                      end: 4,
                      child: GestureDetector(
                        onTap: () =>
                            _deleteExisting(media['id'] as String? ?? ''),
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.55),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.close,
                            size: 14,
                            color: Colors.white,
                          ),
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
            _SectionLabel(l.pendingUpload(s._pendingUploads.length)),
            const SizedBox(height: 8),
            GridView.builder(
              // Nested in a scroll view: without this the sliver auto-pads
              // with MediaQuery.padding, which under extendBody carries the
              // floating nav height and opens a gap below the content.
              padding: EdgeInsets.zero,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
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
                    child: Image.file(s._pendingUploads[i], fit: BoxFit.cover),
                  ),
                  PositionedDirectional(
                    top: 4,
                    end: 4,
                    child: GestureDetector(
                      onTap: () =>
                          setState(() => s._pendingUploads.removeAt(i)),
                      child: Container(
                        width: 24,
                        height: 24,
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.55),
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.close,
                          size: 14,
                          color: Colors.white,
                        ),
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
                  label: l.gallery,
                  ar: l.ar,
                  onTap: () => _pick(ImageSource.gallery),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _AddPhotoBtn(
                  icon: Icons.camera_alt_outlined,
                  label: l.camera,
                  ar: l.ar,
                  onTap: () => _pick(ImageSource.camera),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            l.uploadHint,
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.josefinSans)(fontSize: 11, color: m.textMuted),
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
        setState(
          () => s._pendingUploads.addAll(files.map((f) => File(f.path))),
        );
      } else {
        final file = await _picker.pickImage(source: source, imageQuality: 80);
        if (file == null) return;
        setState(() => s._pendingUploads.add(File(file.path)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('${l.couldNotPickImage}: $e')));
      }
    }
  }

  Future<void> _deleteExisting(String mediaId) async {
    if (s._listingId == null) return;
    try {
      final service = s.ref.read(listingApiServiceProvider);
      await service.deleteMedia(s._listingId!, mediaId);
      setState(() => s._existingMedia.removeWhere((m) => m['id'] == mediaId));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('${l.deleteFailed}: $e')));
      }
    }
  }
}

class _AddPhotoBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool ar;
  final VoidCallback onTap;
  const _AddPhotoBtn({
    required this.icon,
    required this.label,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.accent.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: AppColors.accent.withValues(alpha: 0.35),
            style: BorderStyle.solid,
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 20, color: AppColors.accentDark),
            const SizedBox(width: 8),
            Text(
              label,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    fontSize: 13,
                    color: AppColors.accentDark,
                    fontWeight: FontWeight.w600,
                  ),
            ),
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
    final ar = context.isAr;
    return Padding(
      padding: const EdgeInsetsDirectional.only(bottom: 9),
      child: Text(
        ar ? text : text.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 12.5,
                fontWeight: FontWeight.w600,
                color: AppColors.accentDark,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: AppColors.accentDark,
                letterSpacing: 2.0,
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
    final m = context.miftah;
    final fieldIsAr = textDirection == TextDirection.rtl;
    return TextField(
      controller: controller,
      maxLines: maxLines,
      keyboardType: keyboardType,
      textDirection: textDirection,
      style: (fieldIsAr
          ? GoogleFonts.notoNaskhArabic
          : GoogleFonts.josefinSans)(fontSize: 14, color: m.textPrimary),
      decoration: InputDecoration(
        hintText: hint,
        hintStyle: (fieldIsAr
            ? GoogleFonts.notoNaskhArabic
            : GoogleFonts.josefinSans)(fontSize: 14, color: m.textMuted),
        filled: true,
        fillColor: m.surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: m.border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: m.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.accent, width: 1.5),
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 14,
          vertical: 12,
        ),
      ),
    );
  }
}

class _NumField extends StatelessWidget {
  final String label;
  final int? value;
  final ValueChanged<int?> onChanged;
  const _NumField({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Expanded(
      child: TextField(
        controller: TextEditingController(text: value != null ? '$value' : ''),
        keyboardType: TextInputType.number,
        style: (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
          fontSize: 14,
          color: m.textPrimary,
        ),
        onChanged: (v) => onChanged(int.tryParse(v)),
        decoration: InputDecoration(
          labelText: label,
          labelStyle: (ar
              ? GoogleFonts.notoNaskhArabic
              : GoogleFonts.josefinSans)(fontSize: 12, color: m.textMuted),
          filled: true,
          fillColor: m.surface,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: m.border),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: m.border),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.accent, width: 1.5),
          ),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 12,
            vertical: 10,
          ),
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
      children: children.expand((w) => [w, const SizedBox(width: 10)]).toList()
        ..removeLast(),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;
  const _SwitchRow({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    fontSize: 14,
                    color: m.textPrimary,
                  ),
            ),
          ),
          Switch.adaptive(
            value: value,
            onChanged: onChanged,
            activeThumbColor: AppColors.accent,
            activeTrackColor: AppColors.accent.withValues(alpha: 0.35),
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
  const _OptionPills({
    required this.options,
    required this.selected,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: options.map((opt) {
        final isSelected = selected == opt.$1;
        return GestureDetector(
          onTap: () => onSelect(isSelected ? null : opt.$1),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            decoration: BoxDecoration(
              color: isSelected ? AppColors.primary : m.surface,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: isSelected ? AppColors.accent : m.borderStrong,
              ),
            ),
            child: Text(
              opt.$2,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    fontSize: 13,
                    color: isSelected ? AppColors.accent : m.textSecondary,
                    fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
                  ),
            ),
          ),
        );
      }).toList(),
    );
  }
}
