import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/facility_provider.dart';
import '../../providers/gate_pass_provider.dart' show propertiesProvider;

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المرافق ومواقف السيارات' : 'Facilities';
  String get property => ar ? 'العقار' : 'Property';
  String get amenities => ar ? 'المرافق' : 'Amenities';
  String get parking => ar ? 'المواقف' : 'Parking';
  String get loadFailed =>
      ar ? 'فشل تحميل المرافق' : 'Failed to load facilities';
  String get propertiesLoadFailed =>
      ar ? 'فشل تحميل العقارات' : 'Failed to load properties';
  String get noProperties => ar ? 'لا توجد عقارات' : 'No properties';
  String get noAmenities => ar ? 'لا توجد مرافق بعد' : 'No amenities yet';
  String get noAmenitiesSub => ar
      ? 'أضف مسبحًا أو صالة رياضية أو قاعة مناسبات ليتمكن المستأجرون من طلب حجزها.'
      : 'Add a pool, gym or hall so renters can request it.';
  String get noSpots => ar ? 'لا توجد مواقف بعد' : 'No parking spots yet';
  String get noSpotsSub => ar
      ? 'أضف أرقام المواقف — يمكن إضافة عدة مواقف دفعة واحدة.'
      : 'Add numbered spots — bulk entry adds many at once.';
  String get addAmenity => ar ? 'إضافة مرفق' : 'Add amenity';
  String get editAmenity => ar ? 'تعديل المرفق' : 'Edit amenity';
  String get addParking => ar ? 'إضافة مواقف' : 'Add parking';
  String get editParking => ar ? 'تعديل الموقف' : 'Edit spot';
  String get nameEn => ar ? 'الاسم (إنجليزي)' : 'Name (English)';
  String get nameAr => ar ? 'الاسم (عربي)' : 'Name (Arabic)';
  String get nameRequired =>
      ar ? 'أدخل الاسم بالإنجليزية' : 'Enter the English name';
  String get description => ar ? 'الوصف (اختياري)' : 'Description (optional)';
  String get bookable => ar ? 'قابل للحجز' : 'Bookable';
  String get bookableSub => ar
      ? 'يمكن للمستأجرين إرسال طلبات حجز لهذا المرفق'
      : 'Renters can send booking requests for it';
  String get towers => ar ? 'الأبراج' : 'Towers';
  String get allTowers => ar ? 'كل الأبراج' : 'All towers';
  String get towersHint => ar
      ? 'بدون اختيار = ظاهر لكل الأبراج. الاختيار يقصر الظهور على الأبراج المحددة.'
      : 'None selected = visible to all towers. Selecting restricts visibility.';
  String get spotNumber => ar ? 'رقم الموقف' : 'Spot number';
  String get spotNumbers => ar ? 'أرقام المواقف' : 'Spot numbers';
  String get spotNumbersHint => ar
      ? 'مفصولة بفواصل أو أسطر: B1-01, B1-02, B1-03'
      : 'Comma or newline separated: B1-01, B1-02, B1-03';
  String get spotRequired => ar ? 'أدخل رقم الموقف' : 'Enter a spot number';
  String get bulkEmpty =>
      ar ? 'أدخل رقم موقف واحد على الأقل' : 'Enter at least one spot number';
  String bulkTooMany(int n) => ar
      ? 'الحد الأقصى 500 موقف لكل طلب — لقد أدخلت $n.'
      : 'Maximum 500 spots per request — you entered $n.';
  String get spotTooLong => ar
      ? 'كل رقم موقف يجب ألا يتجاوز 32 حرفًا'
      : 'Each spot number must be 32 characters or fewer';
  String get level => ar ? 'الطابق (اختياري)' : 'Level (optional)';
  String get covered => ar ? 'مظلل' : 'Covered';
  String get bulkMode => ar ? 'إضافة متعددة' : 'Bulk add';
  String get save => ar ? 'حفظ' : 'Save';
  String get saveFailed =>
      ar ? 'تعذر الحفظ. حاول مرة أخرى.' : 'Could not save. Try again.';
  String get deactivate => ar ? 'إلغاء التفعيل' : 'Deactivate';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get deactivated => ar ? 'تم إلغاء التفعيل' : 'Deactivated';
  String get deactivateFailed => ar
      ? 'تعذر إلغاء التفعيل. حاول مرة أخرى.'
      : 'Could not deactivate. Try again.';
  String get inactive => ar ? 'غير نشط' : 'Inactive';
  String get held => ar ? 'محجوز' : 'Held';
  String get notBookable => ar ? 'غير قابل للحجز' : 'Not bookable';
  String deactivateConfirm(String name) => ar
      ? 'إلغاء تفعيل "$name"؟ سيختفي عن المستأجرين وتبقى الطلبات الحالية كما هي.'
      : 'Deactivate "$name"? Renters stop seeing it; existing requests are kept.';
  String pending(int n) => ar ? '$n قيد الانتظار' : '$n pending';
  String towersCount(int n) => ar ? 'الأبراج: $n' : '$n towers';
}

