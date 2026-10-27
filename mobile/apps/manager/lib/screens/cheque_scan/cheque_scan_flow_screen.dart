import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'cheque_register_api.dart';
import 'steps/step1_capture.dart';
import 'steps/step2_reading.dart';
import 'steps/step3_confirm.dart';
import 'steps/step3_multi_review.dart';
import 'steps/step4_success.dart';
import 'widgets/scan_messages.dart';

final _chequeExtractionServiceProvider = Provider<ChequeExtractionService>((
  ref,
) {
  final client = ref.watch(apiClientProvider);
  return ChequeExtractionService(client.dio);
});

final _chequeRegisterApiProvider = Provider<ChequeRegisterApi>((ref) {
  final client = ref.watch(apiClientProvider);
  return ChequeRegisterApi(client.dio);
});

/// 4-step cheque scan wizard for the manager mobile app.
///
/// Flow:
///   Step 1 (Capture)  → camera photo, gallery photo, or a PDF / file. One
///                       photo may hold several cheques.
///   Step 2 (Reading)  → upload + extract via /api/v1/cheques/extract-many:
///                       one item per cheque, each with its own server-issued
///                       image (a crop when the photo held several) and the
///                       organisation's payee check.
///   Step 3 (Confirm)  → one cheque: review fields, the payee check, pick the
///                       register row it is for, pick Hold / Deposit today.
///                       Several cheques: one card each, mapped onto one
///                       lease's instalments.
///                       Both submit POST /leases/{id}/cheques/bulk-attach —
///                       the web scanner's endpoint, which refuses a payee
///                       MISMATCH the operator has not confirmed — chained
///                       with PUT /cheques/{id}/deposit for Deposit today.
///   Step 4 (Success)  → receipt + scan-another / done
class ChequeScanFlowScreen extends ConsumerStatefulWidget {
  const ChequeScanFlowScreen({super.key});

  @override
  ConsumerState<ChequeScanFlowScreen> createState() =>
      _ChequeScanFlowScreenState();
}

class _ChequeScanFlowScreenState extends ConsumerState<ChequeScanFlowScreen> {
  int _step = 1;
  File? _captured;
  ChequeMultiExtractionResult? _scan;
  String? _extractionError;
  Map<String, dynamic>? _target;
  Disposition _disposition = Disposition.depositToday;
  bool _submitting = false;
  String? _submitError;
  int _attachedCount = 1;

  Future<void> _onCaptured(File file) async {
    setState(() {
      _captured = file;
      _step = 2;
      _scan = null;
      _extractionError = null;
      // A new photo is a new match: the previous cheque's row does not carry.
      _target = null;
      _submitError = null;
    });
    try {
      final svc = ref.read(_chequeExtractionServiceProvider);
      final result = await svc.extractMany(file);
      if (!mounted) return;
      if (result.items.isEmpty) {
        throw ChequeUploadRefused('No cheque found', null);
      }
      setState(() => _scan = result);
      // Hold step 2 long enough for the reveal animation to play out.
      await Future.delayed(const Duration(milliseconds: 1500));
      if (!mounted) return;
      setState(() => _step = 3);
    } catch (e) {
      if (!mounted) return;
      final ar = context.isAr;
      setState(() {
        _extractionError = e is ChequeUploadRefused
            ? uploadErrorMessage(e.code, e.message, ar)
            : errorMessage(e, networkErrorMessage(ar));
      });
    }
  }

