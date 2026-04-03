import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _paymentSummaryProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(_paymentServiceProvider);
  return service.getSummary();
});

final _paymentsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_paymentServiceProvider);
  return service.getPayments();
});

final _propertiesForFilterProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});

  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen> {
  String? _selectedPropertyId;
  String? _selectedStatus;

  final _statusOptions = [
    null,
    'PENDING',
    'COLLECTED',
    'DEPOSITED',
    'CLEARED',
    'BOUNCED',
    'OVERDUE',
  ];

  Future<void> _refresh() async {
    ref.invalidate(_paymentSummaryProvider);
    ref.invalidate(_paymentsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final summaryAsync = ref.watch(_paymentSummaryProvider);
    final paymentsAsync = ref.watch(_paymentsProvider);
    final propertiesAsync = ref.watch(_propertiesForFilterProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Payments')),
      body: Column(
        children: [
          // Summary cards
          summaryAsync.when(
            loading: () => const SizedBox(
              height: 90,
              child: Center(child: ListShimmer(itemCount: 1)),
            ),
            error: (_, __) => const SizedBox.shrink(),
            data: (summary) => _buildSummaryCards(summary),
          ),

          // Filter bar
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
            child: Row(
              children: [
                Expanded(
                  child: propertiesAsync.when(
                    loading: () => const SizedBox.shrink(),
                    error: (_, __) => const SizedBox.shrink(),
                    data: (properties) => DropdownButtonFormField<String?>(
                      value: _selectedPropertyId,
                      isExpanded: true,
                      decoration: const InputDecoration(
                        contentPadding:
                            EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                        hintText: 'All Properties',
                      ),
                      items: [
                        const DropdownMenuItem<String?>(
                          value: null,
                          child: Text('All Properties',
                              style: TextStyle(fontSize: 13)),
                        ),
                        ...properties.map((p) => DropdownMenuItem<String?>(
                              value: p['id'],
                              child: Text(p['name'] ?? '',
                                  style: const TextStyle(fontSize: 13),
                                  overflow: TextOverflow.ellipsis),
                            )),
                      ],
                      onChanged: (v) =>
                          setState(() => _selectedPropertyId = v),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: DropdownButtonFormField<String?>(
                    value: _selectedStatus,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      contentPadding:
                          EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                      hintText: 'All Status',
                    ),
                    items: _statusOptions
                        .map((s) => DropdownMenuItem<String?>(
                              value: s,
                              child: Text(
                                s?.replaceAll('_', ' ') ?? 'All Status',
                                style: TextStyle(
                                  fontSize: 13,
                                  color: s != null
                                      ? StatusHelper.getPaymentStatusColor(s)
                                      : null,
                                ),
                              ),
                            ))
                        .toList(),
                    onChanged: (v) =>
                        setState(() => _selectedStatus = v),
                  ),
                ),
              ],
            ),
          ),

          // Payment list
          Expanded(
            child: paymentsAsync.when(
              loading: () => const ListShimmer(itemCount: 3),
              error: (e, _) => ErrorState(
                message: 'Failed to load payments',
                onRetry: _refresh,
              ),
              data: (payments) {
                var filtered = payments.where((p) {
                  if (_selectedPropertyId != null &&
                      p['propertyId'] != _selectedPropertyId) {
                    return false;
                  }
                  if (_selectedStatus != null &&
                      p['status'] != _selectedStatus) {
                    return false;
                  }
                  return true;
                }).toList();

                if (filtered.isEmpty) {
                  return const EmptyState(
                    icon: Icons.payment_outlined,
                    title: 'No payments found',
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 0, 20, 150),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final payment = filtered[index];
                      return AnimatedListItem(
                        index: index,
                        child: _PaymentCard(
                          payment: payment,
                          onTap: () => _showPaymentActions(payment),
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
    );
  }

  Widget _buildSummaryCards(Map<String, dynamic> summary) {
    final items = [
      _SummaryData('Pending', summary['pendingCount'] ?? 0,
          Formatters.currencyCompact((summary['pendingAmount'] ?? 0).toDouble()),
          AppColors.statusPending),
      _SummaryData('Collected', summary['collectedCount'] ?? 0,
          Formatters.currencyCompact((summary['collectedAmount'] ?? 0).toDouble()),
          AppColors.statusCollected),
      _SummaryData('Deposited', summary['depositedCount'] ?? 0,
          Formatters.currencyCompact((summary['depositedAmount'] ?? 0).toDouble()),
          AppColors.info),
      _SummaryData('Cleared', summary['clearedCount'] ?? 0,
          Formatters.currencyCompact((summary['clearedAmount'] ?? 0).toDouble()),
          AppColors.statusCleared),
      _SummaryData('Bounced', summary['bouncedCount'] ?? 0,
          Formatters.currencyCompact((summary['bouncedAmount'] ?? 0).toDouble()),
          AppColors.statusBounced),
      _SummaryData('Overdue', summary['overdueCount'] ?? 0,
          Formatters.currencyCompact((summary['overdueAmount'] ?? 0).toDouble()),
          AppColors.statusOverdue),
    ];

    return SizedBox(
      height: 90,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 10),
        itemBuilder: (context, index) {
          final item = items[index];
          return Container(
            width: 120,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: item.color.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: item.color.withValues(alpha: 0.2)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    Text('${item.count}',
                        style: GoogleFonts.josefinSans(
                          fontWeight: FontWeight.w700,
                          fontSize: 18,
                          color: item.color,
                        )),
                    const SizedBox(width: 4),
                    Flexible(
                      child: Text(item.label,
                          style: GoogleFonts.josefinSans(
                            fontSize: 11,
                            color: item.color.withValues(alpha: 0.8),
                          ),
                          overflow: TextOverflow.ellipsis),
                    ),
                  ],
                ),
                Text(item.amount,
                    style: GoogleFonts.josefinSans(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: item.color,
                    )),
              ],
            ),
          );
        },
      ),
    );
  }

  void _showPaymentActions(Map<String, dynamic> payment) {
    final status = payment['status'] ?? '';
    final paymentId = payment['id'] ?? '';

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => _PaymentActionSheet(
        payment: payment,
        onCollect: () async {
          Navigator.pop(ctx);
          await _showCollectForm(paymentId);
        },
        onDeposit: () async {
          Navigator.pop(ctx);
          await _performAction(
              () => ref.read(_paymentServiceProvider).depositPayment(paymentId),
              'Payment deposited');
        },
        onClear: () async {
          Navigator.pop(ctx);
          await _performAction(
              () => ref.read(_paymentServiceProvider).clearPayment(paymentId),
              'Payment cleared');
        },
        onBounce: () async {
          Navigator.pop(ctx);
          await _performAction(
              () => ref.read(_paymentServiceProvider).bouncePayment(paymentId),
              'Payment bounced');
        },
        onDownloadReceipt: () async {
          Navigator.pop(ctx);
          await _downloadReceipt(paymentId);
        },
      ),
    );
  }

  Future<void> _showCollectForm(String paymentId) async {
    final chequeNumberCtrl = TextEditingController();
    final bankNameCtrl = TextEditingController();
    final payerNameCtrl = TextEditingController();
    DateTime? chequeDate;
    String? chequeImagePath;
    final formKey = GlobalKey<FormState>();

    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
              24, 24, 24, MediaQuery.of(ctx).viewInsets.bottom + 24),
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
                  Text('Collect Payment',
                      style: Theme.of(ctx).textTheme.headlineSmall),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: chequeNumberCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Cheque Number',
                      prefixIcon: Icon(Icons.numbers_outlined),
                    ),
                    validator: (v) => v == null || v.trim().isEmpty
                        ? 'Required'
                        : null,
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: bankNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Bank Name',
                      prefixIcon: Icon(Icons.account_balance_outlined),
                    ),
                    validator: (v) => v == null || v.trim().isEmpty
                        ? 'Required'
                        : null,
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: payerNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Payer Name',
                      prefixIcon: Icon(Icons.person_outline),
                    ),
                  ),
                  const SizedBox(height: 16),
                  InkWell(
                    onTap: () async {
                      final date = await showDatePicker(
                        context: ctx,
                        initialDate: DateTime.now(),
                        firstDate: DateTime(2020),
                        lastDate: DateTime(2030),
                      );
                      if (date != null) {
                        setSheetState(() => chequeDate = date);
                      }
                    },
                    child: InputDecorator(
                      decoration: const InputDecoration(
                        labelText: 'Cheque Date',
                        prefixIcon: Icon(Icons.calendar_today_outlined),
                      ),
                      child: Text(
                        chequeDate != null
                            ? Formatters.date(chequeDate!.toIso8601String())
                            : 'Select date',
                        style: TextStyle(
                          color: chequeDate != null
                              ? AppColors.textPrimary
                              : AppColors.textMuted,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  OutlinedButton.icon(
                    onPressed: () async {
                      final picker = ImagePicker();
                      final image = await picker.pickImage(
                        source: ImageSource.camera,
                        imageQuality: 80,
                      );
                      if (image != null) {
                        setSheetState(() => chequeImagePath = image.path);
                      }
                    },
                    icon: Icon(
                      chequeImagePath != null
                          ? Icons.check_circle
                          : Icons.camera_alt_outlined,
                      size: 18,
                    ),
                    label: Text(chequeImagePath != null
                        ? 'Cheque photo captured'
                        : 'Scan Cheque'),
                  ),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: () async {
                        if (!formKey.currentState!.validate()) return;
                        Navigator.pop(ctx);
                        await _performAction(
                          () => ref
                              .read(_paymentServiceProvider)
                              .collectPayment(paymentId, {
                            'chequeNumber': chequeNumberCtrl.text.trim(),
                            'bankName': bankNameCtrl.text.trim(),
                            if (payerNameCtrl.text.isNotEmpty)
                              'payerName': payerNameCtrl.text.trim(),
                            if (chequeDate != null)
                              'chequeDate':
                                  chequeDate!.toIso8601String().split('T')[0],
                          }),
                          'Payment collected',
                        );
                      },
                      child: const Text('Collect'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _performAction(
      Future<dynamic> Function() action, String successMsg) async {
    try {
      await action();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(successMsg)),
        );
        _refresh();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Action failed')),
        );
      }
    }
  }

  Future<void> _downloadReceipt(String paymentId) async {
    try {
      final bytes = await ref
          .read(_paymentServiceProvider)
          .downloadReceipt(paymentId);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/receipt_$paymentId.pdf');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to download receipt')),
        );
      }
    }
  }
}