String _displayName(Map<String, dynamic> row, bool ar) {
  final nameAr = row['nameAr']?.toString();
  if (ar && nameAr != null && nameAr.isNotEmpty) return nameAr;
  return row['nameEn']?.toString() ?? '—';
}

/// Splits comma/semicolon/newline-separated spot numbers, trimming blanks and
/// de-duplicating while preserving order.
List<String> _parseSpotNumbers(String raw) {
  final seen = <String>{};
  final result = <String>[];
  for (final part in raw.split(RegExp(r'[,\n;]+'))) {
    final trimmed = part.trim();
    if (trimmed.isEmpty || !seen.add(trimmed)) continue;
    result.add(trimmed);
  }
  return result;
}

/// Extracts a user-facing message from a caught error. `FacilityApiService`
/// documents two response shapes: the app's usual `{error: true, message,
/// ...}` envelope, and the booking 409 shape `{error: "message text", ...}` —
/// so this prefers `message` when it is a String, else `error` when it is a
/// String, else falls back to a generic localized message.
String _errorMessage(Object error, String fallback) {
  if (error is DioException) {
    final body = error.response?.data;
    if (body is Map) {
      final message = body['message'];
      if (message is String && message.isNotEmpty) return message;
      final err = body['error'];
      if (err is String && err.isNotEmpty) return err;
    }
  }
  return fallback;
}

/// Inventory management: free-form amenities and numbered parking spots per
/// property, each optionally scoped to specific towers.
///
/// Parameterless and self-fetching — this router rebuilds on `authProvider`
/// and would discard `extra` (see the gate-pass screens' comment in router.dart).
class FacilitiesScreen extends ConsumerStatefulWidget {
  const FacilitiesScreen({super.key});

  @override
  ConsumerState<FacilitiesScreen> createState() => _FacilitiesScreenState();
}