  Future<Map<String, dynamic>?> _openPicker() {
    return showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetCtx) =>
          ChequeTargetPickerSheet(api: ref.read(_chequeRegisterApiProvider)),
    );
  }

  Future<void> _pickTarget() async {
    final picked = await _openPicker();
    if (picked != null && mounted) setState(() => _target = picked);
  }

  String _attachError(Object e) {
    final ar = context.isAr;
    if (e is BulkAttachRefused) {
      return e.reasons
          .toSet()
          .map((r) => attachRefusalMessage(r, ar))
          .join('\n');
    }
    return errorMessage(e, networkErrorMessage(ar));
  }

  Future<void> _onConfirm({
    required String chequeNumber,
    required String bankName,
    required String payerName,
    required DateTime? chequeDate,
    required Disposition disposition,
    required bool payeeMismatchConfirmed,
  }) async {
    final target = _target;
    final scan = _scan;
    if (target == null || scan == null || chequeDate == null) return;
    final chequeId = target['id'].toString();
    setState(() {
      _submitting = true;
      _submitError = null;
      _disposition = disposition;
    });
    var attached = false;
    try {
      final api = ref.read(_chequeRegisterApiProvider);
      await api.bulkAttach(target['leaseId'].toString(), [
        buildAttachItem(
          chequeId: chequeId,
          scan: scan.items.first,
          chequeNumber: chequeNumber,
          bankName: bankName,
          payerName: payerName,
          chequeDate: chequeDate,
          payeeConfirmed: payeeMismatchConfirmed,
        ),
      ]);
      attached = true;
      if (disposition == Disposition.depositToday) {
        await api.deposit(chequeId);
      }
      if (!mounted) return;
      setState(() {
        _attachedCount = 1;
        _step = 4;
        _submitting = false;
      });
    } catch (e) {
      if (!mounted) return;
      final ar = context.isAr;
      final msg = _attachError(e);
      setState(() {
        // Attaching again is safe (the row keeps the scan it now holds), so
        // the operator can retry the deposit or switch to Hold.
        _submitError = attached
            ? (ar
                  ? 'تم إرفاق الشيك، لكن تعذّر الإيداع: $msg'
                  : 'The cheque is attached, but the deposit failed: $msg')
            : msg;
        _submitting = false;
      });
    }
  }

  Future<void> _onSubmitMulti(
    String leaseId,
    List<Map<String, dynamic>> items,
    Map<String, dynamic> leaseRow,
  ) async {
    setState(() {
      _submitting = true;
      _submitError = null;
    });
    try {
      await ref.read(_chequeRegisterApiProvider).bulkAttach(leaseId, items);
      if (!mounted) return;
      setState(() {
        _target = leaseRow;
        _disposition = Disposition.holdInSafe;
        _attachedCount = items.length;
        _step = 4;
        _submitting = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitError = _attachError(e);
        _submitting = false;
      });
    }
  }

  void _resetForAnother() {
    setState(() {
      _step = 1;
      _captured = null;
      _scan = null;
      _extractionError = null;
      _submitError = null;
      _submitting = false;
      _attachedCount = 1;
      _disposition = Disposition.depositToday; // reset to default
      _target = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Scaffold(
      backgroundColor: m.background,
      body: SafeArea(
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 280),
          switchInCurve: Curves.easeOutCubic,
          switchOutCurve: Curves.easeOutCubic,
          transitionBuilder: (child, animation) {
            final curved = CurvedAnimation(
              parent: animation,
              curve: Curves.easeOutCubic,
            );
            return FadeTransition(
              opacity: curved,
              child: SlideTransition(
                position: Tween<Offset>(
                  begin: const Offset(0, 0.04),
                  end: Offset.zero,
                ).animate(curved),
                child: child,
              ),
            );
          },
          child: KeyedSubtree(key: ValueKey(_step), child: _buildBody()),
        ),
      ),
    );
  }

  Widget _buildBody() {
    switch (_step) {
      case 1:
        return Step1Capture(
          onCaptured: _onCaptured,
          onClose: () => context.pop(),
        );
      case 2:
        return Step2Reading(
          capturedImage: _captured!,
          result: _scan?.items.first,
          error: _extractionError,
          onBack: () => setState(() => _step = 1),
          onClose: () => context.pop(),
        );
      case 3:
        final scan = _scan!;
        if (scan.items.length > 1) {
          return Step3MultiReview(
            scan: scan,
            onPickLease: _openPicker,
            loadLeaseRows: ref.read(_chequeRegisterApiProvider).leaseCheques,
            onSubmit: _onSubmitMulti,
            submitting: _submitting,
            submitError: _submitError,
            onBack: () => setState(() => _step = 1),
            onClose: () => context.pop(),
          );
        }
        return Step3Confirm(
          result: scan.items.first,
          matchedCheque: _target,
          onPickCheque: _pickTarget,
          onBack: () => setState(() => _step = 1),
          onConfirm: _onConfirm,
          submitting: _submitting,
          submitError: _submitError,
          onClose: () => context.pop(),
        );
      case 4:
        return Step4Success(
          result: _scan!.items.first,
          matchedCheque: _target,
          disposition: _disposition,
          attachedCount: _attachedCount,
          onScanAnother: _resetForAnother,
          onDone: () => context.pop(),
        );
      default:
        return const SizedBox.shrink();
    }
  }
}

