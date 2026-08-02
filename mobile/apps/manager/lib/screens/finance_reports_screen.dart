import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'report_detail_screen.dart';

final _financeServiceProvider = Provider<FinanceService>((ref) {
  final client = ref.watch(apiClientProvider);
  return FinanceService(client.dio);
});

final _reportServiceProvider = Provider<ReportService>((ref) {
  final client = ref.watch(apiClientProvider);
  return ReportService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _unitServiceProvider = Provider<UnitService>((ref) {
  final client = ref.watch(apiClientProvider);
  return UnitService(client.dio);
});

final _vendorServiceProvider = Provider<VendorService>((ref) {
  final client = ref.watch(apiClientProvider);
  return VendorService(client.dio);
});

class FinanceReportsScreen extends ConsumerStatefulWidget {
  const FinanceReportsScreen({super.key});

  @override
  ConsumerState<FinanceReportsScreen> createState() =>
      _FinanceReportsScreenState();
}

class _FinanceReportsScreenState extends ConsumerState<FinanceReportsScreen> {
  String? _startDate;
  String? _endDate;
  bool _loading = false;

  Future<void> _pickStartDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _startDate != null
          ? DateTime.parse(_startDate!)
          : DateTime.now(),
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
    );
    if (picked != null) {
      setState(() => _startDate = DateFormat('yyyy-MM-dd').format(picked));
    }
  }

  Future<void> _pickEndDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _endDate != null
          ? DateTime.parse(_endDate!)
          : DateTime.now(),
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
    );
    if (picked != null) {
      setState(() => _endDate = DateFormat('yyyy-MM-dd').format(picked));
    }
  }

  void _navigateToReport(String type, String title, dynamic data) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ReportDetailScreen(
          reportType: type,
          reportTitle: title,
          reportData: data,
        ),
      ),
    );
  }

  Future<void> _loadReport(_ReportType type) async {
    final l = _L(context.isAr);
    setState(() => _loading = true);
    try {
      switch (type.key) {
        case 'orgSummary':
          final data = await ref
              .read(_financeServiceProvider)
              .getOrganisationReport(startDate: _startDate, endDate: _endDate);
          if (mounted) _navigateToReport(type.key, type.label(l), data);
          break;

        case 'trialBalance':
          final data = await ref
              .read(_financeServiceProvider)
              .getTrialBalance(startDate: _startDate, endDate: _endDate);
          if (mounted) _navigateToReport(type.key, type.label(l), data);
          break;

        case 'vatReturn':
          if (_startDate == null || _endDate == null) {
            if (mounted) {
              ScaffoldMessenger.of(
                context,
              ).showSnackBar(SnackBar(content: Text(l.vatDateRangeRequired)));
            }
            break;
          }
          final data = await ref
              .read(_reportServiceProvider)
              .getVatReturn(startDate: _startDate!, endDate: _endDate!);
          if (mounted) _navigateToReport(type.key, type.label(l), data);
          break;

        case 'propertyReport':
          if (mounted) await _showPropertyPicker(l);
          break;

        case 'unitReport':
          if (mounted) await _showUnitPicker(l);
          break;

        case 'vendorLedger':
          if (mounted) await _showVendorPicker(l);
          break;
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(l.failedToLoadReport(e.toString()))),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _showPropertyPicker(_L l) async {
    final properties = await ref.read(_propertyServiceProvider).getProperties();
    if (!mounted) return;

    final selected = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => _PickerDialog(
        title: l.selectProperty,
        items: properties,
        nameKey: 'name',
        l: l,
      ),
    );
    if (selected == null || !mounted) return;

    setState(() => _loading = true);
    try {
      final data = await ref
          .read(_reportServiceProvider)
          .getPropertyReport(
            selected['id'],
            startDate: _startDate,
            endDate: _endDate,
          );
      if (mounted) _navigateToReport('propertyReport', l.propertyReport, data);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToLoadPropertyReport)));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _showUnitPicker(_L l) async {
    final units = await ref.read(_unitServiceProvider).getUnits();
    if (!mounted) return;

    final selected = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => _PickerDialog(
        title: l.selectUnit,
        items: units,
        nameKey: 'unitNumber',
        subtitleKey: 'propertyName',
        l: l,
      ),
    );
    if (selected == null || !mounted) return;

    setState(() => _loading = true);
    try {
      final data = await ref
          .read(_reportServiceProvider)
          .getUnitReport(
            selected['id'],
            startDate: _startDate,
            endDate: _endDate,
          );
      if (mounted) _navigateToReport('unitReport', l.unitReport, data);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToLoadUnitReport)));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _showVendorPicker(_L l) async {
    final vendors = await ref.read(_vendorServiceProvider).getVendors();
    if (!mounted) return;

    final selected = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => _PickerDialog(
        title: l.selectVendor,
        items: vendors,
        nameKey: 'name',
        l: l,
      ),
    );
    if (selected == null || !mounted) return;

    setState(() => _loading = true);
    try {
      final data = await ref
          .read(_reportServiceProvider)
          .getVendorLedger(
            selected['id'],
            startDate: _startDate,
            endDate: _endDate,
          );
      if (mounted) _navigateToReport('vendorLedger', l.vendorLedger, data);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToLoadVendorLedger)));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final reports = [
      _ReportType(
        'orgSummary',
        (l) => l.orgSummary,
        Icons.business,
        AppColors.primary,
      ),
      _ReportType(
        'trialBalance',
        (l) => l.trialBalance,
        Icons.balance,
        AppColors.accent,
      ),
      _ReportType(
        'vatReturn',
        (l) => l.vatReturn,
        Icons.receipt_long,
        AppColors.info,
      ),
      _ReportType(
        'propertyReport',
        (l) => l.propertyReport,
        Icons.apartment,
        m.success,
      ),
      _ReportType(
        'unitReport',
        (l) => l.unitReport,
        Icons.door_front_door,
        m.warning,
      ),
      _ReportType(
        'vendorLedger',
        (l) => l.vendorLedger,
        Icons.store,
        AppColors.navyDark,
      ),
    ];

    return Stack(
      children: [
        Scaffold(
          backgroundColor: m.background,
          body: Column(
            children: [
              _ChromeHeader(l: l),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: _DateField(
                              label: l.from,
                              date: _startDate,
                              ar: l.ar,
                              onTap: _pickStartDate,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: _DateField(
                              label: l.to,
                              date: _endDate,
                              ar: l.ar,
                              onTap: _pickEndDate,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 20),
                      GridView.count(
                        // Nested in a scroll view: without this the sliver auto-pads
                        // with MediaQuery.padding, which under extendBody carries the
                        // floating nav height and opens a gap below the content.
                        padding: EdgeInsets.zero,
                        crossAxisCount: 2,
                        childAspectRatio: 1.4,
                        crossAxisSpacing: 12,
                        mainAxisSpacing: 12,
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        children: reports
                            .map(
                              (r) => _ReportCard(
                                reportType: r,
                                l: l,
                                onTap: () => _loadReport(r),
                              ),
                            )
                            .toList(),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        if (_loading)
          Container(
            color: Colors.black38,
            child: Center(
              child: CircularProgressIndicator(color: AppColors.accent),
            ),
          ),
      ],
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
                    : GoogleFonts.cinzel(
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

class _ReportType {
  final String key;
  final String Function(_L l) label;
  final IconData icon;
  final Color color;

  const _ReportType(this.key, this.label, this.icon, this.color);
}

class _DateField extends StatelessWidget {
  final String label;
  final String? date;
  final bool ar;
  final VoidCallback onTap;

  const _DateField({
    required this.label,
    required this.date,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: m.border),
        ),
        child: Row(
          children: [
            Icon(Icons.calendar_today, size: 16, color: m.textMuted),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 11,
                            color: m.textMuted,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 9.5,
                            letterSpacing: 1.4,
                            color: m.textMuted,
                          ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    date != null
                        ? Formatters.date(date, ar: ar)
                        : (ar ? 'اختر' : 'Select'),
                    style: ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w600,
                            color: date != null ? m.textPrimary : m.textMuted,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            color: date != null ? m.textPrimary : m.textMuted,
                          ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ReportCard extends StatelessWidget {
  final _ReportType reportType;
  final _L l;
  final VoidCallback onTap;

  const _ReportCard({
    required this.reportType,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: m.border),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: reportType.color.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(reportType.icon, color: reportType.color, size: 22),
            ),
            const SizedBox(height: 10),
            Text(
              reportType.label(l),
              textAlign: TextAlign.center,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }
}

class _PickerDialog extends StatelessWidget {
  final String title;
  final List<dynamic> items;
  final String nameKey;
  final String? subtitleKey;
  final _L l;

  const _PickerDialog({
    required this.title,
    required this.items,
    required this.nameKey,
    required this.l,
    this.subtitleKey,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return AlertDialog(
      backgroundColor: m.surface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      title: Text(
        title,
        style: l.ar
            ? GoogleFonts.notoNaskhArabic(
                fontWeight: FontWeight.w600,
                color: m.textPrimary,
              )
            : GoogleFonts.cinzel(fontSize: 17, color: m.textPrimary),
      ),
      content: SizedBox(
        width: double.maxFinite,
        child: items.isEmpty
            ? Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  l.noItemsFound,
                  textAlign: TextAlign.center,
                  style: GoogleFonts.josefinSans(color: m.textMuted),
                ),
              )
            : ListView.builder(
                // Nested in a scroll view: without this the sliver auto-pads
                // with MediaQuery.padding, which under extendBody carries the
                // floating nav height and opens a gap below the content.
                padding: EdgeInsets.zero,
                shrinkWrap: true,
                itemCount: items.length,
                itemBuilder: (ctx, index) {
                  final item = items[index] as Map<String, dynamic>;
                  final name = (item[nameKey] ?? '-').toString();
                  final subtitle = subtitleKey != null
                      ? (item[subtitleKey] ?? '').toString()
                      : null;

                  return ListTile(
                    title: Text(
                      name,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(color: m.textPrimary)
                          : GoogleFonts.josefinSans(color: m.textPrimary),
                    ),
                    subtitle: subtitle != null && subtitle.isNotEmpty
                        ? Text(
                            subtitle,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    color: m.textMuted,
                                  )
                                : GoogleFonts.josefinSans(color: m.textMuted),
                          )
                        : null,
                    onTap: () => Navigator.pop(ctx, item),
                  );
                },
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(
            l.cancel,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(fontWeight: FontWeight.w600)
                : GoogleFonts.josefinSans(fontWeight: FontWeight.w600),
          ),
        ),
      ],
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'التقارير المالية' : 'Financial Reports';
  String get from => ar ? 'من' : 'From';
  String get to => ar ? 'إلى' : 'To';
  String get orgSummary => ar ? 'ملخص المؤسسة' : 'Organisation Summary';
  String get trialBalance => ar ? 'ميزان المراجعة' : 'Trial Balance';
  String get vatReturn => ar ? 'إقرار ضريبة القيمة المضافة' : 'VAT Return';
  String get propertyReport => ar ? 'تقرير العقار' : 'Property Report';
  String get unitReport => ar ? 'تقرير الوحدة' : 'Unit Report';
  String get vendorLedger => ar ? 'دفتر أستاذ المورّد' : 'Vendor Ledger';
  String get selectProperty => ar ? 'اختر العقار' : 'Select Property';
  String get selectUnit => ar ? 'اختر الوحدة' : 'Select Unit';
  String get selectVendor => ar ? 'اختر المورّد' : 'Select Vendor';
  String get noItemsFound => ar ? 'لا توجد عناصر' : 'No items found';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get vatDateRangeRequired => ar
      ? 'يرجى تحديد تاريخ البداية والنهاية لإقرار الضريبة'
      : 'Please select both start and end dates for VAT Return';
  String get failedToLoadPropertyReport =>
      ar ? 'تعذر تحميل تقرير العقار' : 'Failed to load property report';
  String get failedToLoadUnitReport =>
      ar ? 'تعذر تحميل تقرير الوحدة' : 'Failed to load unit report';
  String get failedToLoadVendorLedger =>
      ar ? 'تعذر تحميل دفتر أستاذ المورّد' : 'Failed to load vendor ledger';

  String failedToLoadReport(String err) =>
      ar ? 'تعذر تحميل التقرير: $err' : 'Failed to load report: $err';
}