class _FacilitiesScreenState extends ConsumerState<FacilitiesScreen> {
  String? _propertyId;
  bool _parkingTab = false;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final properties = ref.watch(propertiesProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
        actions: [
          if (_propertyId != null)
            IconButton(
              key: const Key('facility-add'),
              icon: const Icon(Icons.add),
              onPressed: () =>
                  _parkingTab ? _openParkingSheet() : _openAmenitySheet(),
            ),
        ],
      ),
      body: properties.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.accent),
        ),
        error: (error, _) => ErrorState(
          message: l.propertiesLoadFailed,
          onRetry: () => ref.invalidate(propertiesProvider),
        ),
        data: (rows) {
          if (rows.isEmpty) {
            return EmptyState(
              icon: Icons.apartment_outlined,
              title: l.noProperties,
            );
          }
          // One property is the overwhelmingly common case; select it
          // silently rather than making the manager pick between one option.
          _propertyId ??= rows.first['id']?.toString();
          final propertyId = _propertyId!;
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    DropdownButtonFormField<String>(
                      initialValue: propertyId,
                      decoration: InputDecoration(labelText: l.property),
                      items: [
                        for (final p in rows)
                          DropdownMenuItem(
                            value: p['id']?.toString(),
                            child: Text(
                              p['name']?.toString() ?? '—',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                      onChanged: (id) => setState(() => _propertyId = id),
                    ),
                    const SizedBox(height: 12),
                    _TabToggle(
                      parking: _parkingTab,
                      l: l,
                      onChanged: (v) => setState(() => _parkingTab = v),
                    ),
                    const SizedBox(height: 4),
                  ],
                ),
              ),
              Expanded(
                child: _parkingTab
                    ? _spotList(propertyId, l)
                    : _amenityList(propertyId, l),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _amenityList(String propertyId, _L l) {
    final async = ref.watch(amenitiesProvider(propertyId));
    return RefreshIndicator(
      color: AppColors.accent,
      onRefresh: () => ref.refresh(amenitiesProvider(propertyId).future),
      child: async.when(
        loading: () => const ListShimmer(itemCount: 4),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: l.loadFailed,
            onRetry: () => ref.invalidate(amenitiesProvider(propertyId)),
          ),
        ),
        data: (items) {
          if (items.isEmpty) {
            return _Scrollable(
              child: EmptyState(
                icon: Icons.pool_outlined,
                title: l.noAmenities,
                subtitle: l.noAmenitiesSub,
                actionLabel: l.addAmenity,
                onAction: _openAmenitySheet,
              ),
            );
          }
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding:
                EdgeInsets.fromLTRB(16, 8, 16, AppInsets.bottomNav(context)),
            itemCount: items.length,
            itemBuilder: (context, i) => AnimatedListItem(
              index: i,
              child: _FacilityCard(
                row: items[i],
                parking: false,
                l: l,
                onEdit: () => _openAmenitySheet(existing: items[i]),
                onDeactivate: () => _deactivate(items[i], parking: false),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _spotList(String propertyId, _L l) {
    final async = ref.watch(parkingSpotsProvider(propertyId));
    return RefreshIndicator(
      color: AppColors.accent,
      onRefresh: () => ref.refresh(parkingSpotsProvider(propertyId).future),
      child: async.when(
        loading: () => const ListShimmer(itemCount: 4),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: l.loadFailed,
            onRetry: () => ref.invalidate(parkingSpotsProvider(propertyId)),
          ),
        ),
        data: (items) {
          if (items.isEmpty) {
            return _Scrollable(
              child: EmptyState(
                icon: Icons.local_parking_outlined,
                title: l.noSpots,
                subtitle: l.noSpotsSub,
                actionLabel: l.addParking,
                onAction: _openParkingSheet,
              ),
            );
          }
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding:
                EdgeInsets.fromLTRB(16, 8, 16, AppInsets.bottomNav(context)),
            itemCount: items.length,
            itemBuilder: (context, i) => AnimatedListItem(
              index: i,
              child: _FacilityCard(
                row: items[i],
                parking: true,
                l: l,
                onEdit: () => _openParkingSheet(existing: items[i]),
                onDeactivate: () => _deactivate(items[i], parking: true),
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _openAmenitySheet({Map<String, dynamic>? existing}) async {
    final propertyId = _propertyId;
    if (propertyId == null) return;
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _AmenitySheet(propertyId: propertyId, existing: existing),
    );
    if (changed == true) ref.invalidate(amenitiesProvider(propertyId));
  }

  Future<void> _openParkingSheet({Map<String, dynamic>? existing}) async {
    final propertyId = _propertyId;
    if (propertyId == null) return;
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _ParkingSheet(propertyId: propertyId, existing: existing),
    );
    if (changed == true) ref.invalidate(parkingSpotsProvider(propertyId));
  }

  Future<void> _deactivate(
    Map<String, dynamic> row, {
    required bool parking,
  }) async {
    final l = _L(context.isAr);
    final name = parking
        ? (row['spotNumber']?.toString() ?? '—')
        : _displayName(row, l.ar);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        content: Text(l.deactivateConfirm(name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text(l.deactivate),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final id = row['id']?.toString();
    final propertyId = _propertyId;
    if (id == null || propertyId == null) return;
    try {
      if (parking) {
        await ref.read(facilityServiceProvider).deactivateParkingSpot(id);
        ref.invalidate(parkingSpotsProvider(propertyId));
      } else {
        await ref.read(facilityServiceProvider).deactivateAmenity(id);
        ref.invalidate(amenitiesProvider(propertyId));
      }
      if (mounted) _toast(l.deactivated);
    } catch (e) {
      if (mounted) _toast(_errorMessage(e, l.deactivateFailed));
    }
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }
}

/// Amenities/Parking segmented toggle (same pattern as the gate-pass create
/// screen's `_TypeToggle`).
class _TabToggle extends StatelessWidget {
  final bool parking;
  final _L l;
  final ValueChanged<bool> onChanged;

  const _TabToggle({
    required this.parking,
    required this.l,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _segment(m, label: l.amenities, selected: !parking, value: false),
          _segment(m, label: l.parking, selected: parking, value: true),
        ],
      ),
    );
  }

  Widget _segment(
    MiftahColors m, {
    required String label,
    required bool selected,
    required bool value,
  }) {
    return Expanded(
      child: GestureDetector(
        onTap: () => onChanged(value),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          padding: const EdgeInsets.symmetric(vertical: 11),
          decoration: BoxDecoration(
            color: selected ? m.surface : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
            border: selected ? Border.all(color: m.border) : null,
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: selected ? m.textPrimary : m.textMuted,
            ),
          ),
        ),
      ),
    );
  }
}

/// One inventory row: amenity or spot, with tower/bookable/held/pending pills.
class _FacilityCard extends StatelessWidget {
  final Map<String, dynamic> row;
  final bool parking;
  final _L l;
  final VoidCallback onEdit;
  final VoidCallback onDeactivate;

  const _FacilityCard({
    required this.row,
    required this.parking,
    required this.l,
    required this.onEdit,
    required this.onDeactivate,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final active = row['active'] != false;
    final pendingCount = (row['pendingCount'] as num?)?.toInt() ?? 0;
    final buildingIds = (row['buildingIds'] as List?) ?? const [];
    final title = parking
        ? (row['spotNumber']?.toString() ?? '—')
        : _displayName(row, l.ar);
    final level = row['level']?.toString();
    final description = row['description']?.toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: active ? m.surface : m.surfaceDim,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onEdit,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    parking
                        ? Icons.local_parking_outlined
                        : Icons.pool_outlined,
                    size: 18,
                    color: AppColors.accentDark,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      title,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                    ),
                  ),
                  IconButton(
                    key: Key('deactivate-${row['id']}'),
                    visualDensity: VisualDensity.compact,
                    icon: Icon(
                      Icons.delete_outline,
                      size: 19,
                      color: active ? m.textMuted : m.border,
                    ),
                    onPressed: active ? onDeactivate : null,
                  ),
                ],
              ),
              if (!parking && description != null && description.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    description,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                      fontSize: 12.5,
                      color: m.textSecondary,
                      height: 1.4,
                    ),
                  ),
                ),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  StatusBadge(
                    label: buildingIds.isEmpty
                        ? l.allTowers
                        : l.towersCount(buildingIds.length),
                    color: AppColors.accentDark,
                  ),
                  if (parking && level != null && level.isNotEmpty)
                    StatusBadge(label: level, color: m.textSecondary),
                  if (parking && row['covered'] == true)
                    StatusBadge(label: l.covered, color: m.textSecondary),
                  // Inactive spots are hidden from renters entirely, so a
                  // held/available pill on them would describe a state
                  // nobody sees — show nothing rather than a stale badge.
                  if (parking && active && row['held'] == true)
                    StatusBadge(label: l.held, color: m.warning),
                  if (!parking && row['bookable'] == false)
                    StatusBadge(label: l.notBookable, color: m.textMuted),
                  if (!active) StatusBadge(label: l.inactive, color: m.textMuted),
                  if (pendingCount > 0)
                    StatusBadge(label: l.pending(pendingCount), color: m.warning),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Tower multi-select. No selection = visible to every tower (the server keeps
/// zero scope rows); selecting towers restricts visibility to them.
class _TowerChips extends ConsumerWidget {
  final String propertyId;
  final Set<String> selected;
  final ValueChanged<Set<String>> onChanged;
  final _L l;

  const _TowerChips({
    required this.propertyId,
    required this.selected,
    required this.onChanged,
    required this.l,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final buildings = ref.watch(buildingsProvider(propertyId));
    return buildings.when(
      loading: () => const ShimmerLoading(height: 36),
      // A property with no towers has nothing to scope; treat a failed tower
      // lookup the same as "no towers" instead of blocking the whole sheet.
      error: (error, _) => Text(
        l.allTowers,
        style: GoogleFonts.josefinSans(fontSize: 12.5, color: m.textMuted),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return Text(
            l.allTowers,
            style: GoogleFonts.josefinSans(fontSize: 12.5, color: m.textMuted),
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final b in rows)
                  FilterChip(
                    label: Text((b['nameEn'] ?? b['name'] ?? '—').toString()),
                    selected: selected.contains(b['id']?.toString()),
                    selectedColor: AppColors.accent.withValues(alpha: 0.25),
                    checkmarkColor: AppColors.accentDark,
                    onSelected: (on) {
                      final id = b['id']?.toString();
                      if (id == null) return;
                      final next = {...selected};
                      on ? next.add(id) : next.remove(id);
                      onChanged(next);
                    },
                  ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              l.towersHint,
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(
                fontSize: 11.5,
                color: m.textMuted,
                height: 1.4,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _AmenitySheet extends ConsumerStatefulWidget {
  final String propertyId;
  final Map<String, dynamic>? existing;
  const _AmenitySheet({required this.propertyId, this.existing});

  @override
  ConsumerState<_AmenitySheet> createState() => _AmenitySheetState();
}

class _AmenitySheetState extends ConsumerState<_AmenitySheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameEnCtrl;
  late final TextEditingController _nameArCtrl;
  late final TextEditingController _descCtrl;
  late bool _bookable;
  late Set<String> _towerIds;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _nameEnCtrl = TextEditingController(text: e?['nameEn']?.toString() ?? '');
    _nameArCtrl = TextEditingController(text: e?['nameAr']?.toString() ?? '');
    _descCtrl =
        TextEditingController(text: e?['description']?.toString() ?? '');
    _bookable = e == null || e['bookable'] == true;
    _towerIds = {
      for (final id in (e?['buildingIds'] as List? ?? const [])) id.toString(),
    };
  }

  @override
  void dispose() {
    _nameEnCtrl.dispose();
    _nameArCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final l = _L(context.isAr);
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    final service = ref.read(facilityServiceProvider);
    final nameAr = _nameArCtrl.text.trim();
    final description = _descCtrl.text.trim();
    try {
      final existing = widget.existing;
      if (existing == null) {
        await service.createAmenity({
          'propertyId': widget.propertyId,
          'nameEn': _nameEnCtrl.text.trim(),
          if (nameAr.isNotEmpty) 'nameAr': nameAr,
          if (description.isNotEmpty) 'description': description,
          'bookable': _bookable,
          'buildingIds': _towerIds.toList(),
        });
      } else {
        // Patch semantics: absent = unchanged. A non-null buildingIds
        // REPLACES the scope set, which is exactly what the chips represent.
        await service.updateAmenity(existing['id'].toString(), {
          'nameEn': _nameEnCtrl.text.trim(),
          if (nameAr.isNotEmpty) 'nameAr': nameAr,
          if (description.isNotEmpty) 'description': description,
          'bookable': _bookable,
          'buildingIds': _towerIds.toList(),
        });
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_errorMessage(e, l.saveFailed)),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: Form(
              key: _formKey,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.existing == null ? l.addAmenity : l.editAmenity,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 16,
                            letterSpacing: 1.6,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('amenity-name-en'),
                    controller: _nameEnCtrl,
                    maxLength: 160,
                    decoration:
                        InputDecoration(labelText: l.nameEn, counterText: ''),
                    validator: (v) =>
                        (v == null || v.trim().isEmpty) ? l.nameRequired : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _nameArCtrl,
                    maxLength: 160,
                    textDirection: TextDirection.rtl,
                    decoration:
                        InputDecoration(labelText: l.nameAr, counterText: ''),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _descCtrl,
                    maxLength: 500,
                    maxLines: 3,
                    decoration: InputDecoration(
                      labelText: l.description,
                      counterText: '',
                    ),
                  ),
                  const SizedBox(height: 4),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _bookable,
                    title: Text(
                      l.bookable,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
                    ),
                    subtitle: Text(
                      l.bookableSub,
                      style: TextStyle(fontSize: 12, color: m.textSecondary),
                    ),
                    onChanged: (v) => setState(() => _bookable = v),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    l.towers,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                      color: m.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  _TowerChips(
                    propertyId: widget.propertyId,
                    selected: _towerIds,
                    l: l,
                    onChanged: (next) => setState(() => _towerIds = next),
                  ),
                  const SizedBox(height: 20),
                  GoldButton(
                    key: const Key('amenity-save'),
                    label: l.save,
                    height: 48,
                    onPressed: _saving ? null : _save,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ParkingSheet extends ConsumerStatefulWidget {
  final String propertyId;
  final Map<String, dynamic>? existing;
  const _ParkingSheet({required this.propertyId, this.existing});

  @override
  ConsumerState<_ParkingSheet> createState() => _ParkingSheetState();
}

class _ParkingSheetState extends ConsumerState<_ParkingSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _spotCtrl;
  late final TextEditingController _levelCtrl;
  late bool _covered;
  late Set<String> _towerIds;
  bool _bulk = false;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _spotCtrl = TextEditingController(text: e?['spotNumber']?.toString() ?? '');
    _levelCtrl = TextEditingController(text: e?['level']?.toString() ?? '');
    _covered = e == null || e['covered'] == true;
    _towerIds = {
      for (final id in (e?['buildingIds'] as List? ?? const [])) id.toString(),
    };
  }

  @override
  void dispose() {
    _spotCtrl.dispose();
    _levelCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final l = _L(context.isAr);
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    final service = ref.read(facilityServiceProvider);
    final level = _levelCtrl.text.trim();
    try {
      final existing = widget.existing;
      if (existing != null) {
        await service.updateParkingSpot(existing['id'].toString(), {
          'spotNumber': _spotCtrl.text.trim(),
          if (level.isNotEmpty) 'level': level,
          'covered': _covered,
          'buildingIds': _towerIds.toList(),
        });
      } else if (_bulk) {
        // The >500 / empty-parse guards live in the field's validator below,
        // so the request is never sent for either case.
        await service.bulkCreateParkingSpots({
          'propertyId': widget.propertyId,
          'spotNumbers': _parseSpotNumbers(_spotCtrl.text),
          if (level.isNotEmpty) 'level': level,
          'covered': _covered,
          'buildingIds': _towerIds.toList(),
        });
      } else {
        await service.createParkingSpot({
          'propertyId': widget.propertyId,
          'spotNumber': _spotCtrl.text.trim(),
          if (level.isNotEmpty) 'level': level,
          'covered': _covered,
          'buildingIds': _towerIds.toList(),
        });
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_errorMessage(e, l.saveFailed)),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final creating = widget.existing == null;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: Form(
              key: _formKey,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    creating ? l.addParking : l.editParking,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 16,
                            letterSpacing: 1.6,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 8),
                  if (creating)
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _bulk,
                      title: Text(
                        l.bulkMode,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                      ),
                      onChanged: (v) => setState(() => _bulk = v),
                    ),
                  const SizedBox(height: 4),
                  TextFormField(
                    key: const Key('spot-numbers'),
                    controller: _spotCtrl,
                    maxLines: _bulk ? 3 : 1,
                    // Bulk text carries many comma/newline-separated numbers,
                    // so only the single-entry field gets the DB's 32-char
                    // cap; bulk entries are checked individually below.
                    maxLength: _bulk ? null : 32,
                    decoration: InputDecoration(
                      labelText: _bulk ? l.spotNumbers : l.spotNumber,
                      hintText: _bulk ? l.spotNumbersHint : 'B1-01',
                      counterText: '',
                    ),
                    validator: (v) {
                      if (_bulk) {
                        final parsed = _parseSpotNumbers(v ?? '');
                        if (parsed.isEmpty) return l.bulkEmpty;
                        if (parsed.length > 500) {
                          return l.bulkTooMany(parsed.length);
                        }
                        if (parsed.any((s) => s.length > 32)) {
                          return l.spotTooLong;
                        }
                        return null;
                      }
                      return (v == null || v.trim().isEmpty)
                          ? l.spotRequired
                          : null;
                    },
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _levelCtrl,
                    maxLength: 32,
                    decoration: InputDecoration(
                      labelText: l.level,
                      hintText: 'B1',
                      counterText: '',
                    ),
                  ),
                  const SizedBox(height: 4),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _covered,
                    title: Text(
                      l.covered,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
                    ),
                    onChanged: (v) => setState(() => _covered = v),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    l.towers,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                      color: m.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  _TowerChips(
                    propertyId: widget.propertyId,
                    selected: _towerIds,
                    l: l,
                    onChanged: (next) => setState(() => _towerIds = next),
                  ),
                  const SizedBox(height: 20),
                  GoldButton(
                    key: const Key('parking-save'),
                    label: l.save,
                    height: 48,
                    onPressed: _saving ? null : _save,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
