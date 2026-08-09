import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تسجيل مورّد للوحدة' : 'Register Unit Vendor';
  String get intro => ar
      ? 'سجّل عاملة منزلية أو موردًا منتظمًا حتى تستخدم زيارات البوابة القادمة السياسة الأسرع لهذه الوحدة.'
      : 'Register a regular maid or vendor so future gate visits can use '
            'the faster policy for that unit.';
  String get couldNotLoadProperties =>
      ar ? 'تعذّر تحميل العقارات.' : 'Could not load properties.';
  String get property => ar ? 'العقار' : 'Property';
  String get selectProperty => ar ? 'اختر عقارًا' : 'Select a property';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get loadingUnits => ar ? 'جارٍ تحميل الوحدات…' : 'Loading units…';
  String get selectUnit => ar ? 'اختر وحدة' : 'Select a unit';
  String get name => ar ? 'الاسم' : 'Name';
  String get enterName => ar ? 'أدخل اسمًا' : 'Enter a name';
  String get mobileNumber => ar ? 'رقم الجوال' : 'Mobile number';
  String get enterMobileNumber =>
      ar ? 'أدخل رقم جوال' : 'Enter a mobile number';
  String get vendorType => ar ? 'نوع المورّد' : 'Vendor type';
  String get maid => ar ? 'عاملة منزلية' : 'Maid';
  String get milkVendor => ar ? 'مورّد الحليب' : 'Milk vendor';
  String get laundryVendor => ar ? 'مورّد المغسلة' : 'Laundry vendor';
  String get serviceVendor => ar ? 'مورّد خدمة' : 'Service vendor';
  String get other => ar ? 'أخرى' : 'Other';
  String get saving => ar ? 'جارٍ الحفظ…' : 'Saving…';
  String get registerVendor => ar ? 'تسجيل المورّد' : 'Register vendor';
  String get vendorRegistered =>
      ar ? 'تم تسجيل المورّد لهذه الوحدة.' : 'Vendor registered for this unit.';
  String get vendorAccessRevoked => ar
      ? 'تم إلغاء دخول المورّد لهذه الوحدة.'
      : 'Vendor access revoked for this unit.';
  String get couldNotRegister =>
      ar ? 'تعذّر تسجيل هذا المورّد.' : 'Could not register this vendor.';
  String get validFrom => ar ? 'صالح من' : 'Valid from';
  String get validFromHint => ar ? 'صالح من (اختياري)' : 'Valid from (optional)';
  String get validTo => ar ? 'صالح حتى' : 'Valid until';
  String get validToHint =>
      ar ? 'صالح حتى (اختياري)' : 'Valid until (optional)';
  String get validToBeforeValidFrom => ar
      ? 'يجب ألا يسبق تاريخ "صالح حتى" تاريخ "صالح من".'
      : '"Valid until" cannot be before "Valid from".';
  String get accessActive => ar ? 'الدخول مفعّل' : 'Access active';
  String get accessActiveHint => ar
      ? 'أوقف التفعيل ثم احفظ لإلغاء تسجيل مورّد سبق تسجيله لهذه الوحدة (بنفس رقم الجوال).'
      : 'Turn off and save to revoke a previously registered vendor for this '
            'unit (same mobile number).';
}

class RegisterGateVendorScreen extends ConsumerStatefulWidget {
  const RegisterGateVendorScreen({super.key});

  @override
  ConsumerState<RegisterGateVendorScreen> createState() =>
      _RegisterGateVendorScreenState();
}