class _SummaryData {
  final String label;
  final int count;
  final String amount;
  final Color color;

  _SummaryData(this.label, this.count, this.amount, this.color);
}

class _PaymentCard extends StatelessWidget {
  final Map<String, dynamic> payment;
  final VoidCallback onTap;

  const _PaymentCard({required this.payment, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final status = payment['status'] ?? 'PENDING';
    final statusColor = StatusHelper.getPaymentStatusColor(status);
    final amount = (payment['amount'] ?? 0).toDouble();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: statusColor.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(Icons.receipt_outlined,
                    color: statusColor, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      payment['renterName'] ?? 'Unknown',
                      style: GoogleFonts.josefinSans(
                        fontWeight: FontWeight.w600,
                        fontSize: 14,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Unit ${payment['unitNumber'] ?? '-'} | #${payment['installmentNumber'] ?? '-'}',
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Due: ${Formatters.date(payment['dueDate'])}',
                      style: GoogleFonts.josefinSans(
                        fontSize: 11,
                        color: AppColors.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    Formatters.currency(amount),
                    style: GoogleFonts.josefinSans(
                      fontWeight: FontWeight.w700,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 4),
                  StatusBadge(label: status, color: statusColor),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PaymentActionSheet extends StatelessWidget {
  final Map<String, dynamic> payment;
  final VoidCallback onCollect;
  final VoidCallback onDeposit;
  final VoidCallback onClear;
  final VoidCallback onBounce;
  final VoidCallback onDownloadReceipt;

  const _PaymentActionSheet({
    required this.payment,
    required this.onCollect,
    required this.onDeposit,
    required this.onClear,
    required this.onBounce,
    required this.onDownloadReceipt,
  });

  @override
  Widget build(BuildContext context) {
    final status = payment['status'] ?? '';
    final amount = (payment['amount'] ?? 0).toDouble();
    final statusColor = StatusHelper.getPaymentStatusColor(status);

    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
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

          // Payment summary
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        payment['renterName'] ?? 'Unknown',
                        style: GoogleFonts.josefinSans(
                          fontWeight: FontWeight.w600,
                          fontSize: 16,
                        ),
                      ),
                    ),
                    StatusBadge(label: status, color: statusColor),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Text(
                      Formatters.currency(amount),
                      style: GoogleFonts.josefinSans(
                        fontWeight: FontWeight.w700,
                        fontSize: 20,
                        color: AppColors.primary,
                      ),
                    ),
                    const Spacer(),
                    Text(
                      'Due: ${Formatters.date(payment['dueDate'])}',
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // Actions
          if (status == 'PENDING' || status == 'OVERDUE')
            _ActionButton(
              icon: Icons.receipt_long_outlined,
              label: 'Collect Payment',
              color: AppColors.primary,
              onTap: onCollect,
            ),
          if (status == 'COLLECTED') ...[
            _ActionButton(
              icon: Icons.account_balance_outlined,
              label: 'Deposit to Bank',
              color: AppColors.info,
              onTap: onDeposit,
            ),
          ],
          if (status == 'DEPOSITED') ...[
            _ActionButton(
              icon: Icons.check_circle_outline,
              label: 'Mark as Cleared',
              color: AppColors.success,
              onTap: onClear,
            ),
            const SizedBox(height: 8),
            _ActionButton(
              icon: Icons.cancel_outlined,
              label: 'Mark as Bounced',
              color: AppColors.danger,
              onTap: onBounce,
            ),
          ],
          if (status == 'BOUNCED')
            _ActionButton(
              icon: Icons.replay_outlined,
              label: 'Replace Cheque',
              color: AppColors.warning,
              onTap: onCollect,
            ),
          if (status == 'CLEARED')
            _ActionButton(
              icon: Icons.download_outlined,
              label: 'Download Receipt',
              color: AppColors.primary,
              onTap: onDownloadReceipt,
            ),
        ],
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  const _ActionButton({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: SizedBox(
        width: double.infinity,
        child: OutlinedButton.icon(
          onPressed: onTap,
          icon: Icon(icon, color: color, size: 20),
          label: Text(label),
          style: OutlinedButton.styleFrom(
            foregroundColor: color,
            side: BorderSide(color: color.withValues(alpha: 0.3)),
            padding: const EdgeInsets.symmetric(vertical: 14),
          ),
        ),
      ),
    );
  }
}