/// Bottom sheet listing the register's post-dated cheques still waiting for
/// their paper (REGISTERED PDC rows), so the user can pick which one the
/// scanned cheque is. Searched server-side.
class ChequeTargetPickerSheet extends StatefulWidget {
  final ChequeRegisterApi api;
  const ChequeTargetPickerSheet({super.key, required this.api});

  @override
  State<ChequeTargetPickerSheet> createState() =>
      _ChequeTargetPickerSheetState();
}

class _ChequeTargetPickerSheetState extends State<ChequeTargetPickerSheet> {
  final _searchController = TextEditingController();
  Timer? _searchDebounce;
  String _query = '';
  late Future<({List<Map<String, dynamic>> rows, int hidden})> _future;

  @override
  void initState() {
    super.initState();
    _future = _load('');
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String value) {
    _searchDebounce?.cancel();
    setState(() => _query = value);
    _searchDebounce = Timer(const Duration(milliseconds: 350), () {
      if (!mounted) return;
      setState(() {
        _future = _load(value);
      });
    });
  }

  void _clearSearch() {
    _searchDebounce?.cancel();
    _searchController.clear();
    setState(() {
      _query = '';
      _future = _load('');
    });
  }

  /// One soonest-cheque-date-first page of scannable rows. `hidden` counts
  /// rows the server still holds beyond the page, so truncation is visible
  /// instead of silent.
  Future<({List<Map<String, dynamic>> rows, int hidden})> _load(
    String query,
  ) async {
    const size = 50;
    final page = await widget.api.searchScannable(search: query, size: size);
    final content = (page['content'] as List? ?? const [])
        .whereType<Map>()
        .map((r) => Map<String, dynamic>.from(r))
        .toList();
    final total = (page['totalElements'] as num?)?.toInt() ?? content.length;
    return (
      rows: content,
      hidden: total > content.length ? total - content.length : 0,
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _PickerL(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
    final keyboardHeight = MediaQuery.viewInsetsOf(context).bottom;
    return AnimatedPadding(
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOut,
      padding: EdgeInsets.fromLTRB(20, 16, 20, 24 + keyboardHeight),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(bottom: 12),
              decoration: BoxDecoration(
                color: m.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          Text(
            l.pickCheque,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            l.pickChequeHint,
            style: bodyFont(fontSize: 12, color: m.textMuted),
          ),
          const SizedBox(height: 14),
          TextField(
            key: const ValueKey('chequePickerSearch'),
            controller: _searchController,
            onChanged: _onSearchChanged,
            textInputAction: TextInputAction.search,
            style: bodyFont(fontSize: 13, color: m.textPrimary),
            decoration: InputDecoration(
              hintText: l.searchHint,
              hintStyle: bodyFont(fontSize: 12, color: m.textMuted),
              prefixIcon: Icon(Icons.search, size: 20, color: m.textMuted),
              suffixIcon: _query.isEmpty
                  ? null
                  : IconButton(
                      key: const ValueKey('chequePickerClearSearch'),
                      tooltip: l.clearSearch,
                      onPressed: _clearSearch,
                      icon: Icon(Icons.close, size: 18, color: m.textMuted),
                    ),
              filled: true,
              fillColor: m.surface,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 12,
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(color: m.border),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(
                  color: Theme.of(context).colorScheme.primary,
                  width: 1.5,
                ),
              ),
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            height: keyboardHeight > 0 ? 220 : 360,
            child: FutureBuilder<({List<Map<String, dynamic>> rows, int hidden})>(
              future: _future,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snap.hasError) {
                  return Center(
                    child: Text(
                      l.failedToLoad(snap.error.toString()),
                      style: bodyFont(fontSize: 12, color: m.danger),
                    ),
                  );
                }
                final pending =
                    snap.data?.rows ?? const <Map<String, dynamic>>[];
                final hidden = snap.data?.hidden ?? 0;
                if (pending.isEmpty) {
                  return Center(
                    child: Text(
                      _query.trim().isEmpty ? l.noPending : l.noMatches,
                      style: bodyFont(fontSize: 13, color: m.textMuted),
                    ),
                  );
                }
                return ListView.separated(
                  itemCount: pending.length + (hidden > 0 ? 1 : 0),
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) {
                    if (i >= pending.length) {
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        child: Text(
                          l.moreNotShown(hidden),
                          textAlign: TextAlign.center,
                          style: bodyFont(fontSize: 11, color: m.textMuted),
                        ),
                      );
                    }
                    final p = pending[i];
                    return InkWell(
                      onTap: () => Navigator.pop(context, p),
                      borderRadius: BorderRadius.circular(12),
                      child: Container(
                        padding: const EdgeInsetsDirectional.symmetric(
                          horizontal: 14,
                          vertical: 12,
                        ),
                        decoration: BoxDecoration(
                          color: m.surface,
                          border: Border.all(color: m.border),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '${p['propertyName'] ?? '—'} · ${p['unitIdentifier'] ?? ''}',
                                    style: bodyFont(
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                      color: m.textPrimary,
                                    ),
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    l.rowMeta(
                                      p['renterName']?.toString() ?? '',
                                      p['seqNo'],
                                      _dueLabel(p['chequeDate'], l.ar),
                                    ),
                                    style: bodyFont(
                                      fontSize: 11,
                                      color: m.textMuted,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              'AED ${_amount(p['amount'])}',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                                color: m.textPrimary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  String _dueLabel(dynamic iso, bool ar) {
    if (iso == null) return '—';
    final dt = DateTime.tryParse(iso.toString());
    if (dt == null) return '—';
    if (ar) return DateFormat('d MMMM', 'ar').format(dt);
    return DateFormat('d MMM').format(dt);
  }

  String _amount(dynamic amount) {
    final n = amount is num ? amount : num.tryParse(amount.toString());
    if (n == null) return '—';
    return NumberFormat('#,##0').format(n);
  }
}

/// Cheque-picker sheet strings (EN/AR). Lightweight per-widget pattern —
/// see arabic-brief.
class _PickerL {
  _PickerL(this.ar);
  final bool ar;

  String get pickCheque =>
      ar ? 'اختر شيكًا مؤجلًا' : 'Pick a post-dated cheque';
  String get pickChequeHint => ar
      ? 'سيتم إرفاق الشيك الممسوح بالقسط المختار في سجل الشيكات.'
      : 'The scanned cheque will be attached to the selected instalment on the register.';
  String get searchHint => ar
      ? 'ابحث بالمستأجر أو رقم الشيك أو الوحدة'
      : 'Search renter, cheque number, or unit';
  String get clearSearch => ar ? 'مسح البحث' : 'Clear search';
  String get noPending => ar
      ? 'لا توجد شيكات مؤجلة بانتظار المسح.'
      : 'No post-dated cheques are waiting for a scan.';
  String get noMatches =>
      ar ? 'لا توجد شيكات تطابق بحثك.' : 'No cheques match your search.';
  String moreNotShown(int count) {
    final formatted = NumberFormat.decimalPattern(
      ar ? 'ar' : 'en',
    ).format(count);
    return ar
        ? 'عدد الشيكات المطابقة الأخرى: $formatted — حدّد البحث لعرضها.'
        : '$formatted more matches — refine the search to narrow them down.';
  }

  String failedToLoad(String error) =>
      ar ? 'تعذّر تحميل الشيكات: $error' : 'Failed to load cheques: $error';
  String rowMeta(String renter, dynamic installment, String due) => ar
      ? '$renter · القسط رقم $installment · تاريخ الشيك $due'
      : '$renter · Instalment #$installment · Dated $due';
}