class _RegisterGateVendorScreenState
    extends ConsumerState<RegisterGateVendorScreen> {
  final _key = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _phone = TextEditingController();
  String? _propertyId;
  String? _unitId;
  String _type = 'MAID';
  DateTime? _validFrom;
  DateTime? _validTo;
  bool _active = true;
  List<Map<String, dynamic>> _units = const [];
  bool _loadingUnits = false;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    super.dispose();
  }

  Future<void> _propertyChanged(String id) async {
    setState(() {
      _propertyId = id;
      _unitId = null;
      _loadingUnits = true;
    });
    try {
      final raw = await UnitService(
        ref.read(apiClientProvider).dio,
      ).getUnitsByProperty(id);
      if (!mounted) return;
      setState(() {
        _units = raw
            .whereType<Map>()
            .map((row) => Map<String, dynamic>.from(row))
            .toList();
        _loadingUnits = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingUnits = false);
    }
  }

  Future<void> _save() async {
    if (!_key.currentState!.validate() || _saving) return;
    final l = _L(context.isAr);
    final validFrom = _validFrom;
    final validTo = _validTo;
    if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.validToBeforeValidFrom)));
      return;
    }
    setState(() => _saving = true);
    try {
      await ref.read(gatePassServiceProvider).registerGateVisitor({
        'propertyId': _propertyId,
        'unitId': _unitId,
        'name': _name.text.trim(),
        'phone': _phone.text.trim(),
        'visitorType': _type,
        // Date pickers yield local midnight; the window runs from the start
        // of the first day to the end of the last, encoded as UTC instants.
        'validFrom': validFrom == null ? null : instant(validFrom),
        'validTo': validTo == null
            ? null
            : instant(
                DateTime(validTo.year, validTo.month, validTo.day, 23, 59, 59),
              ),
        'active': _active,
      });
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_active ? l.vendorRegistered : l.vendorAccessRevoked),
        ),
      );
      _name.clear();
      _phone.clear();
      setState(() {
        _validFrom = null;
        _validTo = null;
        _active = true;
      });
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.couldNotRegister)));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  TextStyle _fieldStyle(bool ar, MiftahColors m) => ar
      ? GoogleFonts.notoNaskhArabic(fontSize: 14, color: m.textPrimary)
      : GoogleFonts.josefinSans(fontSize: 14, color: m.textPrimary);

  /// Optional, clearable date row — same pattern as the listing edit screen's
  /// "available from" field.
  Widget _dateField({
    required MiftahColors m,
    required _L l,
    required String label,
    required String hint,
    required DateTime? value,
    required ValueChanged<DateTime> onPicked,
    required VoidCallback onCleared,
  }) {
    return GestureDetector(
      onTap: () async {
        final now = DateTime.now();
        final date = await showDatePicker(
          context: context,
          initialDate: value ?? now,
          firstDate: now.subtract(const Duration(days: 365)),
          lastDate: now.add(const Duration(days: 730)),
        );
        if (date != null) onPicked(date);
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
            Icon(Icons.calendar_today_outlined, size: 18, color: m.textMuted),
            const SizedBox(width: 10),
            Text(
              value == null
                  ? hint
                  : '$label: ${value.toIso8601String().split('T').first}',
              style: _fieldStyle(l.ar, m).copyWith(
                color: value != null ? m.textPrimary : m.textMuted,
              ),
            ),
            if (value != null) ...[
              const Spacer(),
              GestureDetector(
                onTap: onCleared,
                child: Icon(Icons.close, size: 16, color: m.textMuted),
              ),
            ],
          ],
        ),
      ),
    );
  }

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
      ),
      body: properties.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.accent),
        ),
        error: (error, stack) => Center(child: Text(l.couldNotLoadProperties)),
        data: (rows) => Form(
          key: _key,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                l.intro,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 13,
                        color: m.textSecondary,
                        height: 1.5,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 13,
                        color: m.textSecondary,
                        height: 1.5,
                      ),
              ),
              const SizedBox(height: 18),
              DropdownButtonFormField<String>(
                decoration: InputDecoration(labelText: l.property),
                style: _fieldStyle(l.ar, m),
                items: rows
                    .map(
                      (row) => DropdownMenuItem(
                        value: row['id']?.toString(),
                        child: Text(row['name']?.toString() ?? l.property),
                      ),
                    )
                    .toList(),
                onChanged: (id) {
                  if (id != null) _propertyChanged(id);
                },
                validator: (value) => value == null ? l.selectProperty : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                // Keyed on the property (not the selected unit): a new key
                // resets the FormField, which must happen when the property
                // changes but not when a unit is picked — keying on `_unitId`
                // wiped the selection right after it was made, so validation
                // could never pass and the form could never submit.
                key: ValueKey('vendor-unit-$_propertyId-${_units.length}'),
                initialValue: _unitId,
                decoration: InputDecoration(
                  labelText: _loadingUnits ? l.loadingUnits : l.unit,
                ),
                style: _fieldStyle(l.ar, m),
                items: _units
                    .map(
                      (row) => DropdownMenuItem(
                        value: row['id']?.toString(),
                        child: Text(row['unitNumber']?.toString() ?? l.unit),
                      ),
                    )
                    .toList(),
                onChanged: (id) => setState(() => _unitId = id),
                validator: (value) => value == null ? l.selectUnit : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _name,
                textCapitalization: TextCapitalization.words,
                style: _fieldStyle(l.ar, m),
                decoration: InputDecoration(labelText: l.name),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? l.enterName : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                style: _fieldStyle(l.ar, m),
                decoration: InputDecoration(
                  labelText: l.mobileNumber,
                  hintText: '+971501234567',
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? l.enterMobileNumber
                    : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: InputDecoration(labelText: l.vendorType),
                style: _fieldStyle(l.ar, m),
                items: [
                  DropdownMenuItem(value: 'MAID', child: Text(l.maid)),
                  DropdownMenuItem(
                    value: 'MILK_VENDOR',
                    child: Text(l.milkVendor),
                  ),
                  DropdownMenuItem(
                    value: 'LAUNDRY_VENDOR',
                    child: Text(l.laundryVendor),
                  ),
                  DropdownMenuItem(
                    value: 'SERVICE_VENDOR',
                    child: Text(l.serviceVendor),
                  ),
                  DropdownMenuItem(value: 'OTHER', child: Text(l.other)),
                ],
                onChanged: (value) => setState(() => _type = value!),
              ),
              const SizedBox(height: 14),
              _dateField(
                m: m,
                l: l,
                label: l.validFrom,
                hint: l.validFromHint,
                value: _validFrom,
                onPicked: (date) => setState(() => _validFrom = date),
                onCleared: () => setState(() => _validFrom = null),
              ),
              const SizedBox(height: 14),
              _dateField(
                m: m,
                l: l,
                label: l.validTo,
                hint: l.validToHint,
                value: _validTo,
                onPicked: (date) => setState(() => _validTo = date),
                onCleared: () => setState(() => _validTo = null),
              ),
              const SizedBox(height: 6),
              SwitchListTile(
                value: _active,
                activeThumbColor: AppColors.accent,
                contentPadding: EdgeInsets.zero,
                title: Text(l.accessActive, style: _fieldStyle(l.ar, m)),
                subtitle: Text(
                  l.accessActiveHint,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          color: m.textSecondary,
                          height: 1.4,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 12,
                          color: m.textSecondary,
                          height: 1.4,
                        ),
                ),
                onChanged: (value) => setState(() => _active = value),
              ),
              const SizedBox(height: 14),
              GoldButton(
                label: _saving ? l.saving : l.registerVendor,
                onPressed: _saving ? null : _save,
                icon: _saving
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppColors.primary,
                        ),
                      )
                    : const Icon(Icons.how_to_reg),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
