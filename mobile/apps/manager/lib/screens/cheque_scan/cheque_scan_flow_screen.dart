import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'steps/step1_capture.dart';
import 'steps/step2_reading.dart';
import 'steps/step3_confirm.dart';
import 'steps/step4_success.dart';

final _chequeExtractionServiceProvider = Provider<ChequeExtractionService>((
  ref,
) {
  final client = ref.watch(apiClientProvider);
  return ChequeExtractionService(client.dio);
});

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

/// 4-step cheque scan wizard for the manager mobile app.
///
/// Flow:
///   Step 1 (Capture)  → user takes photo with camera or picks from gallery
///   Step 2 (Reading)  → upload + extract via /api/v1/cheques/extract;
///                       fields reveal progressively while we POST,
///                       and we auto-advance to step 3 on completion
///   Step 3 (Confirm)  → user reviews extracted fields, picks a payment
///                       to settle, picks disposition (Hold / Deposit today)
///                       and submits → PUT /payments/{id}/collect
///                       (chained with /deposit if Disposition.depositToday)
///   Step 4 (Success)  → receipt + scan-another / done
///
/// If [paymentId] is supplied (from payments_screen "Collect" tap), the
/// payment is pre-selected and the user doesn't need to pick one.
class ChequeScanFlowScreen extends ConsumerStatefulWidget {
  final String? paymentId;
  const ChequeScanFlowScreen({super.key, this.paymentId});

  @override
  ConsumerState<ChequeScanFlowScreen> createState() =>
      _ChequeScanFlowScreenState();
}

class _ChequeScanFlowScreenState extends ConsumerState<ChequeScanFlowScreen> {
  int _step = 1;
  File? _captured;
  ChequeExtractionResult? _extracted;
  String? _extractionError;
  String? _selectedPaymentId;
  Map<String, dynamic>? _matchedPayment;
  Disposition _disposition = Disposition.depositToday;
  bool _submitting = false;
  String? _submitError;

  @override
  void initState() {
    super.initState();
    _selectedPaymentId = widget.paymentId;
    if (widget.paymentId != null) {
      _fetchMatchedPayment(widget.paymentId!);
    }
  }

  Future<void> _fetchMatchedPayment(String paymentId) async {
    try {
      final svc = ref.read(_paymentServiceProvider);
      // Best-effort: backend has no single-payment endpoint, but we
      // can fetch the user's full list and pick by id. Keeps the match
      // card useful even though it's a manual link, not auto-match.
      final list = await svc.getPayments();
      final found = list.firstWhere(
        (p) => p is Map<String, dynamic> && p['id'] == paymentId,
        orElse: () => null,
      );
      if (found != null && mounted) {
        setState(() => _matchedPayment = Map<String, dynamic>.from(found));
      }
    } catch (_) {
      // Non-fatal — user can still pick manually.
    }
  }

  Future<void> _onCaptured(File file) async {
    setState(() {
      _captured = file;
      _step = 2;
      _extracted = null;
      _extractionError = null;
    });
    try {
      final svc = ref.read(_chequeExtractionServiceProvider);
      final result = await svc.extract(file);
      if (!mounted) return;
      // Hold step 2 long enough for the reveal animation to play out.
      await Future.delayed(const Duration(milliseconds: 1500));
      if (!mounted) return;
      setState(() {
        _extracted = result;
        _step = 3;
      });
    } catch (e) {
      if (!mounted) return;
      final ar = context.isAr;
      setState(() {
        _extractionError = ar ? 'تعذّرت القراءة: $e' : 'Extraction failed: $e';
      });
    }
  }

