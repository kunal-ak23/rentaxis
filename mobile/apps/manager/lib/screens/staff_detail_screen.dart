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

/// Locale-aware name from a row carrying nameEn/nameAr (staff or property).
String _localName(dynamic row, bool ar) {
  if (row is! Map) return '';
  final nameAr = (row['nameAr'] ?? '').toString();
  final nameEn = (row['nameEn'] ?? '').toString();
  if (ar && nameAr.isNotEmpty) return nameAr;
  if (nameEn.isNotEmpty) return nameEn;
  return nameAr;
}

/// Staff detail: dark chrome hero card (gold-ringed monogram, status pill),
/// property assignment list, restyled to match the admin design language.
class StaffDetailScreen extends ConsumerStatefulWidget {
  final String staffId;
  const StaffDetailScreen({super.key, required this.staffId});

  @override
  ConsumerState<StaffDetailScreen> createState() => _StaffDetailScreenState();
}

class _StaffDetailScreenState extends ConsumerState<StaffDetailScreen> {
  Map<String, dynamic>? _staff;
  List<dynamic> _properties = [];
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final staffService = ref.read(_staffServiceProvider);
      final propertyService = ref.read(_propertyServiceProvider);
      final results = await Future.wait([
        staffService.getStaffById(widget.staffId),
        propertyService.getProperties(),
      ]);
      if (!mounted) return;
      setState(() {
        _staff = results[0] as Map<String, dynamic>;
        _properties = results[1] as List<dynamic>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      final l = _L(context.isAr);
      setState(() {
        _error = l.loadFailed;
        _isLoading = false;
      });
    }
  }

