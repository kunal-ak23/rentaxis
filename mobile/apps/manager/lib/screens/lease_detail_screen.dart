import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../widgets/mark_cheque_failed_dialog.dart';

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

class LeaseDetailScreen extends ConsumerStatefulWidget {
  final String leaseId;
  const LeaseDetailScreen({super.key, required this.leaseId});

  @override
  ConsumerState<LeaseDetailScreen> createState() => _LeaseDetailScreenState();
}

class _LeaseDetailScreenState extends ConsumerState<LeaseDetailScreen> {
  Map<String, dynamic>? _lease;
  List<dynamic> _payments = [];
  List<dynamic> _documents = [];
  List<dynamic> _attachments = [];
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  DateTime? _extendDate;
  bool _isExtending = false;
  bool _isGeneratingContract = false;

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
      final leaseService = ref.read(_leaseServiceProvider);
      final paymentService = ref.read(_paymentServiceProvider);
      final results = await Future.wait([
        leaseService.getLeaseById(widget.leaseId),
        paymentService.getPayments(),
        leaseService.getLeaseDocuments(widget.leaseId),
        leaseService.getAttachments(widget.leaseId),
      ]);
      if (!mounted) return;
      final allPayments = results[1] as List<dynamic>;
      setState(() {
        _lease = results[0] as Map<String, dynamic>;
        _payments = allPayments
            .where((p) => p['leaseId'] == widget.leaseId)
            .toList();
        _documents = results[2] as List<dynamic>;
        _attachments = results[3] as List<dynamic>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load lease details';
        _isLoading = false;
      });
    }
  }

  Future<void> _activateLease() async {
    final confirmed = await _confirmAction(
      'Activate Lease',
      'Are you sure you want to activate this lease? This will generate the payment schedule.',
    );
    if (!confirmed) return;

    setState(() => _isActioning = true);
    try {
      await ref.read(_leaseServiceProvider).activateLease(widget.leaseId);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Lease activated successfully')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to activate lease')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<bool> _confirmAction(String title, String message) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Confirm'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  Future<void> _uploadAttachment() async {
    final source = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.camera_alt_outlined),
              title: const Text('Camera'),
              onTap: () => Navigator.pop(ctx, 'camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('Gallery'),
              onTap: () => Navigator.pop(ctx, 'gallery'),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file),
              title: const Text('File'),
              onTap: () => Navigator.pop(ctx, 'file'),
            ),
          ],
        ),
      ),
    );
    if (source == null) return;

    String? filePath;
    String fileName = 'attachment';

    if (source == 'camera' || source == 'gallery') {
      final picker = ImagePicker();
      final image = await picker.pickImage(
        source:
            source == 'camera' ? ImageSource.camera : ImageSource.gallery,
        imageQuality: 80,
      );
      if (image == null) return;
      filePath = image.path;
      fileName = image.name;
    } else {
      final result = await FilePicker.platform.pickFiles();
      if (result == null || result.files.isEmpty) return;
      filePath = result.files.first.path;
      fileName = result.files.first.name;
    }

    if (filePath == null) return;

    try {
      await ref
          .read(_leaseServiceProvider)
          .uploadAttachment(widget.leaseId, filePath, fileName);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Attachment uploaded')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to upload attachment')),
        );
      }
    }
  }

  Future<void> _downloadDocument(Map<String, dynamic> doc) async {
    try {
      final bytes = await ref
          .read(_leaseServiceProvider)
          .downloadDocument(doc['id']);
      final dir = await getTemporaryDirectory();
      final fileName = doc['name'] ?? 'document.pdf';
      final file = File('${dir.path}/$fileName');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to download document')),
        );
      }
    }
  }

  Future<void> _generateContract() async {
    setState(() => _isGeneratingContract = true);
    try {
      // 1) Fetch the preview PDF and open it in the system viewer.
      final bytes =
          await ref.read(_leaseServiceProvider).previewContract(widget.leaseId);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/lease-preview-${widget.leaseId}.pdf');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to generate preview')),
        );
        setState(() => _isGeneratingContract = false);
      }
      return;
    }

    if (!context.mounted) {
      setState(() => _isGeneratingContract = false);
      return;
    }

    // 2) Confirm save after the user has reviewed the preview.
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Save Contract?'),
        content: const Text(
          'Once saved, this contract will be assigned a contract number '
          'and stored on the lease. Continue?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Confirm & Save'),
          ),
        ],
      ),
    );

    if (confirmed != true) {
      if (mounted) setState(() => _isGeneratingContract = false);
      return;
    }

    try {
      await ref.read(_leaseServiceProvider).generateContract(widget.leaseId);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Contract generated')),
        );
        await _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to save contract')),
        );
      }
    } finally {
      if (mounted) setState(() => _isGeneratingContract = false);
    }
  }

  Future<void> _showExtendDialog() async {
    final lease = _lease!;
    final currentEnd = DateTime.tryParse(lease['endDate'] ?? '') ?? DateTime.now();
    DateTime picked = currentEnd.add(const Duration(days: 365));

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('Extend Lease'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Current end date: ${lease['endDate'] ?? '-'}',
                  style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
              const SizedBox(height: 16),
              const Text('New end date:',
                  style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              InkWell(
                onTap: () async {
                  final d = await showDatePicker(
                    context: ctx,
                    initialDate: picked,
                    firstDate: currentEnd.add(const Duration(days: 1)),
                    lastDate: DateTime.now().add(const Duration(days: 3650)),
                  );
                  if (d != null) setDialogState(() => picked = d);
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                  decoration: BoxDecoration(
                    border: Border.all(color: AppColors.border),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.calendar_today_outlined, size: 18, color: AppColors.primary),
                      const SizedBox(width: 8),
                      Text(
                        '${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}',
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: _isExtending
                  ? null
                  : () async {
                      setDialogState(() => _isExtending = true);
                      try {
                        final newEndDate =
                            '${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}';
                        await ref
                            .read(_leaseServiceProvider)
                            .extendLease(widget.leaseId, newEndDate);
                        if (mounted) {
                          Navigator.pop(ctx);
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                                content: Text('Lease extended successfully')),
                          );
                          _loadData();
                        }
                      } catch (e) {
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                                content: Text('Failed to extend lease')),
                          );
                        }
                      } finally {
                        if (mounted) setDialogState(() => _isExtending = false);
                      }
                    },
              child: _isExtending
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : const Text('Extend'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Lease')),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
      );
    }

    if (_error != null || _lease == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Lease')),
        body: ErrorState(message: _error ?? 'Not found', onRetry: _loadData),
      );
    }

    final lease = _lease!;
    final status = lease['status'] ?? 'DRAFT';
    final statusColor = StatusHelper.getLeaseStatusColor(status);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Lease Details'),
        actions: [
          if (status == 'ACTIVE' || status == 'NOTICE_GIVEN')
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'terminate') context.push('/leases/${widget.leaseId}/settlement');
                if (v == 'extend') _showExtendDialog();
              },
              itemBuilder: (_) => [
                if (status == 'ACTIVE')
                  const PopupMenuItem(
                    value: 'extend',
                    child: Row(
                      children: [
                        Icon(Icons.calendar_month_outlined, color: AppColors.primary, size: 18),
                        SizedBox(width: 8),
                        Text('Extend Lease'),
                      ],
                    ),
                  ),
                const PopupMenuItem(
                  value: 'terminate',
                  child: Row(
                    children: [
                      Icon(Icons.account_balance_wallet_outlined, color: AppColors.warning, size: 18),
                      SizedBox(width: 8),
                      Text('Settle & Terminate'),
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
          color: AppColors.primary,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildHeader(lease, status, statusColor),
                const SizedBox(height: 20),
                if (status == 'DRAFT' || status == 'PENDING_SIGNATURE') ...[
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _isGeneratingContract ? null : _generateContract,
                      icon: _isGeneratingContract
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.description_outlined),
                      label: Text(lease['contractNumber'] != null
                          ? 'Re-generate Contract'
                          : 'Generate Contract'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.primary,
                        side: const BorderSide(color: AppColors.primary),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed: _activateLease,
                      icon: const Icon(Icons.check_circle_outline),
                      label: const Text('Activate Lease'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppColors.success,
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                ],
                _buildPaymentSchedule(),
                const SizedBox(height: 20),
                _buildDocuments(),
                const SizedBox(height: 20),
                _buildAttachments(),
                const SizedBox(height: 20),
                _buildPenaltiesLink(),
                const SizedBox(height: 20),
                _buildSettlementLink(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(
      Map<String, dynamic> lease, String status, Color statusColor) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [AppColors.navyDark, Color(0xFF1A3352)],
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  lease['renterName'] ?? 'Unknown Renter',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              StatusBadge(label: status, color: statusColor),
            ],
          ),
          if (lease['contractNumber'] != null) ...[
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: AppColors.primary,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(
                'Contract No. ${lease['contractNumber']}',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
          const SizedBox(height: 12),
          _HeaderInfo(
            icon: Icons.apartment_outlined,
            text:
                '${lease['propertyName'] ?? '-'} - Unit ${lease['unitIdentifier'] ?? lease['unitNumber'] ?? '-'}',
          ),
          const SizedBox(height: 6),
          _HeaderInfo(
            icon: Icons.calendar_today_outlined,
            text:
                '${Formatters.date(lease['startDate'])} - ${Formatters.date(lease['endDate'])}',
          ),
          if (lease['agreementDate'] != null) ...[
            const SizedBox(height: 6),
            _HeaderInfo(
              icon: Icons.assignment_outlined,
              text: 'Agreement: ${Formatters.date(lease['agreementDate'])}',
            ),
          ],
          const Divider(color: Colors.white24, height: 24),
          Row(
            children: [
              _AmountItem(
                label: 'Annual Rent',
                value: Formatters.currency(
                    (lease['annualRent'] ?? lease['totalRent'] ?? 0).toDouble()),
              ),
              const SizedBox(width: 24),
              if (lease['monthlyRent'] != null)
                _AmountItem(
                  label: 'Monthly',
                  value: Formatters.currency(
                      (lease['monthlyRent']).toDouble()),
                ),
              const SizedBox(width: 24),
              _AmountItem(
                label: 'Payments',
                value: '${lease['numberOfPayments'] ?? _payments.length}',
              ),
            ],
          ),
          if ((lease['adminFee'] != null && (lease['adminFee'] as num) > 0) ||
              (lease['parkingRemoteFee'] != null && (lease['parkingRemoteFee'] as num) > 0)) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                if (lease['adminFee'] != null && (lease['adminFee'] as num) > 0) ...[
                  _AmountItem(
                    label: 'Admin Fee',
                    value: Formatters.currency((lease['adminFee'] as num).toDouble()),
                  ),
                  const SizedBox(width: 24),
                ],
                if (lease['parkingRemoteFee'] != null && (lease['parkingRemoteFee'] as num) > 0)
                  _AmountItem(
                    label: 'Parking Remote',
                    value: Formatters.currency((lease['parkingRemoteFee'] as num).toDouble()),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildPaymentSchedule() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.receipt_long_outlined,
                size: 20, color: AppColors.primary),
            const SizedBox(width: 8),
            Text('Payment Schedule',
                style: Theme.of(context).textTheme.headlineSmall),
            const Spacer(),
            Text('${_payments.length} installments',
                style: const TextStyle(
                    fontSize: 12, color: AppColors.textSecondary)),
          ],
        ),
        const SizedBox(height: 12),
        if (_payments.isEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.border),
            ),
            child: const Column(
              children: [
                Icon(Icons.receipt_long_outlined,
                    size: 40, color: AppColors.textMuted),
                SizedBox(height: 8),
                Text('No payments yet',
                    style: TextStyle(color: AppColors.textSecondary)),
                Text('Activate the lease to generate the schedule',
                    style:
                        TextStyle(fontSize: 12, color: AppColors.textMuted)),
              ],
            ),
          )
        else
          ..._payments.asMap().entries.map((entry) {
            final index = entry.key;
            final payment = entry.value;
            final paymentStatus = payment['status'] ?? 'PENDING';
            final paymentColor =
                StatusHelper.getPaymentStatusColor(paymentStatus);
            final isDeposited = paymentStatus == 'DEPOSITED';

            final rowContent = Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: paymentColor.withValues(alpha: 0.1),
                    shape: BoxShape.circle,
                  ),
                  child: Center(
                    child: Text(
                      '${index + 1}',
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 13,
                        color: paymentColor,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        Formatters.currency(
                            (payment['amount'] ?? 0).toDouble()),
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 14,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        'Due: ${Formatters.date(payment['dueDate'])}',
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                        ),
                      ),
                      if (payment['purposeLabel'] != null) ...[
                        const SizedBox(height: 2),
                        Text(
                          payment['purposeLabel'] as String,
                          style: const TextStyle(
                            fontSize: 11,
                            color: AppColors.textMuted,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                StatusBadge(label: paymentStatus, color: paymentColor),
                if (isDeposited) ...[
                  const SizedBox(width: 4),
                  const Icon(Icons.more_vert,
                      size: 18, color: AppColors.textMuted),
                ],
              ],
            );

            return Container(
              margin: const EdgeInsets.only(bottom: 8),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: isDeposited
                      ? AppColors.danger.withValues(alpha: 0.25)
                      : AppColors.border,
                ),
              ),
              child: isDeposited
                  ? InkWell(
                      borderRadius: BorderRadius.circular(10),
                      onTap: () async {
                        final paymentId = payment['id'] as String? ?? '';
                        final amount = (payment['amount'] ?? 0) as num;
                        final result = await showMarkChequeFailedDialog(
                          context,
                          paymentId: paymentId,
                          installmentNumber: index + 1,
                          amount: amount,
                          paymentService:
                              ref.read(_paymentServiceProvider),
                        );
                        if (result != null && mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                                content: Text('Cheque marked as failed')),
                          );
                          _loadData();
                        }
                      },
                      child: Padding(
                        padding: const EdgeInsets.all(14),
                        child: rowContent,
                      ),
                    )
                  : Padding(
                      padding: const EdgeInsets.all(14),
                      child: rowContent,
                    ),
            );
          }),
      ],
    );
  }

  Widget _buildDocuments() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.folder_outlined,
                size: 20, color: AppColors.primary),
            const SizedBox(width: 8),
            Text('Documents',
                style: Theme.of(context).textTheme.headlineSmall),
          ],
        ),
        const SizedBox(height: 12),
        if (_documents.isEmpty)
          const Text('No documents available',
              style: TextStyle(
                  fontSize: 13, color: AppColors.textSecondary))
        else
          ..._documents.map((doc) => ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: AppColors.info.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(Icons.picture_as_pdf_outlined,
                      color: AppColors.info, size: 20),
                ),
                title: Text(doc['name'] ?? 'Document',
                    style: const TextStyle(fontSize: 14)),
                subtitle: Text(Formatters.date(doc['createdAt']),
                    style: const TextStyle(fontSize: 12)),
                trailing: IconButton(
                  icon: const Icon(Icons.download_outlined,
                      color: AppColors.primary),
                  onPressed: () => _downloadDocument(doc),
                ),
              )),
      ],
    );
  }

  Widget _buildAttachments() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.attach_file, size: 20, color: AppColors.primary),
            const SizedBox(width: 8),
            Text('Attachments',
                style: Theme.of(context).textTheme.headlineSmall),
            const Spacer(),
            TextButton.icon(
              onPressed: _uploadAttachment,
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Add'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_attachments.isEmpty)
          const Text('No attachments',
              style: TextStyle(
                  fontSize: 13, color: AppColors.textSecondary))
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _attachments.map((att) {
              return Chip(
                avatar: const Icon(Icons.insert_drive_file_outlined,
                    size: 16),
                label: Text(att['name'] ?? 'File',
                    style: const TextStyle(fontSize: 12)),
                deleteIcon: const Icon(Icons.close, size: 16),
                onDeleted: () async {
                  try {
                    await ref
                        .read(_leaseServiceProvider)
                        .deleteAttachment(att['id']);
                    _loadData();
                  } catch (_) {}
                },
              );
            }).toList(),
          ),
      ],
    );
  }

  Widget _buildPenaltiesLink() {
    return InkWell(
      onTap: () => context.push('/leases/${widget.leaseId}/penalties'),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.danger.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(Icons.gavel, color: AppColors.danger, size: 22),
            ),
            const SizedBox(width: 14),
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Penalties', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                  SizedBox(height: 2),
                  Text('View and manage late payment penalties', style: TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textMuted),
          ],
        ),
      ),
    );
  }

  Widget _buildSettlementLink() {
    final status = _lease?['status'] ?? '';
    // Show settlement link for ACTIVE, NOTICE_GIVEN, TERMINATED, CLOSED
    final showSettlementLink = ['ACTIVE', 'NOTICE_GIVEN', 'TERMINATED', 'CLOSED']
        .contains(status);
    if (!showSettlementLink) {
      return const SizedBox.shrink();
    }
    final settlementSubtitle = status == 'ACTIVE'
        ? 'Manage settlement draft'
        : 'View settlement preview or details';
    return InkWell(
      onTap: () => context.push('/leases/${widget.leaseId}/settlement'),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.accent.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(Icons.handshake_outlined, color: AppColors.accent, size: 22),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Settlement', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                  const SizedBox(height: 2),
                  Text(settlementSubtitle, style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textMuted),
          ],
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
      children: [
        Icon(icon, size: 16, color: Colors.white60),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(color: Colors.white70, fontSize: 13),
          ),
        ),
      ],
    );
  }
}

class _AmountItem extends StatelessWidget {
  final String label;
  final String value;

  const _AmountItem({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label,
            style: const TextStyle(fontSize: 11, color: Colors.white54)),
        const SizedBox(height: 2),
        Text(
          value,
          style: const TextStyle(
            color: AppColors.accent,
            fontWeight: FontWeight.w700,
            fontSize: 14,
          ),
        ),
      ],
    );
  }
}
