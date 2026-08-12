import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:rentaxis_core/api/services/settlement_service.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';
import 'package:rentaxis_core/theme/app_theme.dart';
import 'package:rentaxis_core/utils/formatters.dart';
import 'package:rentaxis_core/utils/l10n.dart';
import 'package:rentaxis_core/widgets/gold_button.dart';

/// Text style helper — Arabic uses Noto Naskh instead of Cinzel/Josefin
/// Sans, and never carries the EN tracked-uppercase letterSpacing (breaks
/// glyph joining). See arabic-brief.
TextStyle _display(
  bool ar, {
  double size = 16,
  FontWeight weight = FontWeight.w600,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.plusJakartaSans(fontSize: size, fontWeight: weight, color: color);

TextStyle _body(
  bool ar, {
  double size = 13,
  FontWeight weight = FontWeight.w400,
  Color? color,
  double letterSpacing = 0,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.plusJakartaSans(
        fontSize: size,
        fontWeight: weight,
        color: color,
        letterSpacing: letterSpacing,
      );

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
  double _previewDepositAmount = 0;
  List<Map<String, dynamic>> _deductions = [];
  List<Map<String, dynamic>> _additions = [];
  String _notes = '';
  bool _saving = false;
  final Map<String, bool> _uploadingForDeduction = {};
  final Map<int, TextEditingController> _amountControllers = {};
  final Map<int, TextEditingController> _additionAmountControllers = {};

  final _notesController = TextEditingController();

  _L get _l => _L(context.isAr);

  static const _manualCategories = [
    'PROPERTY_DAMAGE',
    'EARLY_TERMINATION_FEE',
    'CLEANING',
    'UTILITY_ARREARS',
    'KEY_REPLACEMENT',
    'OTHER',
  ];

  static const _additionCategories = [
    'PREPAID_RENT',
    'UTILITY_OVERPAYMENT',
    'DEPOSIT_INTEREST',
    'LANDLORD_COMPENSATION',
    'OTHER',
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

  String _categoryLabel(String category) => _l.deductionCategoryLabel(category);

  String _additionCategoryLabel(String category) =>
      _l.additionCategoryLabel(category);

  double get _totalDeductions => _deductions.fold(
    0.0,
    (sum, d) => sum + (double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0),
  );

  double get _totalAdditions => _additions.fold(
    0.0,
    (sum, d) => sum + (double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0),
  );

  double get _depositAmount =>
      (_settlement?['depositAmount'] as num?)?.toDouble() ??
      _previewDepositAmount;

  double get _refundAmount =>
      _depositAmount - _totalDeductions + _totalAdditions;

  bool get _isFinalized =>
      (_settlement?['status'] as String?)?.toUpperCase() == 'FINALIZED';

  bool get _isDraftOrNew =>
      _settlement == null ||
      (_settlement?['status'] as String?)?.toUpperCase() == 'DRAFT';

  void _loadData() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final service = ref.read(_settlementServiceProvider);
      try {
        final settlement = await service.getSettlement(widget.leaseId);
        final rawDeductions = (settlement['deductions'] as List? ?? []).map((
          d,
        ) {
          return <String, dynamic>{
            'id': d['id'],
            'category': d['category'],
            'description': d['description'] ?? '',
            'amount': d['amount']?.toString() ?? '0',
            'autoCalculated': d['autoCalculated'] ?? false,
            'type': d['type'] ?? 'DEDUCTION',
            'attachments': List<Map<String, dynamic>>.from(
              d['attachments'] ?? [],
            ),
          };
        }).toList();

        final rawAdditions = (settlement['deductions'] as List? ?? [])
            .where((d) => d['type'] == 'ADDITION')
            .map(
              (d) => <String, dynamic>{
                'id': d['id'],
                'additionCategory': d['additionCategory'] ?? 'OTHER',
                'description': d['description'] ?? '',
                'amount': d['amount']?.toString() ?? '0',
                'autoCalculated': false,
                'type': 'ADDITION',
                'attachments': List<Map<String, dynamic>>.from(
                  d['attachments'] ?? [],
                ),
              },
            )
            .toList();

        final filteredDeductions = rawDeductions
            .where((d) => d['type'] != 'ADDITION')
            .toList();

        if (mounted) {
          setState(() {
            _settlement = settlement;
            _previewDepositAmount = 0;
            _deductions = filteredDeductions;
            _additions = rawAdditions;
            _notes = settlement['notes'] ?? '';
            _notesController.text = _notes;
            _loading = false;
          });
        }
      } on DioException catch (e) {
        // Only a 404 means "no settlement yet". Anything else (500, 403,
        // network) must surface as an error — silently swapping in the blank
        // preview editor over an existing draft loses notes and deduction
        // ids, so a later Save Draft would duplicate the draft's rows.
        if (e.response?.statusCode != 404) rethrow;
        // No settlement yet — load preview
        final preview = await service.getSettlementPreview(widget.leaseId);
        final previewDeductions = <Map<String, dynamic>>[];
        if ((preview['unpaidRentTotal'] as num? ?? 0) > 0) {
          previewDeductions.add({
            'id': null,
            'category': 'UNPAID_RENT',
            'description': _l.outstandingRent,
            'amount': preview['unpaidRentTotal'].toString(),
            'autoCalculated': true,
            'attachments': <Map<String, dynamic>>[],
          });
        }
        if ((preview['penaltyTotal'] as num? ?? 0) > 0) {
          previewDeductions.add({
            'id': null,
            'category': 'PENALTIES',
            'description': _l.latePaymentPenalties,
            'amount': preview['penaltyTotal'].toString(),
            'autoCalculated': true,
            'attachments': <Map<String, dynamic>>[],
          });
        }
        if (mounted) {
          setState(() {
            _previewDepositAmount =
                (preview['depositAmount'] as num?)?.toDouble() ?? 0;
            _deductions = previewDeductions;
            _loading = false;
          });
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = _l.failedToLoadSettlement;
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
          ..._deductions.map(
            (d) => {
              'id': d['id'],
              'category': d['category'],
              'description': d['description'],
              'amount': double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0,
              'autoCalculated': d['autoCalculated'],
              'type': 'DEDUCTION',
            },
          ),
          ..._additions.map(
            (d) => {
              'id': d['id'],
              'additionCategory': d['additionCategory'],
              'description': d['description'],
              'amount': double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0,
              'autoCalculated': false,
              'type': 'ADDITION',
            },
          ),
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
          for (
            int i = 0;
            i < _deductions.length && i < savedDeductionsList.length;
            i++
          ) {
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
          for (
            int i = 0;
            i < _additions.length && i < savedAdditionsList.length;
            i++
          ) {
            _additions[i]['id'] = savedAdditionsList[i]['id'];
          }
        });
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_l.draftSaved)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_l.saveFailed(e.toString()))));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _finalize() async {
    final l = _l;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.finalizeTitle, style: _display(l.ar, size: 17)),
        content: Text(
          l.finalizeBody,
          style: _body(l.ar, color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
            child: Text(
              l.finalizeAndTerminate,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
            ),
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
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.finalizeFailed(e.toString()))));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _uploadAttachment(String deductionId, int deductionIndex) async {
    final l = _l;
    final source = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.camera_alt_outlined),
              title: Text(l.camera),
              onTap: () => Navigator.pop(ctx, 'camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: Text(l.gallery),
              onTap: () => Navigator.pop(ctx, 'gallery'),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file),
              title: Text(l.file),
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
        source: source == 'camera' ? ImageSource.camera : ImageSource.gallery,
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
          'heic',
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
      await service.uploadDeductionAttachment(deductionId, filePath, fileName);
      final attachments = await service.getDeductionAttachments(deductionId);
      if (mounted) {
        setState(() {
          _deductions[deductionIndex]['attachments'] =
              List<Map<String, dynamic>>.from(attachments);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.uploadFailed(e.toString()))));
      }
    } finally {
      if (mounted) {
        setState(() => _uploadingForDeduction[deductionId] = false);
      }
    }
  }

  Future<void> _deleteAttachment(
    String attachmentId,
    int deductionIndex,
  ) async {
    final l = _l;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.deleteAttachmentTitle, style: _display(l.ar, size: 17)),
        content: Text(
          l.deleteAttachmentBody,
          style: _body(l.ar, color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
            child: Text(
              l.delete,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
            ),
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
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.deleteFailed(e.toString()))));
      }
    }
  }

  Future<void> _uploadAttachmentForAddition(
    String additionId,
    int additionIndex,
  ) async {
    final l = _l;
    final source = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.camera_alt_outlined),
              title: Text(l.camera),
              onTap: () => Navigator.pop(ctx, 'camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: Text(l.gallery),
              onTap: () => Navigator.pop(ctx, 'gallery'),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file),
              title: Text(l.file),
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
        source: source == 'camera' ? ImageSource.camera : ImageSource.gallery,
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
          'heic',
        ],
      );
      if (result == null || result.files.isEmpty) return;
      filePath = result.files.first.path;
      fileName = result.files.first.name;
    }

    if (filePath == null) return;

    setState(() => _uploadingForDeduction[additionId] = true);
    try {
      final service = ref.read(_settlementServiceProvider);
      await service.uploadDeductionAttachment(additionId, filePath, fileName);
      final attachments = await service.getDeductionAttachments(additionId);
      if (mounted) {
        setState(() {
          _additions[additionIndex]['attachments'] =
              List<Map<String, dynamic>>.from(attachments);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.uploadFailed(e.toString()))));
      }
    } finally {
      if (mounted) {
        setState(() => _uploadingForDeduction[additionId] = false);
      }
    }
  }

  Future<void> _deleteAttachmentForAddition(
    String attachmentId,
    int additionIndex,
  ) async {
    final l = _l;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.deleteAttachmentTitle, style: _display(l.ar, size: 17)),
        content: Text(
          l.deleteAttachmentBody,
          style: _body(l.ar, color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
            child: Text(
              l.delete,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
            ),
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
          final attachments = _additions[additionIndex]['attachments'] as List;
          attachments.removeWhere((a) => a['id'] == attachmentId);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.deleteFailed(e.toString()))));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _l;

    if (_loading) {
      return Scaffold(
        backgroundColor: m.background,
        appBar: AppBar(
          backgroundColor: AppColors.primary,
          title: Text(
            l.title,
            style: _display(l.ar, size: 17, color: AppColors.gold400),
          ),
        ),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.accent),
        ),
      );
    }

    if (_error != null) {
      return Scaffold(
        backgroundColor: m.background,
        appBar: AppBar(
          backgroundColor: AppColors.primary,
          title: Text(
            l.title,
            style: _display(l.ar, size: 17, color: AppColors.gold400),
          ),
        ),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.error_outline, size: 48, color: m.danger),
                const SizedBox(height: 16),
                Text(
                  _error!,
                  textAlign: TextAlign.center,
                  style: _body(l.ar, color: m.textSecondary),
                ),
                const SizedBox(height: 16),
                GoldButton(
                  label: l.retry,
                  onPressed: _loadData,
                  expanded: false,
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: AppColors.primary,
        iconTheme: const IconThemeData(color: Colors.white70),
        title: Text(
          l.title,
          style: _display(l.ar, size: 17, color: AppColors.gold400),
        ),
        actions: [
          if (_isDraftOrNew && !_saving)
            TextButton(
              onPressed: _saveDraft,
              child: Text(
                l.save,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontWeight: FontWeight.w600,
                        color: AppColors.accent,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontWeight: FontWeight.w600,
                        letterSpacing: 1.4,
                        color: AppColors.accent,
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
                  color: AppColors.accent,
                ),
              ),
            ),
        ],
      ),
      body: _isFinalized ? _buildFinalizedBody(m, l) : _buildEditableBody(m, l),
    );
  }

  // ─── FINALIZED VIEW ────────────────────────────────────────────────────────

  Widget _buildFinalizedBody(LegacyMiftahColors m, _L l) {
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildFinalizedBadge(m, l),
          const SizedBox(height: 20),
          _buildDepositCard(m, l),
          const SizedBox(height: 20),
          if (_deductions.isNotEmpty) ...[
            _buildAutoDeductionsSection(m, l, readOnly: true),
            const SizedBox(height: 20),
            _buildManualDeductionsSection(m, l, readOnly: true),
            const SizedBox(height: 20),
          ],
          if (_additions.isNotEmpty) ...[
            _SectionHeader(
              icon: Icons.add_circle_outline,
              title: l.additionsRepayments,
              m: m,
              l: l,
            ),
            const SizedBox(height: 12),
            ...List.generate(_additions.length, (i) {
              final addition = _additions[i];
              final additionId = addition['id'] as String?;
              final attachments = List<Map<String, dynamic>>.from(
                addition['attachments'] ?? [],
              );
              return Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: m.success.withValues(alpha: 0.06),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: m.success.withValues(alpha: 0.3)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            _additionCategoryLabel(
                              addition['additionCategory'] as String? ??
                                  'OTHER',
                            ),
                            style: _body(
                              l.ar,
                              size: 14,
                              weight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                          ),
                        ),
                        Text(
                          '+ ${Formatters.currency(double.tryParse(addition['amount']?.toString() ?? '0') ?? 0)}',
                          style: GoogleFonts.plusJakartaSans(
                            color: m.success,
                            fontWeight: FontWeight.w700,
                            fontSize: 14,
                          ),
                        ),
                      ],
                    ),
                    if ((addition['description'] as String?)?.isNotEmpty ==
                        true) ...[
                      const SizedBox(height: 6),
                      Text(
                        addition['description'] as String,
                        style: _body(l.ar, size: 12, color: m.textSecondary),
                      ),
                    ],
                    if (attachments.isNotEmpty && additionId != null) ...[
                      const SizedBox(height: 8),
                      _buildAttachmentGrid(m, attachments, i, true),
                    ],
                  ],
                ),
              );
            }),
            const SizedBox(height: 20),
          ],
          _buildNotesField(m, l, readOnly: true),
          const SizedBox(height: 20),
          _buildSummaryCard(m, l),
        ],
      ),
    );
  }

  Widget _buildFinalizedBadge(LegacyMiftahColors m, _L l) {
    final finalizedAt = _settlement?['finalizedAt'] as String?;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: m.success.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.success.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          Icon(Icons.lock_outlined, color: m.success, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.ar ? l.finalized : l.finalized.toUpperCase(),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          color: m.success,
                          fontWeight: FontWeight.w700,
                          fontSize: 13.5,
                        )
                      : GoogleFonts.plusJakartaSans(
                          color: m.success,
                          fontWeight: FontWeight.w700,
                          fontSize: 12,
                          letterSpacing: 1.2,
                        ),
                ),
                if (finalizedAt != null)
                  Text(
                    l.onDate(Formatters.date(finalizedAt, ar: l.ar)),
                    style: _body(l.ar, size: 12, color: m.textSecondary),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ─── EDITABLE VIEW ─────────────────────────────────────────────────────────

  Widget _buildEditableBody(LegacyMiftahColors m, _L l) {
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildDepositCard(m, l),
          const SizedBox(height: 20),
          _buildAutoDeductionsSection(m, l, readOnly: false),
          const SizedBox(height: 20),
          _buildManualDeductionsSection(m, l, readOnly: false),
          const SizedBox(height: 20),
          // Additions section
          _SectionHeader(
            icon: Icons.add_circle_outline,
            title: l.additionsRepayments,
            m: m,
            l: l,
          ),
          const SizedBox(height: 8),
          if (_additions.isEmpty)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: m.success.withValues(alpha: 0.04),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: m.success.withValues(alpha: 0.2),
                  style: BorderStyle.solid,
                ),
              ),
              child: Text(
                l.noAdditionsHint,
                textAlign: TextAlign.center,
                style: _body(l.ar, size: 12, color: m.textMuted),
              ),
            )
          else
            ...List.generate(
              _additions.length,
              (i) => _buildAdditionCard(m, l, i, _additions[i]),
            ),
          const SizedBox(height: 4),
          if (_isDraftOrNew)
            Align(
              alignment: AlignmentDirectional.centerEnd,
              child: TextButton.icon(
                onPressed: () => setState(
                  () => _additions.add({
                    'id': null,
                    'additionCategory': 'PREPAID_RENT',
                    'description': '',
                    'amount': '0',
                    'autoCalculated': false,
                    'type': 'ADDITION',
                    'attachments': <Map<String, dynamic>>[],
                  }),
                ),
                icon: Icon(Icons.add, size: 16, color: m.success),
                label: Text(
                  l.addRepayment,
                  style: _body(l.ar, size: 13, color: m.success),
                ),
              ),
            ),
          const SizedBox(height: 16),
          _buildNotesField(m, l, readOnly: false),
          const SizedBox(height: 20),
          _buildSummaryCard(m, l),
          const SizedBox(height: 24),
          _buildActionButtons(m, l),
        ],
      ),
    );
  }

  Widget _buildActionButtons(LegacyMiftahColors m, _L l) {
    return Row(
      children: [
        Expanded(
          child: GoldButton.outlined(
            label: l.saveDraft,
            onPressed: _saving ? null : _saveDraft,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: SizedBox(
            height: 48,
            child: ElevatedButton(
              onPressed: _saving ? null : _finalize,
              style: ElevatedButton.styleFrom(
                backgroundColor: m.danger,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: Text(
                l.ar
                    ? l.finalizeAndTerminate
                    : l.finalizeAndTerminate.toUpperCase(),
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 11,
                        letterSpacing: 1.2,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  // ─── SHARED SECTIONS ───────────────────────────────────────────────────────

  Widget _buildDepositCard(LegacyMiftahColors m, _L l) {
    final deposit = _depositAmount;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.primary,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.16)),
      ),
      child: Row(
        children: [
          Icon(
            Icons.account_balance_wallet_outlined,
            color: Colors.white.withValues(alpha: 0.5),
            size: 26,
          ),
          const SizedBox(width: 14),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                l.securityDeposit,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        color: Colors.white70,
                        fontSize: 13,
                      )
                    : GoogleFonts.plusJakartaSans(
                        color: Colors.white70,
                        fontSize: 12.5,
                      ),
              ),
              const SizedBox(height: 4),
              Text(
                Formatters.currency(deposit),
                key: const Key('settlement-deposit-amount'),
                style: GoogleFonts.plusJakartaSans(
                  color: AppColors.gold400,
                  fontWeight: FontWeight.w700,
                  fontSize: 21,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildAutoDeductionsSection(
    LegacyMiftahColors m,
    _L l, {
    required bool readOnly,
  }) {
    final autoDeductions = _deductions
        .where((d) => d['autoCalculated'] == true)
        .toList();
    if (autoDeductions.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionHeader(
          icon: Icons.calculate_outlined,
          title: l.autoCalculatedDeductions,
          m: m,
          l: l,
        ),
        const SizedBox(height: 12),
        ...autoDeductions.asMap().entries.map((entry) {
          final globalIndex = _deductions.indexOf(entry.value);
          return _buildAutoDeductionCard(
            m,
            l,
            entry.value,
            globalIndex,
            readOnly,
          );
        }),
      ],
    );
  }

  Widget _buildAutoDeductionCard(
    LegacyMiftahColors m,
    _L l,
    Map<String, dynamic> deduction,
    int index,
    bool readOnly,
  ) {
    final amountCtrl = _amountControllers.putIfAbsent(
      index,
      () => TextEditingController(text: deduction['amount']?.toString() ?? '0'),
    );
    final deductionId = deduction['id'] as String?;
    final attachments = (deduction['attachments'] as List? ?? [])
        .cast<Map<String, dynamic>>();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
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
                          style: _body(
                            l.ar,
                            size: 14,
                            weight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: AppColors.info.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(20),
                            border: Border.all(
                              color: AppColors.info.withValues(alpha: 0.3),
                            ),
                          ),
                          child: Text(
                            l.ar ? l.auto : l.auto.toUpperCase(),
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 10,
                                    color: AppColors.info,
                                    fontWeight: FontWeight.w600,
                                  )
                                : GoogleFonts.plusJakartaSans(
                                    fontSize: 9.5,
                                    letterSpacing: 1,
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
                        style: _body(l.ar, size: 12, color: m.textSecondary),
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
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  textAlign: TextAlign.end,
                  style: _body(
                    l.ar,
                    size: 14,
                    weight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                  decoration: const InputDecoration(
                    contentPadding: EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
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
            _buildAttachmentGrid(m, attachments, index, readOnly),
          ],
        ],
      ),
    );
  }

  Widget _buildManualDeductionsSection(
    LegacyMiftahColors m,
    _L l, {
    required bool readOnly,
  }) {
    final manualDeductions = _deductions
        .where((d) => d['autoCalculated'] != true)
        .toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionHeader(
          icon: Icons.edit_note_outlined,
          title: l.manualDeductions,
          m: m,
          l: l,
        ),
        const SizedBox(height: 12),
        ...manualDeductions.asMap().entries.map((entry) {
          final globalIndex = _deductions.indexOf(entry.value);
          return _buildManualDeductionCard(
            m,
            l,
            entry.value,
            globalIndex,
            readOnly,
          );
        }),
        if (!readOnly) ...[
          const SizedBox(height: 8),
          GoldButton.outlined(
            label: l.addDeduction,
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
          ),
        ],
      ],
    );
  }

  Widget _buildManualDeductionCard(
    LegacyMiftahColors m,
    _L l,
    Map<String, dynamic> deduction,
    int index,
    bool readOnly,
  ) {
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
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
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
                        style: _body(
                          l.ar,
                          size: 14,
                          weight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                      )
                    : DropdownButtonFormField<String>(
                        initialValue: deduction['category'] as String?,
                        decoration: InputDecoration(
                          labelText: l.category,
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 10,
                          ),
                        ),
                        items: _manualCategories
                            .map(
                              (c) => DropdownMenuItem(
                                value: c,
                                child: Text(
                                  _categoryLabel(c),
                                  style: _body(l.ar, size: 13),
                                ),
                              ),
                            )
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
                  icon: Icon(Icons.close, size: 18, color: m.textMuted),
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
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              labelText: l.amount,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 10,
              ),
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
            decoration: InputDecoration(
              labelText: l.description,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 10,
              ),
            ),
            onChanged: (v) {
              _deductions[index]['description'] = v;
            },
          ),
          const SizedBox(height: 12),

          // Attachments header row
          Row(
            children: [
              Icon(Icons.attach_file, size: 16, color: m.textSecondary),
              const SizedBox(width: 6),
              Text(
                l.attachments,
                style: _body(
                  l.ar,
                  size: 13,
                  weight: FontWeight.w600,
                  color: m.textSecondary,
                ),
              ),
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.accentDark.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  '$attachmentCount/10',
                  style: _body(
                    l.ar,
                    size: 11,
                    weight: FontWeight.w600,
                    color: AppColors.accentDark,
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
                          color: AppColors.accentDark,
                        ),
                      )
                    : TextButton.icon(
                        onPressed: () => _uploadAttachment(deductionId, index),
                        icon: const Icon(Icons.add, size: 16),
                        label: Text(l.add, style: _body(l.ar, size: 12)),
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 4,
                          ),
                          minimumSize: Size.zero,
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                      ),
              if (deductionId == null)
                Tooltip(
                  message: l.saveDraftFirst,
                  child: TextButton.icon(
                    onPressed: null,
                    icon: const Icon(Icons.add, size: 16),
                    label: Text(l.add, style: _body(l.ar, size: 12)),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 4,
                      ),
                      minimumSize: Size.zero,
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ),
                ),
            ],
          ),

          if (attachments.isNotEmpty) ...[
            const SizedBox(height: 8),
            _buildAttachmentGrid(m, attachments, index, readOnly),
          ],
        ],
      ),
    );
  }

  Widget _buildAttachmentGrid(
    LegacyMiftahColors m,
    List<Map<String, dynamic>> attachments,
    int deductionIndex,
    bool readOnly,
  ) {
    return GridView.builder(
      // Nested in a scroll view: without this the sliver auto-pads
      // with MediaQuery.padding, which under extendBody carries the
      // floating nav height and opens a gap below the content.
      padding: EdgeInsets.zero,
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
        final name =
            (attachment['name'] ?? attachment['fileName'] ?? '') as String;
        final isImage = _isImageFile(name);
        final isVideo = _isVideoFile(name);
        final attachmentId = attachment['id'] as String?;

        return GestureDetector(
          onLongPress: (readOnly || attachmentId == null)
              ? null
              : () => _deleteAttachment(attachmentId, deductionIndex),
          child: Container(
            decoration: BoxDecoration(
              color: m.background,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: m.border),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (isImage)
                    _buildImageThumbnail(m, attachment)
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
                            color: isVideo ? AppColors.accentDark : m.danger,
                          ),
                          const SizedBox(height: 4),
                          Text(
                            name.length > 14
                                ? '${name.substring(0, 12)}…'
                                : name,
                            style: TextStyle(
                              fontSize: 10,
                              color: m.textSecondary,
                            ),
                            textAlign: TextAlign.center,
                          ),
                        ],
                      ),
                    ),
                  // Long-press delete hint for DRAFT only
                  if (!readOnly && attachmentId != null)
                    PositionedDirectional(
                      top: 4,
                      end: 4,
                      child: GestureDetector(
                        onTap: () =>
                            _deleteAttachment(attachmentId, deductionIndex),
                        child: Container(
                          padding: const EdgeInsets.all(2),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.5),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.close,
                            size: 12,
                            color: Colors.white,
                          ),
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

  Widget _buildImageThumbnail(LegacyMiftahColors m, Map<String, dynamic> attachment) {
    final url = attachment['fileUrl'] as String?;
    if (url != null && url.isNotEmpty) {
      return Image.network(
        url,
        fit: BoxFit.cover,
        errorBuilder: (_, _, _) => Center(
          child: Icon(
            Icons.broken_image_outlined,
            size: 28,
            color: m.textMuted,
          ),
        ),
      );
    }
    return Center(
      child: Icon(Icons.image_outlined, size: 28, color: m.textMuted),
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

  Widget _buildNotesField(LegacyMiftahColors m, _L l, {required bool readOnly}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionHeader(icon: Icons.notes_outlined, title: l.notes, m: m, l: l),
        const SizedBox(height: 12),
        TextFormField(
          controller: _notesController,
          enabled: !readOnly,
          maxLines: 4,
          textAlign: TextAlign.start,
          style: _body(l.ar),
          decoration: InputDecoration(
            hintText: readOnly
                ? ((_notes.isEmpty) ? l.noNotes : null)
                : l.addNotesHint,
          ),
          onChanged: (v) => _notes = v,
        ),
      ],
    );
  }

  Widget _buildSummaryCard(LegacyMiftahColors m, _L l) {
    final total = _totalDeductions;
    final deposit = _depositAmount;
    final refund = _refundAmount;
    final isRefundPositive = refund >= 0;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.primary,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.16)),
      ),
      child: Column(
        children: [
          _buildSummaryRow(
            l,
            l.securityDeposit,
            Formatters.currency(deposit),
            valueColor: Colors.white,
          ),
          const SizedBox(height: 10),
          _buildSummaryRow(
            l,
            l.totalDeductions,
            '- ${Formatters.currency(total)}',
            valueColor: total > 0 ? AppColors.warning : Colors.white,
          ),
          if (_totalAdditions > 0) ...[
            const SizedBox(height: 12),
            _buildSummaryRow(
              l,
              l.totalAdditions,
              '+ ${Formatters.currency(_totalAdditions)}',
              valueColor: AppColors.success,
            ),
          ],
          const Divider(color: Colors.white24, height: 24),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                l.refundToRenter,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                        fontSize: 14.5,
                      )
                    : GoogleFonts.plusJakartaSans(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
              ),
              Text(
                Formatters.currency(refund.abs()),
                style: GoogleFonts.plusJakartaSans(
                  color: isRefundPositive
                      ? AppColors.success
                      : AppColors.danger,
                  fontWeight: FontWeight.w700,
                  fontSize: 19,
                ),
              ),
            ],
          ),
          if (!isRefundPositive) ...[
            const SizedBox(height: 6),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                const Icon(
                  Icons.warning_amber_outlined,
                  size: 14,
                  color: AppColors.warning,
                ),
                const SizedBox(width: 4),
                Text(
                  l.renterOwes(Formatters.currency(refund.abs())),
                  style: _body(l.ar, size: 11, color: AppColors.warning),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSummaryRow(
    _L l,
    String label,
    String value, {
    Color valueColor = Colors.white,
  }) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(color: Colors.white70, fontSize: 13)
              : GoogleFonts.plusJakartaSans(color: Colors.white70, fontSize: 12.5),
        ),
        Text(
          value,
          style: GoogleFonts.plusJakartaSans(
            color: valueColor,
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
      ],
    );
  }

  // ─── ADDITION CARD ─────────────────────────────────────────────────────────

  Widget _buildAdditionCard(
    LegacyMiftahColors m,
    _L l,
    int index,
    Map<String, dynamic> addition,
  ) {
    final isEditable = _isDraftOrNew;
    final amountCtrl = _additionAmountControllers.putIfAbsent(
      index,
      () => TextEditingController(text: addition['amount']?.toString() ?? '0'),
    );
    final attachments = List<Map<String, dynamic>>.from(
      addition['attachments'] ?? [],
    );
    final additionId = addition['id'] as String?;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.success.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.success.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: DropdownButtonFormField<String>(
                  initialValue:
                      addition['additionCategory'] as String? ?? 'OTHER',
                  isExpanded: true,
                  decoration: InputDecoration(
                    labelText: l.category,
                    isDense: true,
                  ),
                  items: _additionCategories
                      .map(
                        (c) => DropdownMenuItem<String>(
                          value: c,
                          child: Text(
                            _additionCategoryLabel(c),
                            style: _body(l.ar, size: 13),
                          ),
                        ),
                      )
                      .toList(),
                  onChanged: isEditable
                      ? (val) {
                          if (val != null) {
                            setState(
                              () => _additions[index]['additionCategory'] = val,
                            );
                          }
                        }
                      : null,
                ),
              ),
              const SizedBox(width: 10),
              SizedBox(
                width: 110,
                child: TextFormField(
                  controller: amountCtrl,
                  enabled: isEditable,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  textAlign: TextAlign.end,
                  decoration: InputDecoration(
                    labelText: l.amount,
                    prefixText: '+ ',
                    prefixStyle: TextStyle(
                      color: m.success,
                      fontWeight: FontWeight.w600,
                    ),
                    isDense: true,
                  ),
                  onChanged: (v) {
                    _additions[index]['amount'] = v;
                  },
                ),
              ),
              if (isEditable)
                IconButton(
                  icon: Icon(Icons.delete_outline, color: m.danger, size: 20),
                  onPressed: () => setState(() {
                    _additions.removeAt(index);
                    // Clear controllers so they're rebuilt from correct indices
                    for (final ctrl in _additionAmountControllers.values) {
                      ctrl.dispose();
                    }
                    _additionAmountControllers.clear();
                  }),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                ),
            ],
          ),
          const SizedBox(height: 10),
          TextFormField(
            initialValue: addition['description'] as String? ?? '',
            enabled: isEditable,
            decoration: InputDecoration(
              labelText: l.descriptionOptional,
              isDense: true,
            ),
            onChanged: (v) => _additions[index]['description'] = v,
          ),
          if (additionId != null) ...[
            const SizedBox(height: 10),
            // Inline attachment section — updates _additions[index], not _deductions
            Row(
              children: [
                Icon(Icons.attach_file, size: 16, color: m.textSecondary),
                const SizedBox(width: 6),
                Text(
                  l.attachments,
                  style: _body(
                    l.ar,
                    size: 13,
                    weight: FontWeight.w600,
                    color: m.textSecondary,
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: AppColors.accentDark.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    '${attachments.length}/10',
                    style: _body(
                      l.ar,
                      size: 11,
                      weight: FontWeight.w600,
                      color: AppColors.accentDark,
                    ),
                  ),
                ),
                const Spacer(),
                if (isEditable && attachments.length < 10)
                  (_uploadingForDeduction[additionId] == true)
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: AppColors.accentDark,
                          ),
                        )
                      : TextButton.icon(
                          onPressed: () =>
                              _uploadAttachmentForAddition(additionId, index),
                          icon: const Icon(Icons.add, size: 16),
                          label: Text(l.add, style: _body(l.ar, size: 12)),
                          style: TextButton.styleFrom(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 4,
                            ),
                            minimumSize: Size.zero,
                            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                        ),
              ],
            ),
            if (attachments.isNotEmpty) ...[
              const SizedBox(height: 8),
              GridView.builder(
                // Nested in a scroll view: without this the sliver auto-pads
                // with MediaQuery.padding, which under extendBody carries the
                // floating nav height and opens a gap below the content.
                padding: EdgeInsets.zero,
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
                  final name =
                      (attachment['name'] ?? attachment['fileName'] ?? '')
                          as String;
                  final isImage = _isImageFile(name);
                  final isVideo = _isVideoFile(name);
                  final attachmentId = attachment['id'] as String?;

                  return GestureDetector(
                    onLongPress: (!isEditable || attachmentId == null)
                        ? null
                        : () =>
                              _deleteAttachmentForAddition(attachmentId, index),
                    child: Container(
                      decoration: BoxDecoration(
                        color: m.background,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: m.border),
                      ),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(8),
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            if (isImage)
                              _buildImageThumbnail(m, attachment)
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
                                          ? AppColors.accentDark
                                          : m.danger,
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      name.length > 14
                                          ? '${name.substring(0, 12)}…'
                                          : name,
                                      style: TextStyle(
                                        fontSize: 10,
                                        color: m.textSecondary,
                                      ),
                                      textAlign: TextAlign.center,
                                    ),
                                  ],
                                ),
                              ),
                            if (isEditable && attachmentId != null)
                              PositionedDirectional(
                                top: 4,
                                end: 4,
                                child: GestureDetector(
                                  onTap: () => _deleteAttachmentForAddition(
                                    attachmentId,
                                    index,
                                  ),
                                  child: Container(
                                    padding: const EdgeInsets.all(2),
                                    decoration: BoxDecoration(
                                      color: Colors.black54,
                                      borderRadius: BorderRadius.circular(4),
                                    ),
                                    child: const Icon(
                                      Icons.close,
                                      size: 12,
                                      color: Colors.white,
                                    ),
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ],
          ] else if (isEditable)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                l.saveDraftToEnableAttachments,
                style: _body(
                  l.ar,
                  size: 11,
                  color: m.textMuted,
                ).copyWith(fontStyle: FontStyle.italic),
              ),
            ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final IconData icon;
  final String title;
  final LegacyMiftahColors m;
  final _L l;
  const _SectionHeader({
    required this.icon,
    required this.title,
    required this.m,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 17, color: AppColors.accentDark),
        const SizedBox(width: 8),
        Text(
          l.ar ? title : title.toUpperCase(),
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 13,
                  color: AppColors.accentDark,
                  fontWeight: FontWeight.w600,
                )
              : GoogleFonts.plusJakartaSans(
                  fontSize: 10.5,
                  letterSpacing: 2.2,
                  color: AppColors.accentDark,
                  fontWeight: FontWeight.w600,
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

  String get title => ar ? 'التسوية' : 'Settlement';
  String get save => ar ? 'حفظ' : 'Save';
  String get retry => ar ? 'إعادة المحاولة' : 'Retry';
  String get failedToLoadSettlement =>
      ar ? 'تعذر تحميل التسوية' : 'Failed to load settlement';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get delete => ar ? 'حذف' : 'Delete';
  String get add => ar ? 'إضافة' : 'Add';
  String get camera => ar ? 'الكاميرا' : 'Camera';
  String get gallery => ar ? 'معرض الصور' : 'Gallery';
  String get file => ar ? 'ملف' : 'File';
  String get category => ar ? 'الفئة' : 'Category';
  String get amount => ar ? 'المبلغ' : 'Amount';
  String get description => ar ? 'الوصف' : 'Description';
  String get descriptionOptional =>
      ar ? 'الوصف (اختياري)' : 'Description (optional)';
  String get attachments => ar ? 'المرفقات' : 'Attachments';
  String get auto => ar ? 'تلقائي' : 'Auto';
  String get notes => ar ? 'ملاحظات' : 'Notes';
  String get noNotes => ar ? 'لا توجد ملاحظات' : 'No notes';
  String get addNotesHint =>
      ar ? 'أضف ملاحظات التسوية…' : 'Add settlement notes…';

  String get outstandingRent => ar ? 'إيجار مستحق' : 'Outstanding rent';
  String get latePaymentPenalties =>
      ar ? 'غرامات التأخير' : 'Late payment penalties';

  String get draftSaved => ar ? 'تم حفظ المسودة' : 'Draft saved';
  String saveFailed(String e) => ar ? 'فشل الحفظ: $e' : 'Failed to save: $e';
  String uploadFailed(String e) => ar ? 'فشل الرفع: $e' : 'Upload failed: $e';
  String deleteFailed(String e) =>
      ar ? 'فشل الحذف: $e' : 'Failed to delete: $e';
  String finalizeFailed(String e) =>
      ar ? 'فشل الإنهاء: $e' : 'Failed to finalize: $e';

  String get finalizeTitle => ar ? 'إنهاء التسوية' : 'Finalize Settlement';
  String get finalizeBody => ar
      ? 'سيؤدي هذا إلى إنهاء العقد وقفل مبالغ التسوية. يمكن إضافة المرفقات لاحقاً. هل تريد المتابعة؟'
      : 'This will terminate the lease and lock the settlement amounts. Attachments can still be added after. Continue?';
  String get finalizeAndTerminate =>
      ar ? 'إنهاء وتسوية العقد' : 'Finalize & Terminate';

  String get deleteAttachmentTitle => ar ? 'حذف المرفق' : 'Delete Attachment';
  String get deleteAttachmentBody =>
      ar ? 'إزالة هذا المرفق؟' : 'Remove this attachment?';

  String get finalized => ar ? 'منتهية' : 'FINALIZED';
  String onDate(String date) => ar ? 'بتاريخ $date' : 'on $date';

  String get securityDeposit => ar ? 'مبلغ التأمين' : 'Security Deposit';
  String get autoCalculatedDeductions =>
      ar ? 'خصومات محسوبة تلقائياً' : 'Auto-Calculated Deductions';
  String get manualDeductions => ar ? 'خصومات يدوية' : 'Manual Deductions';
  String get addDeduction => ar ? 'إضافة خصم' : 'Add Deduction';
  String get additionsRepayments =>
      ar ? 'إضافات (مبالغ مستردة)' : 'Additions (Repayments)';
  String get addRepayment => ar ? 'إضافة مبلغ مسترد' : 'Add Repayment';
  String get noAdditionsHint => ar
      ? 'لا توجد إضافات. اضغط "إضافة مبلغ مسترد" لإضافة مبالغ مستحقة للمستأجر.'
      : 'No additions. Tap "Add Repayment" to add repayments owed to the renter.';
  String get saveDraftFirst => ar
      ? 'احفظ المسودة أولاً لإضافة مرفقات'
      : 'Save draft first to add attachments';
  String get saveDraftToEnableAttachments =>
      ar ? 'احفظ المسودة لتفعيل المرفقات' : 'Save draft to enable attachments';
  String get saveDraft => ar ? 'حفظ المسودة' : 'Save Draft';

  String get totalDeductions => ar ? 'إجمالي الخصومات' : 'Total Deductions';
  String get totalAdditions => ar ? 'إجمالي الإضافات' : 'Total Additions';
  String get refundToRenter =>
      ar ? 'المبلغ المسترد للمستأجر' : 'Refund to Renter';
  String renterOwes(String amount) =>
      ar ? 'يستحق على المستأجر $amount' : 'Renter owes $amount';

  String deductionCategoryLabel(String category) {
    switch (category) {
      case 'UNPAID_RENT':
        return ar ? 'إيجار غير مسدد' : 'Unpaid Rent';
      case 'PENALTIES':
        return ar ? 'غرامات تأخير' : 'Late Penalties';
      case 'PROPERTY_DAMAGE':
        return ar ? 'أضرار بالعقار' : 'Property Damage';
      case 'EARLY_TERMINATION_FEE':
        return ar ? 'رسوم إنهاء مبكر' : 'Early Termination Fee';
      case 'CLEANING':
        return ar ? 'تنظيف' : 'Cleaning';
      case 'UTILITY_ARREARS':
        return ar ? 'متأخرات خدمات' : 'Utility Arrears';
      case 'KEY_REPLACEMENT':
        return ar ? 'استبدال مفاتيح' : 'Key Replacement';
      case 'OTHER':
        return ar ? 'أخرى' : 'Other';
      default:
        return category;
    }
  }

  String additionCategoryLabel(String category) {
    switch (category) {
      case 'PREPAID_RENT':
        return ar ? 'إيجار مدفوع مسبقاً' : 'Prepaid Rent';
      case 'UTILITY_OVERPAYMENT':
        return ar ? 'دفع زائد للخدمات' : 'Utility Overpayment';
      case 'DEPOSIT_INTEREST':
        return ar ? 'فائدة التأمين' : 'Deposit Interest';
      case 'LANDLORD_COMPENSATION':
        return ar ? 'تعويض من المالك' : 'Landlord Compensation';
      case 'OTHER':
        return ar ? 'أخرى' : 'Other';
      default:
        return category;
    }
  }
}
