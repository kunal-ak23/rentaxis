import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:rentaxis_core/api/services/settlement_service.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';
import 'package:rentaxis_core/theme/app_theme.dart';
import 'package:rentaxis_core/utils/formatters.dart';

final _settlementServiceProvider = Provider<SettlementService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettlementService(client.dio);
});

class LeaseSettlementScreen extends ConsumerStatefulWidget {
  final String leaseId;
  final String? leaseStatus;

  const LeaseSettlementScreen({
    super.key,
    required this.leaseId,
    this.leaseStatus,
  });

  @override
  ConsumerState<LeaseSettlementScreen> createState() =>
      _LeaseSettlementScreenState();
}

class _LeaseSettlementScreenState extends ConsumerState<LeaseSettlementScreen> {
  bool _loading = true;
  String? _error;
  Map<String, dynamic>? _settlement;
  List<Map<String, dynamic>> _deductions = [];
  List<Map<String, dynamic>> _additions = [];
  String _notes = '';
  bool _saving = false;
  final Map<String, bool> _uploadingForDeduction = {};
  final Map<int, TextEditingController> _amountControllers = {};
  final Map<int, TextEditingController> _additionAmountControllers = {};

  final _notesController = TextEditingController();

  static const _manualCategories = [
    {'value': 'PROPERTY_DAMAGE', 'label': 'Property Damage'},
    {'value': 'EARLY_TERMINATION_FEE', 'label': 'Early Termination Fee'},
    {'value': 'CLEANING', 'label': 'Cleaning'},
    {'value': 'UTILITY_ARREARS', 'label': 'Utility Arrears'},
    {'value': 'KEY_REPLACEMENT', 'label': 'Key Replacement'},
    {'value': 'OTHER', 'label': 'Other'},
  ];

  static const _additionCategories = [
    {'value': 'PREPAID_RENT', 'label': 'Prepaid Rent'},
    {'value': 'UTILITY_OVERPAYMENT', 'label': 'Utility Overpayment'},
    {'value': 'DEPOSIT_INTEREST', 'label': 'Deposit Interest'},
    {'value': 'LANDLORD_COMPENSATION', 'label': 'Landlord Compensation'},
    {'value': 'OTHER', 'label': 'Other'},
  ];

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  @override
  void dispose() {
    _notesController.dispose();
    for (final ctrl in _amountControllers.values) {
      ctrl.dispose();
    }
    for (final ctrl in _additionAmountControllers.values) {
      ctrl.dispose();
    }
    super.dispose();
  }

  String _categoryLabel(String category) {
    const labels = {
      'UNPAID_RENT': 'Unpaid Rent',
      'PENALTIES': 'Late Penalties',
      'PROPERTY_DAMAGE': 'Property Damage',
      'EARLY_TERMINATION_FEE': 'Early Termination Fee',
      'CLEANING': 'Cleaning',
      'UTILITY_ARREARS': 'Utility Arrears',
      'KEY_REPLACEMENT': 'Key Replacement',
      'OTHER': 'Other',
    };
    return labels[category] ?? category;
  }

  String _additionCategoryLabel(String category) {
    const labels = {
      'PREPAID_RENT': 'Prepaid Rent',
      'UTILITY_OVERPAYMENT': 'Utility Overpayment',
      'DEPOSIT_INTEREST': 'Deposit Interest',
      'LANDLORD_COMPENSATION': 'Landlord Compensation',
      'OTHER': 'Other',
    };
    return labels[category] ?? category;
  }

  double get _totalDeductions => _deductions.fold(
      0.0,
      (sum, d) =>
          sum + (double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0));

  double get _totalAdditions => _additions.fold(
      0.0,
      (sum, d) =>
          sum + (double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0));

  double get _depositAmount =>
      (_settlement?['depositAmount'] as num?)?.toDouble() ?? 0.0;

  double get _refundAmount => _depositAmount - _totalDeductions + _totalAdditions;

  bool get _isFinalized =>
      (_settlement?['status'] as String?)?.toUpperCase() == 'FINALIZED';

  bool get _isDraftOrNew =>
      _settlement == null ||
      (_settlement?['status'] as String?)?.toUpperCase() == 'DRAFT';

