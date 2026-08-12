import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'سياسة الدخول للبوابة' : 'Gate Access Policy';
  String get loadPolicyFailed =>
      ar ? 'تعذّر تحميل سياسة البوابة.' : 'Could not load the gate policy.';
  String get savePolicyFailed =>
      ar ? 'تعذّر حفظ سياسة البوابة.' : 'Could not save the gate policy.';
  String get propertyPolicySaved =>
      ar ? 'تم حفظ سياسة العقار.' : 'Property policy saved.';
  String get towerOverrideSaved =>
      ar ? 'تم حفظ استثناء البرج.' : 'Tower override saved.';
  String get couldNotLoadProperties =>
      ar ? 'تعذّر تحميل العقارات.' : 'Could not load properties.';
  String get property => ar ? 'العقار' : 'Property';
  String get scope => ar ? 'النطاق' : 'Scope';
  String get scopeHelper => ar
      ? 'اختر برجًا لتجاوز الإعداد الافتراضي للعقار.'
      : 'Choose a tower to override the property default.';
  String get propertyDefault =>
      ar ? 'الإعداد الافتراضي للعقار' : 'Property default';
  String get tower => ar ? 'برج' : 'Tower';
  String get approveUnregistered => ar
      ? 'اعتماد الزوار وسائقي التوصيل الجدد'
      : 'Approve new visitors and delivery riders';
  String get approveUnregisteredSubtitle => ar
      ? 'يبقون عند البوابة حتى يوافق أحد السكان.'
      : 'They remain at the gate until a resident approves.';
  String get approveRegisteredEveryVisit => ar
      ? 'اعتماد المورّدين المسجّلين في كل زيارة'
      : 'Approve registered vendors every visit';
  String get notifyResidents => ar
      ? 'إشعار السكان عند وصول موردهم'
      : 'Notify residents when their vendor arrives';
  String get requireFreshPhoto =>
      ar ? 'طلب صورة كاميرا حديثة' : 'Require a fresh camera photo';
  String get approvalTimeout => ar ? 'مهلة الاعتماد' : 'Approval timeout';
  String minutes(int n) => ar ? '$n دقيقة' : '$n min';
  String minutesLabel(int n) => ar ? '$n دقيقة' : '$n minutes';
  String get saving => ar ? 'جارٍ الحفظ…' : 'Saving…';
  String get savePolicy => ar ? 'حفظ السياسة' : 'Save policy';
}

class GateAccessPolicyScreen extends ConsumerStatefulWidget {
  const GateAccessPolicyScreen({super.key});

  @override
  ConsumerState<GateAccessPolicyScreen> createState() =>
      _GateAccessPolicyScreenState();
}