  Future<void> _deleteStaff() async {
    final l = _L(context.isAr);
    final name = _localName(_staff, l.ar);
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

    setState(() => _isActioning = true);
    try {
      await ref.read(_staffServiceProvider).deleteStaff(widget.staffId);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.staffDeleted)));
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.staffDeleteFailed)));
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  void _showEditSheet() {
    if (_staff == null) return;
    final l = _L(context.isAr);
    final staff = _staff!;
    final nameEnCtrl = TextEditingController(text: staff['nameEn'] ?? '');
    final nameArCtrl = TextEditingController(text: staff['nameAr'] ?? '');
    final designationCtrl = TextEditingController(
      text: staff['designation'] ?? '',
    );
    final phoneCtrl = TextEditingController(text: staff['phone'] ?? '');
    final formKey = GlobalKey<FormState>();
    String? propertyId = staff['property']?['id'] as String?;

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
                    l.editStaff,
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
                      ..._properties.map(
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
                    label: l.saveChanges,
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service = ref.read(_staffServiceProvider);
                      final nameAr = nameArCtrl.text.trim();
                      final designation = designationCtrl.text.trim();
                      final phone = phoneCtrl.text.trim();
                      try {
                        // PUT /v1/staff/{id} copies every Staff field from the
                        // body, so echo the ones this sheet doesn't edit —
                        // omitting them would null them out server-side.
                        await service.updateStaff(widget.staffId, {
                          'nameEn': nameEnCtrl.text.trim(),
                          'nameAr': nameAr.isEmpty ? null : nameAr,
                          'designation': designation.isEmpty
                              ? null
                              : designation,
                          'phone': phone.isEmpty ? null : phone,
                          'employeeId': staff['employeeId'],
                          'department': staff['department'],
                          'monthlySalary': staff['monthlySalary'],
                          'joinDate': staff['joinDate'],
                          'emiratesId': staff['emiratesId'],
                          'passportNumber': staff['passportNumber'],
                          'active': staff['active'] ?? true,
                          if (propertyId != null)
                            'property': {'id': propertyId},
                          if (staff['salaryAccount']?['id'] != null)
                            'salaryAccount': {
                              'id': staff['salaryAccount']['id'],
                            },
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _loadData();
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(l.staffUpdated)),
                          );
                        }
                      } catch (e) {
                        if (ctx.mounted) {
                          ScaffoldMessenger.of(ctx).showSnackBar(
                            SnackBar(content: Text(l.staffUpdateFailed)),
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

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);

    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: Text(l.title)),
        body: Center(
          child: CircularProgressIndicator(
            color: m.isDark ? AppColors.accent : AppColors.primary,
          ),
        ),
      );
    }

    if (_error != null || _staff == null) {
      return Scaffold(
        appBar: AppBar(title: Text(l.title)),
        body: ErrorState(message: _error ?? l.notFound, onRetry: _loadData),
      );
    }

    final staff = _staff!;
    final localName = _localName(staff, l.ar);
    final name = localName.isEmpty ? l.unknown : localName;
    final designation = (staff['designation'] ?? '').toString();
    final phone = (staff['phone'] ?? '').toString();
    final active = staff['active'] != false;

    // The Staff DTO carries a single nested property assignment.
    final assignedProperties = [
      if (staff['property'] is Map) staff['property'],
    ];

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(l.title),
        actions: [
          IconButton(
            icon: const Icon(Icons.edit_outlined),
            onPressed: _showEditSheet,
          ),
          PopupMenuButton<String>(
            onSelected: (v) {
              if (v == 'delete') _deleteStaff();
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'delete',
                child: Row(
                  children: [
                    const Icon(
                      Icons.delete_outline,
                      color: AppColors.danger,
                      size: 18,
                    ),
                    const SizedBox(width: 8),
                    Text(l.delete),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      body: LoadingOverlay(
        isLoading: _isActioning,
        child: RefreshIndicator(
          onRefresh: _loadData,
          color: m.isDark ? AppColors.accent : AppColors.primary,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.fromLTRB(
              16,
              16,
              16,
              24,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Dark chrome hero card
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: AppColors.primary,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: AppColors.accent.withValues(alpha: 0.14),
                    ),
                  ),
                  child: Column(
                    children: [
                      Container(
                        width: 72,
                        height: 72,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: AppColors.accent.withValues(alpha: 0.45),
                          ),
                        ),
                        alignment: Alignment.center,
                        child: Text(
                          name.isNotEmpty ? name[0].toUpperCase() : '?',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 24,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accent,
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        name,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 19,
                                fontWeight: FontWeight.w600,
                                color: AppColors.gold400,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 19,
                                color: AppColors.gold400,
                              ),
                      ),
                      const SizedBox(height: 10),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 5,
                        ),
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(999),
                          color: (active ? AppColors.success : Colors.white38)
                              .withValues(alpha: 0.12),
                          border: Border.all(
                            color: (active ? AppColors.success : Colors.white38)
                                .withValues(alpha: 0.3),
                          ),
                        ),
                        child: Text(
                          active ? l.active : l.inactive,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.plusJakartaSans)(
                                fontSize: 10.5,
                                letterSpacing: l.ar ? 0 : 1.2,
                                color: AppColors.gold400,
                              ),
                        ),
                      ),
                      Divider(
                        color: Colors.white.withValues(alpha: 0.15),
                        height: 28,
                      ),
                      if (designation.isNotEmpty)
                        _HeaderInfo(
                          icon: Icons.badge_outlined,
                          text: designation,
                        ),
                      if (phone.isNotEmpty) ...[
                        const SizedBox(height: 6),
                        _HeaderInfo(icon: Icons.phone_outlined, text: phone),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 24),

                Row(
                  children: [
                    Icon(
                      Icons.apartment_outlined,
                      size: 18,
                      color: AppColors.accentDark,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      l.propertyAssignments,
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.plusJakartaSans)(
                            fontSize: 10,
                            letterSpacing: l.ar ? 0 : 2.2,
                            color: m.textMuted,
                          ),
                    ),
                    const Spacer(),
                    Text(
                      l.propertyCount(assignedProperties.length),
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.plusJakartaSans)(
                            fontSize: 12,
                            color: m.textSecondary,
                          ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (assignedProperties.isEmpty)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: m.surface,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: m.border),
                    ),
                    child: Column(
                      children: [
                        Icon(
                          Icons.apartment_outlined,
                          size: 36,
                          color: m.textMuted,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          l.noAssignments,
                          style: (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts
                                    .josefinSans)(color: m.textSecondary),
                        ),
                        Text(
                          l.assignHint,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.plusJakartaSans)(
                                fontSize: 12,
                                color: m.textMuted,
                              ),
                        ),
                      ],
                    ),
                  )
                else
                  ...assignedProperties.map((property) {
                    final localPropertyName = _localName(property, l.ar);
                    final propertyName = localPropertyName.isEmpty
                        ? l.property
                        : localPropertyName;
                    final address = (property['address'] ?? '').toString();
                    return Container(
                      margin: const EdgeInsets.only(bottom: 8),
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: m.surface,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: m.border),
                      ),
                      child: Row(
                        children: [
                          Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color:
                                  (m.isDark
                                          ? AppColors.accent
                                          : AppColors.primary)
                                      .withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Icon(
                              Icons.apartment_outlined,
                              color: m.isDark
                                  ? AppColors.accent
                                  : AppColors.primary,
                              size: 20,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  propertyName,
                                  style:
                                      (l.ar
                                      ? GoogleFonts.notoNaskhArabic
                                      : GoogleFonts.plusJakartaSans)(
                                        fontWeight: FontWeight.w600,
                                        fontSize: 14,
                                        color: m.textPrimary,
                                      ),
                                ),
                                if (address.isNotEmpty) ...[
                                  const SizedBox(height: 2),
                                  Text(
                                    address,
                                    style:
                                        (l.ar
                                        ? GoogleFonts.notoNaskhArabic
                                        : GoogleFonts.plusJakartaSans)(
                                          fontSize: 12,
                                          color: m.textSecondary,
                                        ),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ],
                      ),
                    );
                  }),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _HeaderInfo extends StatelessWidget {
  final IconData icon;
  final String text;

  const _HeaderInfo({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, size: 16, color: Colors.white60),
        const SizedBox(width: 8),
        Text(text, style: const TextStyle(color: Colors.white70, fontSize: 13)),
      ],
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تفاصيل الموظف' : 'Staff Details';
  String get loadFailed =>
      ar ? 'فشل تحميل تفاصيل الموظف' : 'Failed to load staff details';
  String get notFound => ar ? 'غير موجود' : 'Not found';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get editStaff => ar ? 'تعديل الموظف' : 'Edit Staff';
  String get nameEn => ar ? 'الاسم (إنجليزي)' : 'Name (English)';
  String get nameAr => ar ? 'الاسم (عربي)' : 'Name (Arabic)';
  String get nameRequired => ar ? 'الاسم مطلوب' : 'Name is required';
  String get designation => ar ? 'المسمى الوظيفي' : 'Designation';
  String get phoneNumber => ar ? 'رقم الهاتف' : 'Phone Number';
  String get noProperty => ar ? 'بدون عقار' : 'No property';
  String get active => ar ? 'نشط' : 'Active';
  String get inactive => ar ? 'غير نشط' : 'Inactive';
  String get saveChanges => ar ? 'حفظ التغييرات' : 'Save Changes';
  String get staffUpdated =>
      ar ? 'تم تحديث بيانات الموظف' : 'Staff member updated';
  String get staffUpdateFailed =>
      ar ? 'فشل تحديث بيانات الموظف' : 'Failed to update staff member';
  String get deleteStaff => ar ? 'حذف الموظف' : 'Delete Staff';
  String deleteStaffConfirm(dynamic name) => ar
      ? 'هل أنت متأكد من حذف ${name ?? 'هذا الموظف'}؟ لا يمكن التراجع عن هذا الإجراء.'
      : 'Are you sure you want to delete ${name ?? 'this staff member'}? This action cannot be undone.';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get delete => ar ? 'حذف' : 'Delete';
  String get staffDeleted => ar ? 'تم حذف الموظف' : 'Staff member deleted';
  String get staffDeleteFailed =>
      ar ? 'فشل حذف الموظف' : 'Failed to delete staff member';
  String get propertyAssignments =>
      ar ? 'العقارات المعينة' : 'Property Assignments';
  String get property => ar ? 'عقار' : 'Property';
  String propertyCount(int n) => ar
      ? '$n ${n == 1 ? 'عقار' : 'عقارات'}'
      : '$n ${n == 1 ? 'property' : 'properties'}';
  String get noAssignments =>
      ar ? 'لا توجد عقارات معينة' : 'No property assignments';
  String get assignHint =>
      ar ? 'عيّن عقارات لهذا الموظف' : 'Assign properties to this staff member';
}