  void _loadData() async {
    setState(() => _loading = true);
    try {
      final service = ref.read(_settlementServiceProvider);
      try {
        final settlement = await service.getSettlement(widget.leaseId);
        final rawDeductions =
            (settlement['deductions'] as List? ?? []).map((d) {
          return <String, dynamic>{
            'id': d['id'],
            'category': d['category'],
            'description': d['description'] ?? '',
            'amount': d['amount']?.toString() ?? '0',
            'autoCalculated': d['autoCalculated'] ?? false,
            'type': d['type'] ?? 'DEDUCTION',
            'attachments':
                List<Map<String, dynamic>>.from(d['attachments'] ?? []),
          };
        }).toList();

        final rawAdditions = (settlement['deductions'] as List? ?? [])
            .where((d) => d['type'] == 'ADDITION')
            .map((d) => <String, dynamic>{
                  'id': d['id'],
                  'additionCategory': d['additionCategory'] ?? 'OTHER',
                  'description': d['description'] ?? '',
                  'amount': d['amount']?.toString() ?? '0',
                  'autoCalculated': false,
                  'type': 'ADDITION',
                  'attachments': List<Map<String, dynamic>>.from(d['attachments'] ?? []),
                })
            .toList();

        final filteredDeductions = rawDeductions
            .where((d) => d['type'] != 'ADDITION')
            .toList();

        if (mounted) {
          setState(() {
            _settlement = settlement;
            _deductions = filteredDeductions;
            _additions = rawAdditions;
            _notes = settlement['notes'] ?? '';
            _notesController.text = _notes;
            _loading = false;
          });
        }
      } catch (_) {
        // No settlement yet — load preview
        final preview = await service.getSettlementPreview(widget.leaseId);
        final previewDeductions = <Map<String, dynamic>>[];
        if ((preview['unpaidRentTotal'] as num? ?? 0) > 0) {
          previewDeductions.add({
            'id': null,
            'category': 'UNPAID_RENT',
            'description': 'Outstanding rent',
            'amount': preview['unpaidRentTotal'].toString(),
            'autoCalculated': true,
            'attachments': <Map<String, dynamic>>[],
          });
        }
        if ((preview['penaltyTotal'] as num? ?? 0) > 0) {
          previewDeductions.add({
            'id': null,
            'category': 'PENALTIES',
            'description': 'Late payment penalties',
            'amount': preview['penaltyTotal'].toString(),
            'autoCalculated': true,
            'attachments': <Map<String, dynamic>>[],
          });
        }
        if (mounted) {
          setState(() {
            _deductions = previewDeductions;
            _loading = false;
          });
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _loading = false;
        });
      }
    }
  }

  Future<void> _saveDraft() async {
    setState(() => _saving = true);
    try {
      final service = ref.read(_settlementServiceProvider);
      final data = {
        'notes': _notes,
        'deductions': [
          ..._deductions.map((d) => {
                'id': d['id'],
                'category': d['category'],
                'description': d['description'],
                'amount':
                    double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0,
                'autoCalculated': d['autoCalculated'],
                'type': 'DEDUCTION',
              }),
          ..._additions.map((d) => {
                'id': d['id'],
                'additionCategory': d['additionCategory'],
                'description': d['description'],
                'amount':
                    double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0,
                'autoCalculated': false,
                'type': 'ADDITION',
              }),
        ],
      };
      final result = await service.saveDraft(widget.leaseId, data);
      final savedDeductions = result['deductions'] as List? ?? [];
      if (mounted) {
        setState(() {
          _settlement = result;
          final savedDeductionsList = savedDeductions
              .where((d) => d['type'] != 'ADDITION')
              .toList();
          for (int i = 0;
              i < _deductions.length && i < savedDeductionsList.length;
              i++) {
            _deductions[i]['id'] = savedDeductionsList[i]['id'];
          }
          // Sync amount controllers for auto-deductions with server-returned values
          for (int i = 0; i < _deductions.length; i++) {
            if (_deductions[i]['autoCalculated'] == true &&
                _amountControllers.containsKey(i)) {
              _amountControllers[i]!.text =
                  _deductions[i]['amount']?.toString() ?? '0';
            }
          }
          final savedAdditions = result['deductions'] as List? ?? [];
          final savedAdditionsList = savedAdditions
              .where((d) => d['type'] == 'ADDITION')
              .toList();
          for (int i = 0;
              i < _additions.length && i < savedAdditionsList.length;
              i++) {
            _additions[i]['id'] = savedAdditionsList[i]['id'];
          }
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Draft saved')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to save: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _finalize() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Finalize Settlement'),
        content: const Text(
          'This will terminate the lease and lock the settlement amounts. Attachments can still be added after. Continue?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.danger),
            child: const Text('Finalize & Terminate'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _saving = true);
    try {
      final service = ref.read(_settlementServiceProvider);
      await service.finalizeSettlement(widget.leaseId);
      if (mounted) context.pop();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to finalize: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _uploadAttachment(
      String deductionId, int deductionIndex) async {
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
        imageQuality: 85,
      );
      if (image == null) return;
      filePath = image.path;
      fileName = image.name;
    } else {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: [
          'pdf',
          'mp4',
          'mov',
          'avi',
          'jpg',
          'jpeg',
          'png',
          'heic'
        ],
      );
      if (result == null || result.files.isEmpty) return;
      filePath = result.files.first.path;
      fileName = result.files.first.name;
    }

    if (filePath == null) return;

    setState(() => _uploadingForDeduction[deductionId] = true);
    try {
      final service = ref.read(_settlementServiceProvider);
      // Note: for large files (up to 250MB), the Dio client receiveTimeout (default 15s)
      // may need to be increased. Consider passing Options(receiveTimeout: Duration(minutes: 5))
      // to the upload call if timeouts are observed in production.
      await service.uploadDeductionAttachment(
          deductionId, filePath, fileName);
      final attachments = await service.getDeductionAttachments(deductionId);
      if (mounted) {
        setState(() {
          _deductions[deductionIndex]['attachments'] =
              List<Map<String, dynamic>>.from(attachments);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Upload failed: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _uploadingForDeduction[deductionId] = false);
      }
    }
  }

  Future<void> _deleteAttachment(
      String attachmentId, int deductionIndex) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Attachment'),
        content: const Text('Remove this attachment?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.danger),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      final service = ref.read(_settlementServiceProvider);
      await service.deleteDeductionAttachment(attachmentId);
      if (mounted) {
        setState(() {
          final attachments =
              _deductions[deductionIndex]['attachments'] as List;
          attachments.removeWhere((a) => a['id'] == attachmentId);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to delete: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(
        body: Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
      );
    }

    if (_error != null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Settlement')),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.error_outline,
                    size: 48, color: AppColors.danger),
                const SizedBox(height: 16),
                Text(_error!,
                    textAlign: TextAlign.center,
                    style:
                        const TextStyle(color: AppColors.textSecondary)),
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: _loadData,
                  child: const Text('Retry'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settlement'),
        actions: [
          if (_isDraftOrNew && !_saving)
            TextButton(
              onPressed: _saveDraft,
              child: const Text(
                'Save',
                style: TextStyle(
                  color: AppColors.primary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          if (_saving)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: AppColors.primary,
                ),
              ),
            ),
        ],
      ),
      body: _isFinalized
          ? _buildFinalizedBody()
          : _buildEditableBody(),
    );
  }

  // ─── FINALIZED VIEW ────────────────────────────────────────────────────────

  Widget _buildFinalizedBody() {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildFinalizedBadge(),
          const SizedBox(height: 20),
          _buildDepositCard(),
          const SizedBox(height: 20),
          if (_deductions.isNotEmpty) ...[
            _buildAutoDeductionsSection(readOnly: true),
            const SizedBox(height: 20),
            _buildManualDeductionsSection(readOnly: true),
            const SizedBox(height: 20),
          ],
          _buildNotesField(readOnly: true),
          const SizedBox(height: 20),
          _buildSummaryCard(),
        ],
      ),
    );
  }

  Widget _buildFinalizedBadge() {
    final finalizedAt = _settlement?['finalizedAt'] as String?;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.success.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border:
            Border.all(color: AppColors.success.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          const Icon(Icons.lock_outlined,
              color: AppColors.success, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'FINALIZED',
                  style: TextStyle(
                    color: AppColors.success,
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    letterSpacing: 1,
                  ),
                ),
                if (finalizedAt != null)
                  Text(
                    'on ${Formatters.date(finalizedAt)}',
                    style: const TextStyle(
                        fontSize: 12, color: AppColors.textSecondary),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ─── EDITABLE VIEW ─────────────────────────────────────────────────────────

  Widget _buildEditableBody() {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildDepositCard(),
          const SizedBox(height: 20),
          _buildAutoDeductionsSection(readOnly: false),
          const SizedBox(height: 20),
          _buildManualDeductionsSection(readOnly: false),
          const SizedBox(height: 20),
          _buildNotesField(readOnly: false),
          const SizedBox(height: 20),
          _buildSummaryCard(),
          const SizedBox(height: 24),
          _buildActionButtons(),
        ],
      ),
    );
  }

  Widget _buildActionButtons() {
    return Row(
      children: [
        Expanded(
          child: OutlinedButton(
            onPressed: _saving ? null : _saveDraft,
            child: const Text('Save Draft'),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: ElevatedButton(
            onPressed: _saving ? null : _finalize,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.danger,
            ),
            child: const Text('Finalize & Terminate'),
          ),
        ),
      ],
    );
  }

  // ─── SHARED SECTIONS ───────────────────────────────────────────────────────

  Widget _buildDepositCard() {
    final deposit = _depositAmount;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [AppColors.navyDark, Color(0xFF1A3352)],
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          const Icon(Icons.account_balance_wallet_outlined,
              color: Colors.white60, size: 28),
          const SizedBox(width: 14),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Security Deposit',
                style: TextStyle(color: Colors.white70, fontSize: 13),
              ),
              const SizedBox(height: 4),
              Text(
                Formatters.currency(deposit),
                style: const TextStyle(
                  color: AppColors.accent,
                  fontWeight: FontWeight.w700,
                  fontSize: 22,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildAutoDeductionsSection({required bool readOnly}) {
    final autoDeductions =
        _deductions.where((d) => d['autoCalculated'] == true).toList();
    if (autoDeductions.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildSectionHeader(
            Icons.calculate_outlined, 'Auto-Calculated Deductions'),
        const SizedBox(height: 12),
        ...autoDeductions.asMap().entries.map((entry) {
          final globalIndex = _deductions.indexOf(entry.value);
          return _buildAutoDeductionCard(
              entry.value, globalIndex, readOnly);
        }),
      ],
    );
  }

  Widget _buildAutoDeductionCard(
      Map<String, dynamic> deduction, int index, bool readOnly) {
    final amountCtrl = _amountControllers.putIfAbsent(
      index,
      () => TextEditingController(text: deduction['amount']?.toString() ?? '0'),
    );
    final deductionId = deduction['id'] as String?;
    final attachments =
        (deduction['attachments'] as List? ?? []).cast<Map<String, dynamic>>();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          _categoryLabel(deduction['category'] as String),
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppColors.info.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(20),
                            border: Border.all(
                                color:
                                    AppColors.info.withValues(alpha: 0.3)),
                          ),
                          child: const Text(
                            'Auto',
                            style: TextStyle(
                              fontSize: 10,
                              color: AppColors.info,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                    if ((deduction['description'] as String?)?.isNotEmpty ==
                        true) ...[
                      const SizedBox(height: 4),
                      Text(
                        deduction['description'] as String,
                        style: const TextStyle(
                            fontSize: 12, color: AppColors.textSecondary),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                width: 110,
                child: TextFormField(
                  controller: amountCtrl,
                  enabled: !readOnly,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  textAlign: TextAlign.right,
                  style: const TextStyle(
                      fontWeight: FontWeight.w600, fontSize: 14),
                  decoration: const InputDecoration(
                    contentPadding:
                        EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  ),
                  onChanged: (v) {
                    _deductions[index]['amount'] = v;
                  },
                ),
              ),
            ],
          ),
          // Show attachments if any (especially relevant in finalized view)
          if (attachments.isNotEmpty || deductionId != null) ...[
            const SizedBox(height: 8),
            _buildAttachmentGrid(attachments, index, readOnly),
          ],
        ],
      ),
    );
  }

  Widget _buildManualDeductionsSection({required bool readOnly}) {
    final manualDeductions =
        _deductions.where((d) => d['autoCalculated'] != true).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildSectionHeader(Icons.edit_note_outlined, 'Manual Deductions'),
        const SizedBox(height: 12),
        ...manualDeductions.asMap().entries.map((entry) {
          final globalIndex = _deductions.indexOf(entry.value);
          return _buildManualDeductionCard(
              entry.value, globalIndex, readOnly);
        }),
        if (!readOnly) ...[
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: () {
                setState(() {
                  _deductions.add({
                    'id': null,
                    'category': 'PROPERTY_DAMAGE',
                    'description': '',
                    'amount': '0',
                    'autoCalculated': false,
                    'attachments': <Map<String, dynamic>>[],
                  });
                });
              },
              icon: const Icon(Icons.add, size: 18),
              label: const Text('+ Add Deduction'),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildManualDeductionCard(
      Map<String, dynamic> deduction, int index, bool readOnly) {
    final deductionId = deduction['id'] as String?;
    final attachments =
        deduction['attachments'] as List<Map<String, dynamic>>? ?? [];
    final attachmentCount = attachments.length;
    final isUploading =
        deductionId != null && (_uploadingForDeduction[deductionId] == true);

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Top row: category + remove button
          Row(
            children: [
              Expanded(
                child: readOnly
                    ? Text(
                        _categoryLabel(deduction['category'] as String),
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 14,
                        ),
                      )
                    : DropdownButtonFormField<String>(
                        value: deduction['category'] as String?,
                        decoration: const InputDecoration(
                          labelText: 'Category',
                          contentPadding: EdgeInsets.symmetric(
                              horizontal: 12, vertical: 10),
                        ),
                        items: _manualCategories
                            .map((c) => DropdownMenuItem(
                                  value: c['value']!,
                                  child: Text(c['label']!,
                                      style: const TextStyle(fontSize: 13)),
                                ))
                            .toList(),
                        onChanged: (v) {
                          if (v != null) {
                            setState(() => _deductions[index]['category'] = v);
                          }
                        },
                      ),
              ),
              if (!readOnly) ...[
                const SizedBox(width: 8),
                IconButton(
                  icon: const Icon(Icons.close,
                      size: 18, color: AppColors.textMuted),
                  onPressed: () {
                    setState(() => _deductions.removeAt(index));
                  },
                ),
              ],
            ],
          ),
          const SizedBox(height: 10),

          // Amount field
          TextFormField(
            initialValue: deduction['amount']?.toString() ?? '0',
            enabled: !readOnly,
            keyboardType:
                const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              labelText: 'Amount',
              contentPadding:
                  EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            ),
            onChanged: (v) {
              _deductions[index]['amount'] = v;
            },
          ),
          const SizedBox(height: 10),

          // Description field
          TextFormField(
            initialValue: deduction['description'] as String? ?? '',
            enabled: !readOnly,
            decoration: const InputDecoration(
              labelText: 'Description',
              contentPadding:
                  EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            ),
            onChanged: (v) {
              _deductions[index]['description'] = v;
            },
          ),
          const SizedBox(height: 12),

          // Attachments header row
          Row(
            children: [
              const Icon(Icons.attach_file,
                  size: 16, color: AppColors.textSecondary),
              const SizedBox(width: 6),
              const Text(
                'Attachments',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textSecondary,
                ),
              ),
              const SizedBox(width: 8),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.primary.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  '$attachmentCount/10',
                  style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: AppColors.primary,
                  ),
                ),
              ),
              const Spacer(),
              if (deductionId != null && attachmentCount < 10)
                isUploading
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppColors.primary,
                        ),
                      )
                    : TextButton.icon(
                        onPressed: () =>
                            _uploadAttachment(deductionId, index),
                        icon: const Icon(Icons.add, size: 16),
                        label: const Text('Add',
                            style: TextStyle(fontSize: 12)),
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 10, vertical: 4),
                          minimumSize: Size.zero,
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                      ),
              if (deductionId == null)
                Tooltip(
                  message: 'Save draft first to add attachments',
                  child: TextButton.icon(
                    onPressed: null,
                    icon: const Icon(Icons.add, size: 16),
                    label: const Text('Add', style: TextStyle(fontSize: 12)),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 4),
                      minimumSize: Size.zero,
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ),
                ),
            ],
          ),

          if (attachments.isNotEmpty) ...[
            const SizedBox(height: 8),
            _buildAttachmentGrid(attachments, index, readOnly),
          ],
        ],
      ),
    );
  }

  Widget _buildAttachmentGrid(List<Map<String, dynamic>> attachments,
      int deductionIndex, bool readOnly) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        crossAxisSpacing: 8,
        mainAxisSpacing: 8,
        childAspectRatio: 1.4,
      ),
      itemCount: attachments.length,
      itemBuilder: (context, i) {
        final attachment = attachments[i];
        final name = (attachment['name'] ?? attachment['fileName'] ?? '') as String;
        final isImage = _isImageFile(name);
        final isVideo = _isVideoFile(name);
        final attachmentId = attachment['id'] as String?;

        return GestureDetector(
          onLongPress: (readOnly || attachmentId == null)
              ? null
              : () => _deleteAttachment(attachmentId, deductionIndex),
          child: Container(
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: AppColors.border),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (isImage)
                    _buildImageThumbnail(attachment)
                  else
                    Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            isVideo
                                ? Icons.videocam_outlined
                                : Icons.picture_as_pdf_outlined,
                            size: 28,
                            color: isVideo
                                ? AppColors.primary
                                : AppColors.danger,
                          ),
                          const SizedBox(height: 4),
                          Text(
                            name.length > 14
                                ? '${name.substring(0, 12)}…'
                                : name,
                            style: const TextStyle(
                                fontSize: 10,
                                color: AppColors.textSecondary),
                            textAlign: TextAlign.center,
                          ),
                        ],
                      ),
                    ),
                  // Long-press delete hint for DRAFT only
                  if (!readOnly && attachmentId != null)
                    Positioned(
                      top: 4,
                      right: 4,
                      child: GestureDetector(
                        onTap: () =>
                            _deleteAttachment(attachmentId, deductionIndex),
                        child: Container(
                          padding: const EdgeInsets.all(2),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.5),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.close,
                              size: 12, color: Colors.white),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildImageThumbnail(Map<String, dynamic> attachment) {
    final url = attachment['fileUrl'] as String?;
    if (url != null && url.isNotEmpty) {
      return Image.network(
        url,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => const Center(
          child: Icon(Icons.broken_image_outlined,
              size: 28, color: AppColors.textMuted),
        ),
      );
    }
    return const Center(
      child: Icon(Icons.image_outlined,
          size: 28, color: AppColors.textMuted),
    );
  }

  bool _isImageFile(String name) {
    final ext = name.toLowerCase().split('.').last;
    return ['jpg', 'jpeg', 'png', 'heic', 'webp', 'gif'].contains(ext);
  }

  bool _isVideoFile(String name) {
    final ext = name.toLowerCase().split('.').last;
    return ['mp4', 'mov', 'avi', 'mkv'].contains(ext);
  }

  Widget _buildNotesField({required bool readOnly}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildSectionHeader(Icons.notes_outlined, 'Notes'),
        const SizedBox(height: 12),
        TextFormField(
          controller: _notesController,
          enabled: !readOnly,
          maxLines: 4,
          decoration: InputDecoration(
            hintText: readOnly
                ? ((_notes.isEmpty) ? 'No notes' : null)
                : 'Add settlement notes…',
          ),
          onChanged: (v) => _notes = v,
        ),
      ],
    );
  }

  Widget _buildSummaryCard() {
    final total = _totalDeductions;
    final deposit = _depositAmount;
    final refund = _refundAmount;
    final isRefundPositive = refund >= 0;

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
        children: [
          _buildSummaryRow(
            'Security Deposit',
            Formatters.currency(deposit),
            valueColor: Colors.white,
          ),
          const SizedBox(height: 10),
          _buildSummaryRow(
            'Total Deductions',
            '- ${Formatters.currency(total)}',
            valueColor: total > 0 ? AppColors.warning : Colors.white,
          ),
          const Divider(color: Colors.white24, height: 24),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Refund to Renter',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
              Text(
                Formatters.currency(refund.abs()),
                style: TextStyle(
                  color: isRefundPositive
                      ? AppColors.success
                      : AppColors.danger,
                  fontWeight: FontWeight.w700,
                  fontSize: 20,
                ),
              ),
            ],
          ),
          if (!isRefundPositive) ...[
            const SizedBox(height: 6),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                const Icon(Icons.warning_amber_outlined,
                    size: 14, color: AppColors.warning),
                const SizedBox(width: 4),
                Text(
                  'Renter owes ${Formatters.currency(refund.abs())}',
                  style: const TextStyle(
                      fontSize: 11, color: AppColors.warning),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSummaryRow(String label, String value,
      {Color valueColor = Colors.white}) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(color: Colors.white70, fontSize: 13),
        ),
        Text(
          value,
          style: TextStyle(
            color: valueColor,
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
      ],
    );
  }

  Widget _buildSectionHeader(IconData icon, String title) {
    return Row(
      children: [
        Icon(icon, size: 20, color: AppColors.primary),
        const SizedBox(width: 8),
        Text(
          title,
          style: Theme.of(context).textTheme.headlineSmall,
        ),
      ],
    );
  }
}
