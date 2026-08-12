import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _settingsServiceProvider = Provider<SettingsService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettingsService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _propertiesProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

class RentSettingsScreen extends ConsumerStatefulWidget {
  const RentSettingsScreen({super.key});

  @override
  ConsumerState<RentSettingsScreen> createState() => _RentSettingsScreenState();
}

class _RentSettingsScreenState extends ConsumerState<RentSettingsScreen> {
  String? _selectedPropertyId;
  String? _selectedPropertyName;
  bool _loadingSettings = false;
  Map<String, dynamic>? _settings;
  String? _error;
  bool _forbidden = false;

  Future<void> _loadSettings(String propertyId) async {
    setState(() {
      _loadingSettings = true;
      _error = null;
      _forbidden = false;
    });
    try {
      final service = ref.read(_settingsServiceProvider);
      final data = await service.getRentSettings(propertyId);
      setState(() {
        _settings = data;
        _loadingSettings = false;
      });
    } catch (e) {
      // GET /v1/rent-settings/{id} is SUPER_ADMIN/TENANT_ADMIN only — a 403
      // is a permission boundary, not a transient failure.
      final forbidden = e is DioException && e.response?.statusCode == 403;
      setState(() {
        _forbidden = forbidden;
        _error = forbidden
            ? _L(context.isAr).noPermission
            : _L(context.isAr).failedToLoadSettings;
        _loadingSettings = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final propertiesAsync = ref.watch(_propertiesProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(l: l),
          Expanded(
            child: propertiesAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
              error: (e, _) => ErrorState(
                message: l.failedToLoadProperties,
                onRetry: () => ref.invalidate(_propertiesProvider),
              ),
              data: (properties) {
                return SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _PropertyDropdown(
                        properties: properties,
                        selectedId: _selectedPropertyId,
                        l: l,
                        onChanged: (value, name) {
                          setState(() {
                            _selectedPropertyId = value;
                            _selectedPropertyName = name;
                          });
                          _loadSettings(value);
                        },
                      ),
                      const SizedBox(height: 24),
                      if (_selectedPropertyId == null)
                        Center(
                          child: Padding(
                            padding: const EdgeInsets.only(top: 40),
                            child: Column(
                              children: [
                                Icon(
                                  Icons.touch_app_outlined,
                                  size: 48,
                                  color: m.textMuted,
                                ),
                                const SizedBox(height: 12),
                                Text(
                                  l.selectPropertyPrompt,
                                  style: l.ar
                                      ? GoogleFonts.notoNaskhArabic(
                                          fontSize: 14,
                                          color: m.textMuted,
                                        )
                                      : GoogleFonts.plusJakartaSans(
                                          fontSize: 13,
                                          color: m.textMuted,
                                        ),
                                ),
                              ],
                            ),
                          ),
                        )
                      else if (_loadingSettings)
                        Center(
                          child: Padding(
                            padding: const EdgeInsets.only(top: 40),
                            child: CircularProgressIndicator(
                              color: AppColors.accent,
                            ),
                          ),
                        )
                      else if (_error != null)
                        ErrorState(
                          message: _error!,
                          // Retrying a 403 can never succeed.
                          onRetry: _forbidden
                              ? null
                              : () => _loadSettings(_selectedPropertyId!),
                        )
                      else if (_settings == null)
                        EmptyState(
                          icon: Icons.settings_outlined,
                          title: l.noSettings,
                        )
                      else
                        _SettingsCard(
                          propertyName: _selectedPropertyName,
                          settings: _settings!,
                          l: l,
                        ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _ChromeHeader extends StatelessWidget {
  final _L l;
  const _ChromeHeader({required this.l});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(8, 4, 20, 18),
      child: SafeArea(
        bottom: false,
        child: Row(
          children: [
            IconButton(
              onPressed: () => context.pop(),
              icon: Icon(
                context.isAr ? Icons.chevron_right : Icons.chevron_left,
                color: AppColors.accent,
                size: 26,
              ),
            ),
            Flexible(
              child: Text(
                l.title,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 15,
                        letterSpacing: 1.6,
                        color: Colors.white,
                      ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PropertyDropdown extends StatelessWidget {
  final List<dynamic> properties;
  final String? selectedId;
  final _L l;
  final void Function(String value, String name) onChanged;

  const _PropertyDropdown({
    required this.properties,
    required this.selectedId,
    required this.l,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 14),
      child: DropdownButtonHideUnderline(
        child: DropdownButtonFormField<String>(
          // ignore: deprecated_member_use
          value: selectedId,
          dropdownColor: m.surface,
          icon: Icon(Icons.expand_more, color: m.textMuted),
          decoration: InputDecoration(
            border: InputBorder.none,
            labelText: l.selectProperty,
            labelStyle: l.ar
                ? GoogleFonts.notoNaskhArabic(fontSize: 13, color: m.textMuted)
                : GoogleFonts.plusJakartaSans(fontSize: 12, color: m.textMuted),
            prefixIcon: Icon(Icons.apartment, color: AppColors.accentDark),
          ),
          items: properties.map<DropdownMenuItem<String>>((p) {
            final id = p['id']?.toString() ?? '';
            final name = p['name']?.toString() ?? l.unnamed;
            return DropdownMenuItem(
              value: id,
              child: Text(
                name,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 14,
                        color: m.textPrimary,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 14,
                        color: m.textPrimary,
                      ),
              ),
            );
          }).toList(),
          onChanged: (value) {
            if (value == null) return;
            final prop = properties.firstWhere(
              (p) => p['id']?.toString() == value,
            );
            onChanged(value, prop['name']?.toString() ?? l.unnamed);
          },
        ),
      ),
    );
  }
}

class _SettingsCard extends StatelessWidget {
  final String? propertyName;
  final Map<String, dynamic> settings;
  final _L l;

  const _SettingsCard({
    required this.propertyName,
    required this.settings,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            propertyName ?? l.propertySettings,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.plusJakartaSans(fontSize: 16, color: m.textPrimary),
          ),
          const SizedBox(height: 4),
          Divider(color: m.divider, height: 24),
          // Keys per RentCollectionSettingsDTO: dueDayOfMonth,
          // gracePeriodDays, penaltyType, penaltyAmount,
          // onlinePaymentEnabled.
          _SettingRow(
            icon: Icons.event_outlined,
            label: l.dueDay,
            value: l.dayOfMonth(settings['dueDayOfMonth']),
          ),
          _SettingRow(
            icon: Icons.timer_outlined,
            label: l.gracePeriod,
            value: l.days(settings['gracePeriodDays']),
          ),
          if (settings['penaltyType'] != null)
            _SettingRow(
              icon: Icons.category_outlined,
              label: l.penaltyType,
              value: l.penaltyTypeLabel((settings['penaltyType'] as String)),
            ),
          if (settings['penaltyType'] != null &&
              settings['penaltyType'] != 'NONE')
            _SettingRow(
              icon: Icons.percent,
              label: l.penaltyAmount,
              value: l.penaltyAmountValue(
                settings['penaltyAmount'],
                settings['penaltyType'] as String,
              ),
            ),
          _SettingRow(
            icon: Icons.credit_card_outlined,
            label: l.onlinePayment,
            value: settings['onlinePaymentEnabled'] == true ? l.yes : l.no,
          ),
        ],
      ),
    );
  }
}

class _SettingRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _SettingRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Row(
        children: [
          Icon(icon, size: 20, color: AppColors.accentDark),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              label,
              style: ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 13,
                      color: m.textSecondary,
                    ),
            ),
          ),
          Text(
            value,
            style: ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'إعدادات تحصيل الإيجار' : 'Rent Collection Settings';
  String get failedToLoadProperties =>
      ar ? 'تعذر تحميل العقارات' : 'Failed to load properties';
  String get failedToLoadSettings =>
      ar ? 'تعذر تحميل إعدادات الإيجار' : 'Failed to load rent settings';
  String get selectProperty => ar ? 'اختر العقار' : 'Select Property';
  String get unnamed => ar ? 'بدون اسم' : 'Unnamed';
  String get selectPropertyPrompt =>
      ar ? 'اختر عقاراً لعرض الإعدادات' : 'Select a property to view settings';
  String get noSettings => ar
      ? 'لا توجد إعدادات إيجار مضبوطة لهذا العقار'
      : 'No rent settings configured for this property';
  String get propertySettings => ar ? 'إعدادات العقار' : 'Property Settings';
  String get dueDay => ar ? 'يوم الاستحقاق الشهري' : 'Due Day of Month';
  String get gracePeriod => ar ? 'فترة السماح' : 'Grace Period';
  String get penaltyType => ar ? 'نوع الغرامة' : 'Penalty Type';
  String get penaltyAmount =>
      ar ? 'مبلغ غرامة التأخير' : 'Late Payment Penalty';
  String get onlinePayment => ar ? 'الدفع عبر الإنترنت' : 'Online Payment';
  String get noPermission => ar
      ? 'ليس لديك صلاحية لعرض إعدادات تحصيل الإيجار'
      : 'You do not have permission to view rent collection settings';
  String get yes => ar ? 'نعم' : 'Yes';
  String get no => ar ? 'لا' : 'No';

  String days(dynamic n) => ar ? '${n ?? '-'} يوم' : '${n ?? '-'} days';
  String dayOfMonth(dynamic n) => ar ? 'اليوم ${n ?? '-'}' : 'Day ${n ?? '-'}';

  /// penaltyAmount is AED/day for FIXED_PER_DAY and %/day for PERCENTAGE
  /// (mirrors the web rent-settings labels).
  String penaltyAmountValue(dynamic n, String type) {
    if (type == 'FIXED_PER_DAY') {
      return ar ? '${n ?? '-'} درهم/يوم' : '${n ?? '-'} AED/day';
    }
    if (type == 'PERCENTAGE') {
      return ar ? '${n ?? '-'}%/يوم' : '${n ?? '-'}%/day';
    }
    return '${n ?? '-'}';
  }

  String penaltyTypeLabel(String type) {
    // Keys mirror the backend PenaltyType enum: NONE, FIXED_PER_DAY,
    // PERCENTAGE.
    const arMap = {
      'NONE': 'لا شيء',
      'FIXED_PER_DAY': 'مبلغ ثابت يومياً',
      'PERCENTAGE': 'نسبة من الإيجار',
    };
    if (ar) return arMap[type] ?? type.replaceAll('_', ' ');
    return type.replaceAll('_', ' ');
  }
}