class _GateAccessPolicyScreenState
    extends ConsumerState<GateAccessPolicyScreen> {
  String? _propertyId;
  String? _buildingId;
  List<Map<String, dynamic>> _buildings = const [];
  bool _loading = false;
  bool _saving = false;
  bool _requireUnregisteredApproval = true;
  bool _requireRegisteredApproval = false;
  bool _notifyRegisteredEntry = true;
  bool _requireFreshPhoto = true;
  int _timeout = 15;

  Future<void> _selectProperty(String id) async {
    setState(() {
      _propertyId = id;
      _buildingId = null;
      _loading = true;
    });
    final client = ref.read(apiClientProvider);
    try {
      final results = await Future.wait([
        BuildingService(client.dio).getBuildingsByProperty(id),
        ref.read(gatePassServiceProvider).effectiveGatePolicy(propertyId: id),
      ]);
      if (!mounted) return;
      setState(() {
        _buildings = (results[0] as List)
            .whereType<Map>()
            .map((row) => Map<String, dynamic>.from(row))
            .toList();
        _apply(
          results[1] is Map
              ? Map<String, dynamic>.from(results[1] as Map)
              : const <String, dynamic>{},
        );
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _loading = false);
        _snack(_L(context.isAr).loadPolicyFailed);
      }
    }
  }

  Future<void> _selectBuilding(String? id) async {
    if (_propertyId == null) return;
    setState(() {
      _buildingId = id;
      _loading = true;
    });
    try {
      final policy = await ref
          .read(gatePassServiceProvider)
          .effectiveGatePolicy(propertyId: _propertyId!, buildingId: id);
      if (!mounted) return;
      setState(() {
        _apply(policy);
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _loading = false);
        _snack(_L(context.isAr).loadPolicyFailed);
      }
    }
  }

  void _apply(Map<String, dynamic> policy) {
    _requireUnregisteredApproval =
        policy['requireUnregisteredApproval'] != false;
    _requireRegisteredApproval = policy['requireRegisteredApproval'] == true;
    _notifyRegisteredEntry = policy['notifyRegisteredEntry'] != false;
    _requireFreshPhoto = policy['requireFreshPhoto'] != false;
    _timeout = (policy['approvalTimeoutMinutes'] as num?)?.toInt() ?? 15;
  }

  Future<void> _save() async {
    if (_propertyId == null || _saving) return;
    final l = _L(context.isAr);
    setState(() => _saving = true);
    try {
      await ref
          .read(gatePassServiceProvider)
          .saveGatePolicy(
            propertyId: _propertyId!,
            buildingId: _buildingId,
            policy: {
              'requireUnregisteredApproval': _requireUnregisteredApproval,
              'requireRegisteredApproval': _requireRegisteredApproval,
              'notifyRegisteredEntry': _notifyRegisteredEntry,
              'requireFreshPhoto': _requireFreshPhoto,
              'approvalTimeoutMinutes': _timeout,
            },
          );
      _snack(
        _buildingId == null ? l.propertyPolicySaved : l.towerOverrideSaved,
      );
    } catch (_) {
      _snack(l.savePolicyFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  TextStyle _labelStyle(bool ar, LegacyMiftahColors m) => ar
      ? GoogleFonts.notoNaskhArabic(fontSize: 14, color: m.textPrimary)
      : GoogleFonts.josefinSans(fontSize: 14, color: m.textPrimary);

  TextStyle _subtitleStyle(bool ar, LegacyMiftahColors m) => ar
      ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textSecondary)
      : GoogleFonts.josefinSans(fontSize: 12, color: m.textSecondary);

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
        data: (rows) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _SectionCard(
              m: m,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  DropdownButtonFormField<String>(
                    initialValue: _propertyId,
                    decoration: InputDecoration(labelText: l.property),
                    style: _labelStyle(l.ar, m),
                    items: rows
                        .map(
                          (row) => DropdownMenuItem(
                            value: row['id']?.toString(),
                            child: Text(
                              row['name']?.toString() ??
                                  row['nameEn']?.toString() ??
                                  l.property,
                            ),
                          ),
                        )
                        .toList(),
                    onChanged: (id) {
                      if (id != null) _selectProperty(id);
                    },
                  ),
                  const SizedBox(height: 14),
                  DropdownButtonFormField<String?>(
                    key: ValueKey(
                      'policy-scope-$_propertyId-$_buildingId-${_buildings.length}',
                    ),
                    initialValue: _buildingId,
                    decoration: InputDecoration(
                      labelText: l.scope,
                      helperText: l.scopeHelper,
                    ),
                    style: _labelStyle(l.ar, m),
                    items: [
                      DropdownMenuItem<String?>(
                        value: null,
                        child: Text(l.propertyDefault),
                      ),
                      ..._buildings.map(
                        (row) => DropdownMenuItem<String?>(
                          value: row['id']?.toString(),
                          child: Text(row['nameEn']?.toString() ?? l.tower),
                        ),
                      ),
                    ],
                    onChanged: _propertyId == null ? null : _selectBuilding,
                  ),
                ],
              ),
            ),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(28),
                child: Center(
                  child: CircularProgressIndicator(color: AppColors.accent),
                ),
              )
            else if (_propertyId != null) ...[
              const SizedBox(height: 14),
              _SectionCard(
                m: m,
                padding: EdgeInsets.zero,
                child: Column(
                  children: [
                    _PolicySwitchTile(
                      m: m,
                      value: _requireUnregisteredApproval,
                      title: l.approveUnregistered,
                      subtitle: l.approveUnregisteredSubtitle,
                      titleStyle: _labelStyle(l.ar, m),
                      subtitleStyle: _subtitleStyle(l.ar, m),
                      onChanged: (value) =>
                          setState(() => _requireUnregisteredApproval = value),
                    ),
                    Divider(height: 1, color: m.divider),
                    _PolicySwitchTile(
                      m: m,
                      value: _requireRegisteredApproval,
                      title: l.approveRegisteredEveryVisit,
                      titleStyle: _labelStyle(l.ar, m),
                      onChanged: (value) =>
                          setState(() => _requireRegisteredApproval = value),
                    ),
                    Divider(height: 1, color: m.divider),
                    _PolicySwitchTile(
                      m: m,
                      value: _notifyRegisteredEntry,
                      title: l.notifyResidents,
                      titleStyle: _labelStyle(l.ar, m),
                      onChanged: (value) =>
                          setState(() => _notifyRegisteredEntry = value),
                    ),
                    Divider(height: 1, color: m.divider),
                    _PolicySwitchTile(
                      m: m,
                      value: _requireFreshPhoto,
                      title: l.requireFreshPhoto,
                      titleStyle: _labelStyle(l.ar, m),
                      onChanged: (value) =>
                          setState(() => _requireFreshPhoto = value),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 14),
              _SectionCard(
                m: m,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(l.approvalTimeout, style: _labelStyle(l.ar, m)),
                        Text(
                          l.minutes(_timeout),
                          style: _subtitleStyle(l.ar, m),
                        ),
                      ],
                    ),
                    SliderTheme(
                      data: SliderTheme.of(context).copyWith(
                        activeTrackColor: AppColors.accent,
                        thumbColor: AppColors.accent,
                        inactiveTrackColor: m.borderStrong,
                      ),
                      child: Slider(
                        value: _timeout.toDouble(),
                        min: 5,
                        max: 60,
                        divisions: 11,
                        label: l.minutesLabel(_timeout),
                        onChanged: (value) =>
                            setState(() => _timeout = value.round()),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              GoldButton(
                label: _saving ? l.saving : l.savePolicy,
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
                    : const Icon(Icons.save_outlined),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.m,
    required this.child,
    this.padding = const EdgeInsets.all(14),
  });

  final LegacyMiftahColors m;
  final Widget child;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      padding: padding,
      child: child,
    );
  }
}

class _PolicySwitchTile extends StatelessWidget {
  const _PolicySwitchTile({
    required this.m,
    required this.value,
    required this.title,
    required this.titleStyle,
    this.subtitle,
    this.subtitleStyle,
    required this.onChanged,
  });

  final LegacyMiftahColors m;
  final bool value;
  final String title;
  final TextStyle titleStyle;
  final String? subtitle;
  final TextStyle? subtitleStyle;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      value: value,
      activeThumbColor: AppColors.accent,
      title: Text(title, style: titleStyle),
      subtitle: subtitle == null ? null : Text(subtitle!, style: subtitleStyle),
      onChanged: onChanged,
    );
  }
}
