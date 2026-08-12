import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _staffServiceProvider = Provider<StaffService>((ref) {
  final client = ref.watch(apiClientProvider);
  return StaffService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _staffProvider = FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_staffServiceProvider);
  return service.getStaff();
});

/// Locale-aware name from a row carrying nameEn/nameAr (staff or property).
String _localName(dynamic row, bool ar) {
  if (row is! Map) return '';
  final nameAr = (row['nameAr'] ?? '').toString();
  final nameEn = (row['nameEn'] ?? '').toString();
  if (ar && nameAr.isNotEmpty) return nameAr;
  if (nameEn.isNotEmpty) return nameEn;
  return nameAr;
}

final _propertiesForFilterProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

/// Staff directory, restyled to match the admin design language: dark
/// chrome search header, property filter chips, role-badged cards.
class StaffScreen extends ConsumerStatefulWidget {
  const StaffScreen({super.key});

  @override
  ConsumerState<StaffScreen> createState() => _StaffScreenState();
}

class _StaffScreenState extends ConsumerState<StaffScreen> {
  String _searchQuery = '';
  String? _selectedPropertyId;

  Future<void> _refresh() async {
    ref.invalidate(_staffProvider);
    ref.invalidate(_propertiesForFilterProvider);
  }

  Future<void> _deleteStaff(Map<String, dynamic> staff) async {
    final l = _L(context.isAr);
    final name = _localName(staff, l.ar);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.deleteStaff),
        content: Text(l.deleteStaffConfirm(name.isEmpty ? null : name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
            child: Text(l.delete),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref.read(_staffServiceProvider).deleteStaff(staff['id']);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.staffDeleted)));
        _refresh();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.staffDeleteFailed)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final staffAsync = ref.watch(_staffProvider);
    final propertiesAsync = ref.watch(_propertiesForFilterProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(
            count: staffAsync.asData?.value.length,
            l: l,
            onSearchChanged: (v) =>
                setState(() => _searchQuery = v.toLowerCase()),
          ),
          propertiesAsync.when(
            loading: () => const SizedBox.shrink(),
            error: (error, stackTrace) => const SizedBox.shrink(),
            data: (properties) {
              if (properties.isEmpty) return const SizedBox.shrink();
              return SizedBox(
                height: 48,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  children: [
                    _FilterChip(
                      label: l.all,
                      selected: _selectedPropertyId == null,
                      onTap: () => setState(() => _selectedPropertyId = null),
                    ),
                    ...properties.map(
                      (p) => Padding(
                        padding: const EdgeInsetsDirectional.only(start: 8),
                        child: _FilterChip(
                          label: p['name'] ?? l.property,
                          selected: _selectedPropertyId == p['id'],
                          onTap: () =>
                              setState(() => _selectedPropertyId = p['id']),
                        ),
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
          Expanded(
            child: staffAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                ),
              ),
              error: (e, _) =>
                  ErrorState(message: l.loadFailed, onRetry: _refresh),
              data: (staff) {
                // The Staff DTO exposes nameEn/nameAr/designation and a single
                // nested property — search and filter on those real fields.
                final filtered = staff.where((s) {
                  final haystack = [
                    s['nameEn'],
                    s['nameAr'],
                    s['designation'],
                    s['property']?['nameEn'],
                    s['property']?['nameAr'],
                  ];
                  final matchesSearch =
                      _searchQuery.isEmpty ||
                      haystack.any(
                        (v) => (v ?? '')
                            .toString()
                            .toLowerCase()
                            .contains(_searchQuery),
                      );

                  if (_selectedPropertyId != null &&
                      s['property']?['id'] != _selectedPropertyId) {
                    return false;
                  }

                  return matchesSearch;
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.badge_outlined,
                    title: _searchQuery.isEmpty && _selectedPropertyId == null
                        ? l.noStaffYet
                        : l.noMatchingStaff,
                    subtitle:
                        _searchQuery.isEmpty && _selectedPropertyId == null
                        ? l.addFirstStaff
                        : null,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: EdgeInsets.fromLTRB(
                      16,
                      8,
                      16,
                      AppInsets.bottomNav(context),
                    ),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final member = filtered[index];
                      return AnimatedListItem(
                        index: index,
                        child: _StaffCard(
                          staff: member,
                          l: l,
                          onTap: () => context.push('/staff/${member['id']}'),
                          onLongPress: () => _deleteStaff(member),
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
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () => _showCreateStaffSheet(context),
        child: const Icon(Icons.person_add_outlined, color: Colors.white),
      ),
    );
  }

  void _showCreateStaffSheet(BuildContext context) {
    final l = _L(context.isAr);
    final nameEnCtrl = TextEditingController();
    final nameArCtrl = TextEditingController();
    final designationCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    final properties =
        ref.read(_propertiesForFilterProvider).asData?.value ?? const [];
    String? propertyId;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
            24,
            24,
            24,
            MediaQuery.of(ctx).viewInsets.bottom + 24,
          ),
          child: Form(
            key: formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: AppColors.border,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    l.newStaffMember,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 19,
                            fontWeight: FontWeight.w600,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 18,
                            fontWeight: FontWeight.w600,
                          ),
                  ),
                  const SizedBox(height: 20),
                  TextFormField(
                    key: const Key('staff-name-en'),
                    controller: nameEnCtrl,
                    decoration: InputDecoration(
                      labelText: l.nameEn,
                      prefixIcon: const Icon(Icons.person_outline),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? l.nameRequired : null,
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('staff-name-ar'),
                    controller: nameArCtrl,
                    decoration: InputDecoration(
                      labelText: l.nameAr,
                      prefixIcon: const Icon(Icons.person_outline),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('staff-designation'),
                    controller: designationCtrl,
                    decoration: InputDecoration(
                      labelText: l.designation,
                      prefixIcon: const Icon(Icons.badge_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('staff-phone'),
                    controller: phoneCtrl,
                    keyboardType: TextInputType.phone,
                    decoration: InputDecoration(
                      labelText: l.phoneNumber,
                      prefixIcon: const Icon(Icons.phone_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String?>(
                    key: const Key('staff-property'),
                    initialValue: propertyId,
                    decoration: InputDecoration(
                      labelText: l.property,
                      prefixIcon: const Icon(Icons.apartment_outlined),
                    ),
                    items: [
                      DropdownMenuItem<String?>(
                        value: null,
                        child: Text(l.noProperty),
                      ),
                      ...properties.map(
                        (p) => DropdownMenuItem<String?>(
                          value: p['id'] as String?,
                          child: Text((p['name'] ?? l.property).toString()),
                        ),
                      ),
                    ],
                    onChanged: (v) => setSheetState(() => propertyId = v),
                  ),
                  const SizedBox(height: 24),
                  GoldButton(
                    key: const Key('staff-save'),
                    label: l.createStaff,
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service = ref.read(_staffServiceProvider);
                      try {
                        // POST /v1/staff binds the Staff entity directly:
                        // nameEn is required; property goes as {id}.
                        await service.createStaff({
                          'nameEn': nameEnCtrl.text.trim(),
                          if (nameArCtrl.text.trim().isNotEmpty)
                            'nameAr': nameArCtrl.text.trim(),
                          if (designationCtrl.text.trim().isNotEmpty)
                            'designation': designationCtrl.text.trim(),
                          if (phoneCtrl.text.trim().isNotEmpty)
                            'phone': phoneCtrl.text.trim(),
                          if (propertyId != null)
                            'property': {'id': propertyId},
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _refresh();
                      } catch (e) {
                        if (ctx.mounted) {
                          ScaffoldMessenger.of(ctx).showSnackBar(
                            SnackBar(content: Text(l.createStaffFailed)),
                          );
                        }
                      }
                    },
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

class _ChromeHeader extends StatelessWidget {
  final int? count;
  final _L l;
  final ValueChanged<String> onSearchChanged;

  const _ChromeHeader({
    required this.count,
    required this.l,
    required this.onSearchChanged,
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
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            count != null ? l.staffCount(count!) : l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 12,
                    color: AppColors.goldMid,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 10,
                    letterSpacing: 2.6,
                    color: AppColors.goldMid,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    color: AppColors.gold400,
                  )
                : GoogleFonts.plusJakartaSans(fontSize: 22, color: AppColors.gold400),
          ),
          const SizedBox(height: 12),
          TextField(
            onChanged: onSearchChanged,
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.plusJakartaSans)(fontSize: 13, color: Colors.white),
            decoration: InputDecoration(
              isDense: true,
              hintText: l.searchHint,
              hintStyle:
                  (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.plusJakartaSans)(
                    fontSize: 12.5,
                    color: Colors.white.withValues(alpha: 0.45),
                  ),
              prefixIcon: Icon(
                Icons.search,
                size: 18,
                color: Colors.white.withValues(alpha: 0.45),
              ),
              filled: true,
              fillColor: Colors.white.withValues(alpha: 0.07),
              contentPadding: const EdgeInsets.symmetric(vertical: 10),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(999),
                borderSide: BorderSide(
                  color: AppColors.accent.withValues(alpha: 0.2),
                ),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(999),
                borderSide: BorderSide(
                  color: AppColors.accent.withValues(alpha: 0.2),
                ),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(999),
                borderSide: BorderSide(
                  color: AppColors.accent.withValues(alpha: 0.5),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _FilterChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;
  const _FilterChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          color: selected ? AppColors.primary : Colors.transparent,
          border: Border.all(
            color: selected ? AppColors.primary : m.borderStrong,
          ),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: context.isAr
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 12.5,
                  color: selected ? AppColors.accent : m.textSecondary,
                )
              : GoogleFonts.plusJakartaSans(
                  fontSize: 11.5,
                  letterSpacing: 0.6,
                  color: selected ? AppColors.accent : m.textSecondary,
                ),
        ),
      ),
    );
  }
}

class _StaffCard extends StatelessWidget {
  final Map<String, dynamic> staff;
  final _L l;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  const _StaffCard({
    required this.staff,
    required this.l,
    required this.onTap,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final localName = _localName(staff, l.ar);
    final name = localName.isEmpty ? l.unknown : localName;
    final designation = (staff['designation'] ?? '').toString();
    final phone = (staff['phone'] ?? '').toString();
    final active = staff['active'] != false;
    final propertyName = _localName(staff['property'], l.ar);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: m.background,
                  border: Border.all(color: m.borderStrong),
                ),
                alignment: Alignment.center,
                child: Text(
                  name.isNotEmpty ? name[0].toUpperCase() : '?',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 13,
                    color: AppColors.accentDark,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            name,
                            style:
                                (l.ar
                                ? GoogleFonts.notoNaskhArabic
                                : GoogleFonts.plusJakartaSans)(
                                  fontWeight: FontWeight.w600,
                                  fontSize: 14,
                                  color: m.textPrimary,
                                ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: 8),
                        _StatusPill(
                          color: active
                              ? AppColors.success
                              : m.textSecondary,
                          label: active ? l.active : l.inactive,
                          ar: l.ar,
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    if (designation.isNotEmpty)
                      Text(
                        designation,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 12,
                              color: m.textSecondary,
                            ),
                      ),
                    if (phone.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        phone,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 12,
                              color: m.textMuted,
                            ),
                      ),
                    ],
                    if (propertyName.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        propertyName,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 10.5,
                              fontWeight: FontWeight.w600,
                              color: AppColors.accentDark,
                            ),
                      ),
                    ],
                  ],
                ),
              ),
              Icon(Icons.chevron_right, color: m.textMuted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  final Color color;
  final String label;
  final bool ar;
  const _StatusPill({
    required this.color,
    required this.label,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: color.withValues(alpha: 0.08),
        border: Border.all(color: color.withValues(alpha: 0.28)),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.plusJakartaSans)(
          fontSize: 9,
          letterSpacing: ar ? 0 : 1.0,
          color: color,
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'الموظفون' : 'Staff';
  String staffCount(int n) => ar ? '$n موظفًا' : '$n STAFF';
  String get searchHint => ar ? 'ابحث في الموظفين' : 'Search staff...';
  String get all => ar ? 'الكل' : 'All';
  String get property => ar ? 'عقار' : 'Property';
  String get loadFailed => ar ? 'فشل تحميل الموظفين' : 'Failed to load staff';
  String get noStaffYet => ar ? 'لا يوجد موظفون بعد' : 'No staff yet';
  String get noMatchingStaff =>
      ar ? 'لا يوجد موظفون مطابقون' : 'No matching staff';
  String get addFirstStaff =>
      ar ? 'أضف أول موظف لديك' : 'Add your first staff member';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get newStaffMember => ar ? 'موظف جديد' : 'New Staff Member';
  String get nameEn => ar ? 'الاسم (إنجليزي)' : 'Name (English)';
  String get nameAr => ar ? 'الاسم (عربي)' : 'Name (Arabic)';
  String get nameRequired => ar ? 'الاسم مطلوب' : 'Name is required';
  String get designation => ar ? 'المسمى الوظيفي' : 'Designation';
  String get phoneNumber => ar ? 'رقم الهاتف' : 'Phone Number';
  String get noProperty => ar ? 'بدون عقار' : 'No property';
  String get active => ar ? 'نشط' : 'Active';
  String get inactive => ar ? 'غير نشط' : 'Inactive';
  String get createStaff => ar ? 'إنشاء موظف' : 'Create Staff';
  String get createStaffFailed =>
      ar ? 'فشل إنشاء الموظف' : 'Failed to create staff member';
  String get deleteStaff => ar ? 'حذف الموظف' : 'Delete Staff';
  String deleteStaffConfirm(dynamic name) => ar
      ? 'هل أنت متأكد من حذف ${name ?? 'هذا الموظف'}؟'
      : 'Are you sure you want to delete ${name ?? 'this staff member'}?';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get delete => ar ? 'حذف' : 'Delete';
  String get staffDeleted => ar ? 'تم حذف الموظف' : 'Staff member deleted';
  String get staffDeleteFailed =>
      ar ? 'فشل حذف الموظف' : 'Failed to delete staff member';
}
