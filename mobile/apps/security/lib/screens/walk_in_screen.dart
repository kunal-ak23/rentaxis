import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/gate_pass_provider.dart';

class WalkInScreen extends ConsumerStatefulWidget {
  const WalkInScreen({super.key});

  @override
  ConsumerState<WalkInScreen> createState() => _WalkInScreenState();
}

class _WalkInScreenState extends ConsumerState<WalkInScreen> {
  final _formKey = GlobalKey<FormState>();
  final _phone = TextEditingController();
  final _name = TextEditingController();
  final _purpose = TextEditingController();
  final _vehicle = TextEditingController();
  final _picker = ImagePicker();

  String? _propertyId;
  String? _unitId;
  String _visitorType = 'DELIVERY';
  List<Map<String, dynamic>> _destinations = const [];
  XFile? _photo;
  bool _loadingUnits = false;
  bool _lookingUp = false;
  bool _submitting = false;
  String? _lookupMessage;

  @override
  void dispose() {
    _phone.dispose();
    _name.dispose();
    _purpose.dispose();
    _vehicle.dispose();
    super.dispose();
  }

  Future<void> _loadDestinations(String propertyId) async {
    setState(() {
      _propertyId = propertyId;
      _unitId = null;
      _destinations = const [];
      _loadingUnits = true;
    });
    try {
      final raw = await ref
          .read(gatePassServiceProvider)
          .walkInDestinations(propertyId);
      if (!mounted) return;
      setState(() {
        _destinations = raw.whereType<Map<String, dynamic>>().toList();
        _loadingUnits = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loadingUnits = false);
      _snack(_L(context.isAr).loadUnitsFailed);
    }
  }

  Future<void> _lookup() async {
    if (_propertyId == null || _phone.text.trim().isEmpty || _lookingUp) return;
    final l = _L(context.isAr);
    setState(() {
      _lookingUp = true;
      _lookupMessage = null;
    });
    try {
      final row = await ref
          .read(gatePassServiceProvider)
          .lookupWalkInVisitor(
            propertyId: _propertyId!,
            phone: _phone.text,
            unitId: _unitId,
          );
      if (!mounted) return;
      _name.text = row['name']?.toString() ?? '';
      _vehicle.text = row['vehicleNumber']?.toString() ?? '';
      final type = row['visitorType']?.toString();
      if (_types.contains(type)) _visitorType = type!;
      final previousUnit = row['lastUnitId']?.toString();
      if (_unitId == null &&
          _destinations.any(
            (row) => row['unitId']?.toString() == previousUnit,
          )) {
        _unitId = previousUnit;
      }
      setState(() {
        _lookingUp = false;
        _lookupMessage = row['registeredForSelectedUnit'] == true
            ? l.registeredForUnit
            : l.previousVisitorFound;
      });
    } on DioException catch (error) {
      if (!mounted) return;
      setState(() {
        _lookingUp = false;
        _lookupMessage = error.response?.statusCode == 404
            ? l.firstVisit
            : l.lookupFailed;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _lookingUp = false;
        _lookupMessage = l.lookupFailed;
      });
    }
  }

  Future<void> _takePhoto() async {
    try {
      final image = await _picker.pickImage(
        source: ImageSource.camera,
        imageQuality: 78,
        maxWidth: 1280,
      );
      if (image != null && mounted) setState(() => _photo = image);
    } catch (_) {
      // Denied camera permission or a platform failure: tell the guard
      // instead of silently doing nothing.
      if (mounted) _snack(_L(context.isAr).cameraUnavailable);
    }
  }

  Future<void> _submit() async {
    final l = _L(context.isAr);
    if (!_formKey.currentState!.validate() || _submitting) return;
    if (_photo == null) {
      _snack(l.takePhotoFirst);
      return;
    }
    setState(() => _submitting = true);
    try {
      final pass = await ref
          .read(gatePassServiceProvider)
          .createWalkIn(
            propertyId: _propertyId!,
            unitId: _unitId!,
            name: _name.text,
            phone: _phone.text,
            visitorType: _visitorType,
            purpose: _purpose.text,
            vehicleNumber: _vehicle.text,
            photoPath: _photo!.path,
          );
      if (!mounted) return;
      ref.invalidate(expectedTodayProvider);
      ref.invalidate(approvalsProvider);
      final id = pass['id']?.toString();
      if (id == null) throw StateError('Walk-in response had no id');
      if (mounted) context.pushReplacement('/walk-in/$id');
    } on DioException catch (error) {
      _snack(
        error.response?.data is Map
            ? (error.response!.data['message']?.toString() ?? l.createFailed)
            : l.createFailed,
      );
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  static const _types = [
    'DELIVERY',
    'GUEST',
    'MAID',
    'MILK_VENDOR',
    'LAUNDRY_VENDOR',
    'SERVICE_VENDOR',
    'OTHER',
  ];

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    final properties = ref.watch(myPropertiesProvider);
    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: m.surface,
        foregroundColor: m.textPrimary,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        title: Text(
          l.newWalkIn,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: m.textPrimary,
                )
              : GoogleFonts.cinzel(
                  fontSize: 17,
                  fontWeight: FontWeight.w500,
                  letterSpacing: 1.2,
                  color: m.textPrimary,
                ),
        ),
      ),
      body: properties.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stack) => Center(
          child: Text(
            l.loadPropertiesFailed,
            style: bodyFont(color: m.textSecondary),
          ),
        ),
        data: (rows) => Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              DropdownButtonFormField<String>(
                initialValue: _propertyId,
                decoration: InputDecoration(labelText: l.property),
                items: rows
                    .map(
                      (row) => DropdownMenuItem(
                        value: row['id']?.toString(),
                        child: Text(
                          row['name']?.toString() ?? l.propertyFallback,
                        ),
                      ),
                    )
                    .toList(),
                onChanged: (value) {
                  if (value != null) _loadDestinations(value);
                },
                validator: (value) => value == null ? l.selectProperty : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                key: ValueKey(
                  'destination-$_propertyId-$_unitId-${_destinations.length}',
                ),
                initialValue: _unitId,
                decoration: InputDecoration(
                  labelText: _loadingUnits ? l.loadingUnits : l.towerAndUnit,
                ),
                items: _destinations.map((row) {
                  final tower = row['buildingName']?.toString();
                  final unit = row['unitNumber']?.toString() ?? l.unitFallback;
                  return DropdownMenuItem(
                    value: row['unitId']?.toString(),
                    child: Text(tower == null ? unit : '$tower · $unit'),
                  );
                }).toList(),
                onChanged: _loadingUnits
                    ? null
                    : (value) => setState(() => _unitId = value),
                validator: (value) =>
                    value == null ? l.selectDestination : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                textInputAction: TextInputAction.search,
                onFieldSubmitted: (_) => _lookup(),
                decoration: InputDecoration(
                  labelText: l.mobileNumber,
                  hintText: '+971501234567',
                  suffixIcon: _lookingUp
                      ? const Padding(
                          padding: EdgeInsets.all(14),
                          child: SizedBox.square(
                            dimension: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : IconButton(
                          tooltip: l.findPreviousVisitor,
                          onPressed: _lookup,
                          icon: const Icon(Icons.manage_search),
                        ),
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? l.enterMobileNumber
                    : null,
              ),
              if (_lookupMessage != null)
                Padding(
                  padding: const EdgeInsetsDirectional.only(top: 8),
                  child: Text(
                    _lookupMessage!,
                    style: bodyFont(color: m.textSecondary, fontSize: 13),
                  ),
                ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _name,
                textCapitalization: TextCapitalization.words,
                decoration: InputDecoration(labelText: l.visitorName),
                validator: (value) => value == null || value.trim().isEmpty
                    ? l.enterVisitorName
                    : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                initialValue: _visitorType,
                decoration: InputDecoration(labelText: l.visitorType),
                items: _types
                    .map(
                      (type) => DropdownMenuItem(
                        value: type,
                        child: Text(l.visitorTypeValue(type)),
                      ),
                    )
                    .toList(),
                onChanged: (value) => setState(() => _visitorType = value!),
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _purpose,
                decoration: InputDecoration(labelText: l.purposeOrCompany),
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _vehicle,
                textCapitalization: TextCapitalization.characters,
                decoration: InputDecoration(labelText: l.vehicleNumberOptional),
              ),
              const SizedBox(height: 18),
              if (_photo != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(14),
                  child: Image.file(
                    File(_photo!.path),
                    height: 220,
                    fit: BoxFit.cover,
                  ),
                ),
              const SizedBox(height: 10),
              GoldButton.outlined(
                label: _photo == null ? l.takePhoto : l.retakePhoto,
                onPressed: _takePhoto,
                icon: const Icon(Icons.photo_camera),
              ),
              const SizedBox(height: 18),
              GoldButton(
                label: _submitting ? l.submitting : l.requestEntry,
                onPressed: _submitting ? null : _submit,
                icon: _submitting
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.send),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Walk-in screen strings (EN/AR). Lightweight per-screen pattern — see
/// arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;
  String get cameraUnavailable => ar
      ? 'تعذّر فتح الكاميرا. تحقق من صلاحية الكاميرا في الإعدادات.'
      : 'Could not open the camera. Check camera permission in Settings.';

  String get newWalkIn => ar ? 'زيارة بدون تصريح جديدة' : 'New walk-in visitor';
  String get loadPropertiesFailed => ar
      ? 'تعذّر تحميل العقارات المكلّف بها.'
      : 'Could not load assigned properties.';
  String get loadUnitsFailed => ar
      ? 'تعذّر تحميل وحدات هذا العقار.'
      : 'Could not load units for this property.';
  String get property => ar ? 'العقار' : 'Property';
  String get propertyFallback => ar ? 'عقار' : 'Property';
  String get selectProperty => ar ? 'اختر عقارًا' : 'Select a property';
  String get loadingUnits => ar ? 'جارٍ تحميل الوحدات…' : 'Loading units…';
  String get towerAndUnit => ar ? 'البرج والوحدة' : 'Tower and unit';
  String get unitFallback => ar ? 'وحدة' : 'Unit';
  String get selectDestination =>
      ar ? 'اختر الوحدة المقصودة' : 'Select the destination unit';
  String get mobileNumber =>
      ar ? 'رقم الهاتف مع رمز الدولة' : 'Mobile number with country code';
  String get findPreviousVisitor =>
      ar ? 'البحث عن زائر سابق' : 'Find previous visitor';
  String get enterMobileNumber =>
      ar ? 'أدخل رقم الهاتف' : 'Enter a mobile number';
  String get registeredForUnit => ar
      ? 'مسجَّل لهذه الوحدة — تم تحميل البيانات.'
      : 'Registered for this unit — details loaded.';
  String get previousVisitorFound => ar
      ? 'تم العثور على زائر سابق — تأكد من البيانات والتقط صورة جديدة.'
      : 'Previous visitor found — confirm the details and take a fresh photo.';
  String get firstVisit => ar
      ? 'زيارة أولى — أدخل البيانات أدناه.'
      : 'First visit — enter the details below.';
  String get lookupFailed => ar
      ? 'تعذّر البحث عن الزيارات السابقة.'
      : 'Could not search previous visits.';
  String get visitorName => ar ? 'اسم الزائر' : 'Visitor name';
  String get enterVisitorName =>
      ar ? 'أدخل اسم الزائر' : 'Enter the visitor name';
  String get visitorType => ar ? 'نوع الزائر' : 'Visitor type';
  String get purposeOrCompany =>
      ar ? 'الغرض أو شركة التوصيل' : 'Purpose or delivery company';
  String get vehicleNumberOptional =>
      ar ? 'رقم المركبة (اختياري)' : 'Vehicle number (optional)';
  String get takePhoto => ar ? 'التقاط صورة الزائر' : 'Take visitor photo';
  String get retakePhoto => ar ? 'إعادة التقاط الصورة' : 'Retake photo';
  String get submitting => ar ? 'جارٍ الإرسال…' : 'Submitting…';
  String get requestEntry => ar ? 'طلب الدخول' : 'Request entry';
  String get takePhotoFirst => ar
      ? 'التقط صورة جديدة للزائر قبل الإرسال.'
      : 'Take a fresh visitor photo before submitting.';
  String get createFailed =>
      ar ? 'تعذّر إنشاء طلب الزيارة.' : 'Could not create the visitor request.';

  String visitorTypeValue(String type) {
    switch (type) {
      case 'DELIVERY':
        return ar ? 'توصيل' : 'delivery';
      case 'GUEST':
        return ar ? 'ضيف' : 'guest';
      case 'MAID':
        return ar ? 'عاملة منزلية' : 'maid';
      case 'MILK_VENDOR':
        return ar ? 'مورّد الحليب' : 'milk vendor';
      case 'LAUNDRY_VENDOR':
        return ar ? 'مورّد المغسلة' : 'laundry vendor';
      case 'SERVICE_VENDOR':
        return ar ? 'مورّد خدمة' : 'service vendor';
      case 'OTHER':
        return ar ? 'أخرى' : 'other';
      default:
        return type.replaceAll('_', ' ').toLowerCase();
    }
  }
}
