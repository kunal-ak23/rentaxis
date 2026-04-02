import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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
      initialDate:
          _startDate != null ? DateTime.parse(_startDate!) : DateTime.now(),
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
      initialDate:
          _endDate != null ? DateTime.parse(_endDate!) : DateTime.now(),
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
    );
    if (picked != null) {
      setState(() => _endDate = DateFormat('yyyy-MM-dd').format(picked));
    }
  }

  void _navigateToReport(String type, dynamic data) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ReportDetailScreen(
          reportType: type,
          reportData: data,
        ),
      ),
    );
  }

  Future<void> _loadReport(String type) async {
    setState(() => _loading = true);
    try {
      switch (type) {
        case 'Organisation Summary':
          final data = await ref
              .read(_financeServiceProvider)
              .getOrganisationReport(
                  startDate: _startDate, endDate: _endDate);
          if (mounted) _navigateToReport(type, data);
          break;

        case 'Trial Balance':
          final data = await ref
              .read(_financeServiceProvider)
              .getTrialBalance(startDate: _startDate, endDate: _endDate);
          if (mounted) _navigateToReport(type, data);
          break;

        case 'VAT Return':
          if (_startDate == null || _endDate == null) {
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text(
                      'Please select both start and end dates for VAT Return'),
                ),
              );
            }
            break;
          }
          final data = await ref
              .read(_reportServiceProvider)
              .getVatReturn(startDate: _startDate!, endDate: _endDate!);
          if (mounted) _navigateToReport(type, data);
          break;

        case 'Property Report':
          if (mounted) await _showPropertyPicker();
          break;

        case 'Unit Report':
          if (mounted) await _showUnitPicker();
          break;

        case 'Vendor Ledger':
          if (mounted) await _showVendorPicker();
          break;
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to load report: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _showPropertyPicker() async {
    final properties = await ref.read(_propertyServiceProvider).getProperties();
    if (!mounted) return;

    final selected = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => _PickerDialog(
        title: 'Select Property',
        items: properties,
        nameKey: 'name',
      ),
    );
    if (selected == null || !mounted) return;

    setState(() => _loading = true);
    try {
      final data = await ref
          .read(_reportServiceProvider)
          .getPropertyReport(selected['id'],
              startDate: _startDate, endDate: _endDate);
      if (mounted) _navigateToReport('Property Report', data);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to load property report: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _showUnitPicker() async {
    final units = await ref.read(_unitServiceProvider).getUnits();
    if (!mounted) return;

    final selected = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => _PickerDialog(
        title: 'Select Unit',
        items: units,
        nameKey: 'unitNumber',
        subtitleKey: 'propertyName',
      ),
    );
    if (selected == null || !mounted) return;

    setState(() => _loading = true);
    try {
      final data = await ref
          .read(_reportServiceProvider)
          .getUnitReport(selected['id'],
              startDate: _startDate, endDate: _endDate);
      if (mounted) _navigateToReport('Unit Report', data);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to load unit report: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _showVendorPicker() async {
    final vendors = await ref.read(_vendorServiceProvider).getVendors();
    if (!mounted) return;

    final selected = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => _PickerDialog(
        title: 'Select Vendor',
        items: vendors,
        nameKey: 'name',
      ),
    );
    if (selected == null || !mounted) return;

    setState(() => _loading = true);
    try {
      final data = await ref
          .read(_reportServiceProvider)
          .getVendorLedger(selected['id'],
              startDate: _startDate, endDate: _endDate);
      if (mounted) _navigateToReport('Vendor Ledger', data);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to load vendor ledger: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final reports = [
      _ReportType('Organisation Summary', Icons.business, AppColors.primary),
      _ReportType('Trial Balance', Icons.balance, AppColors.accent),
      _ReportType('VAT Return', Icons.receipt_long, AppColors.info),
      _ReportType('Property Report', Icons.apartment, AppColors.success),
      _ReportType('Unit Report', Icons.door_front_door, AppColors.warning),
      _ReportType('Vendor Ledger', Icons.store, AppColors.navyDark),
    ];

    return Stack(
      children: [
        Scaffold(
          appBar: AppBar(title: const Text('Financial Reports')),
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Date range row
                Row(
                  children: [
                    Expanded(
                      child: _DateField(
                        label: 'From',
                        date: _startDate,
                        onTap: _pickStartDate,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _DateField(
                        label: 'To',
                        date: _endDate,
                        onTap: _pickEndDate,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),

                // Report cards grid
                GridView.count(
                  crossAxisCount: 2,
                  childAspectRatio: 1.4,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  children: reports
                      .map((r) => _ReportCard(
                            reportType: r,
                            onTap: () => _loadReport(r.name),
                          ))
                      .toList(),
                ),
              ],
            ),
          ),
        ),
        if (_loading) const LoadingOverlay(),
      ],
    );
  }
}

class _ReportType {
  final String name;
  final IconData icon;
  final Color color;

  const _ReportType(this.name, this.icon, this.color);
}

class _DateField extends StatelessWidget {
  final String label;
  final String? date;
  final VoidCallback onTap;

  const _DateField({
    required this.label,
    required this.date,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: Row(
          children: [
            const Icon(Icons.calendar_today,
                size: 16, color: AppColors.textMuted),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      fontSize: 10,
                      color: AppColors.textMuted,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    date != null ? Formatters.date(date) : 'Select',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: date != null
                          ? AppColors.textPrimary
                          : AppColors.textMuted,
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
  final VoidCallback onTap;

  const _ReportCard({required this.reportType, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.border),
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
              child: Icon(
                reportType.icon,
                color: reportType.color,
                size: 22,
              ),
            ),
            const SizedBox(height: 10),
            Text(
              reportType.name,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
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

  const _PickerDialog({
    required this.title,
    required this.items,
    required this.nameKey,
    this.subtitleKey,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(title),
      content: SizedBox(
        width: double.maxFinite,
        child: items.isEmpty
            ? const Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'No items found',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.textMuted),
                ),
              )
            : ListView.builder(
                shrinkWrap: true,
                itemCount: items.length,
                itemBuilder: (ctx, index) {
                  final item = items[index] as Map<String, dynamic>;
                  final name = (item[nameKey] ?? '-').toString();
                  final subtitle = subtitleKey != null
                      ? (item[subtitleKey] ?? '').toString()
                      : null;

                  return ListTile(
                    title: Text(name),
                    subtitle: subtitle != null && subtitle.isNotEmpty
                        ? Text(subtitle)
                        : null,
                    onTap: () => Navigator.pop(ctx, item),
                  );
                },
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
      ],
    );
  }
}