  Future<void> _pickPayment() async {
    final picked = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetCtx) =>
          _PaymentPickerSheet(service: ref.read(_paymentServiceProvider)),
    );
    if (picked != null && mounted) {
      setState(() {
        _selectedPaymentId = picked['id']?.toString();
        _matchedPayment = picked;
      });
    }
  }

  Future<void> _onConfirm({
    required String chequeNumber,
    required String bankName,
    required String payerName,
    required DateTime? chequeDate,
    required Disposition disposition,
  }) async {
    if (_selectedPaymentId == null || _extracted == null) return;
    setState(() {
      _submitting = true;
      _submitError = null;
      _disposition = disposition;
    });
    try {
      final svc = ref.read(_paymentServiceProvider);
      await svc.collectPayment(_selectedPaymentId!, {
        'chequeNumber': chequeNumber,
        'bankName': bankName,
        'payerName': payerName,
        'chequeDate': chequeDate != null
            ? DateFormat('yyyy-MM-dd').format(chequeDate)
            : null,
        'chequeImageUrl': _extracted!.imageUrl,
        'chequeImageBlobPath': _extracted!.imageBlobPath,
        // Normalize to UTC so the emitted ISO string carries a `Z` suffix.
        // Backend (Spring + Jackson) deserializes OffsetDateTime strictly: a
        // string with no offset (Dart's default for non-UTC DateTime) would
        // be rejected.
        'chequeImageUploadedAt': _extracted!.uploadedAt
            .toUtc()
            .toIso8601String(),
      });
      if (disposition == Disposition.depositToday) {
        await svc.depositPayment(_selectedPaymentId!);
      }
      if (!mounted) return;
      setState(() {
        _step = 4;
        _submitting = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitError = e.toString();
        _submitting = false;
      });
    }
  }

  void _resetForAnother() {
    setState(() {
      _step = 1;
      _captured = null;
      _extracted = null;
      _extractionError = null;
      _submitError = null;
      _submitting = false;
      _disposition = Disposition.depositToday; // reset to default
      // Keep the matched payment / paymentId IF it was passed in via deep link.
      if (widget.paymentId == null) {
        _selectedPaymentId = null;
        _matchedPayment = null;
      }
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
          result: _extracted,
          error: _extractionError,
          onBack: () => setState(() => _step = 1),
          onClose: () => context.pop(),
        );
      case 3:
        return Step3Confirm(
          result: _extracted!,
          matchedPayment: _matchedPayment,
          onPickPayment: _pickPayment,
          onBack: () => setState(() => _step = 2),
          onConfirm: _onConfirm,
          submitting: _submitting,
          submitError: _submitError,
          onClose: () => context.pop(),
        );
      case 4:
        return Step4Success(
          result: _extracted!,
          matchedPayment: _matchedPayment,
          disposition: _disposition,
          onScanAnother: _resetForAnother,
          onDone: () => context.pop(),
        );
      default:
        return const SizedBox.shrink();
    }
  }
}

/// Bottom sheet listing PENDING payments so the user can pick which one
/// the scanned cheque settles. Manual until backend gets an auto-match
/// endpoint.
class _PaymentPickerSheet extends StatefulWidget {
  final PaymentService service;
  const _PaymentPickerSheet({required this.service});

  @override
  State<_PaymentPickerSheet> createState() => _PaymentPickerSheetState();
}

class _PaymentPickerSheetState extends State<_PaymentPickerSheet> {
  late Future<List<dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.service.getPayments();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _PickerL(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
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
            l.pickPayment,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.cinzel(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            l.pickPaymentHint,
            style: bodyFont(fontSize: 12, color: m.textMuted),
          ),
          const SizedBox(height: 14),
          SizedBox(
            height: 360,
            child: FutureBuilder<List<dynamic>>(
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
                // Collectable statuses — matches the payments screen action
                // sheet, which offers "Collect" for PENDING and OVERDUE.
                final pending = (snap.data ?? const [])
                    .whereType<Map<String, dynamic>>()
                    .where(
                      (p) =>
                          p['status'] == 'PENDING' || p['status'] == 'OVERDUE',
                    )
                    .toList();
                if (pending.isEmpty) {
                  return Center(
                    child: Text(
                      l.noPending,
                      style: bodyFont(fontSize: 13, color: m.textMuted),
                    ),
                  );
                }
                return ListView.separated(
                  itemCount: pending.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) {
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
                                      p['installmentNumber'],
                                      _dueLabel(p['dueDate'], l.ar),
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
                              style: GoogleFonts.cinzel(
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

/// Payment-picker sheet strings (EN/AR). Lightweight per-widget pattern —
/// see arabic-brief.
class _PickerL {
  _PickerL(this.ar);
  final bool ar;

  String get pickPayment => ar ? 'اختر دفعة معلّقة' : 'Pick a pending payment';
  String get pickPaymentHint => ar
      ? 'سيتم ربط الشيك الممسوح بالقسط المختار.'
      : 'The scanned cheque will be linked to the selected installment.';
  String get noPending =>
      ar ? 'لا توجد دفعات معلّقة للتحصيل.' : 'No pending payments to collect.';
  String failedToLoad(String error) =>
      ar ? 'تعذّر تحميل المدفوعات: $error' : 'Failed to load payments: $error';
  String rowMeta(String renter, dynamic installment, String due) => ar
      ? '$renter · القسط رقم $installment · الاستحقاق $due'
      : '$renter · Installment #$installment · Due $due';
}
