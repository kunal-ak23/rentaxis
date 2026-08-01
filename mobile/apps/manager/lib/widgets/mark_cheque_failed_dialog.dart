import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// Reason options for cheque failure. Values are backend enum keys; labels are
// resolved via _L at render time.
const _kReasonValues = ['BOUNCE', 'SIGNATURE_MISMATCH', 'ACCOUNT_CLOSED'];

/// Shows the mark-cheque-failed dialog and returns the response Map on
/// success, or null if the user cancelled.
Future<Map<String, dynamic>?> showMarkChequeFailedDialog(
  BuildContext context, {
  required String paymentId,
  required int installmentNumber,
  required num amount,
  required PaymentService paymentService,
}) {
  return showDialog<Map<String, dynamic>>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => _MarkChequeFailedDialog(
      paymentId: paymentId,
      installmentNumber: installmentNumber,
      amount: amount,
      paymentService: paymentService,
    ),
  );
}

class _MarkChequeFailedDialog extends StatefulWidget {
  final String paymentId;
  final int installmentNumber;
  final num amount;
  final PaymentService paymentService;

  const _MarkChequeFailedDialog({
    required this.paymentId,
    required this.installmentNumber,
    required this.amount,
    required this.paymentService,
  });

  @override
  State<_MarkChequeFailedDialog> createState() =>
      _MarkChequeFailedDialogState();
}

class _MarkChequeFailedDialogState extends State<_MarkChequeFailedDialog> {
  String? _selectedReason;
  final _notesController = TextEditingController();
  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void dispose() {
    _notesController.dispose();
    super.dispose();
  }

  bool get _canSubmit => _selectedReason != null && !_isSubmitting;

  Future<void> _submit(_L l) async {
    if (!_canSubmit) return;
    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });
    try {
      final result = await widget.paymentService.markPaymentFailed(
        widget.paymentId,
        failureReason: _selectedReason!,
        notes: _notesController.text.trim().isEmpty
            ? null
            : _notesController.text.trim(),
      );
      if (mounted) Navigator.of(context).pop(result);
    } on DioException catch (e) {
      String message = l.failedGeneric;
      final data = e.response?.data;
      if (data is Map && data['message'] != null) {
        message = data['message'].toString();
      } else if (data is String && data.isNotEmpty) {
        message = data;
      }
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = message;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = l.failedGeneric;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final amountStr = Formatters.currency(widget.amount.toDouble());
    final bodyStyle = (l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans);

    return AlertDialog(
      backgroundColor: m.surface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      title: Row(
        children: [
          Icon(Icons.cancel_outlined, color: m.danger, size: 22),
          const SizedBox(width: 10),
          Text(
            l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.cinzel(
                    fontWeight: FontWeight.w600,
                    fontSize: 18,
                    color: m.textPrimary,
                  ),
          ),
        ],
      ),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              l.subtitle(widget.installmentNumber, amountStr),
              style: bodyStyle(fontSize: 13, color: m.textSecondary),
            ),
            const SizedBox(height: 20),

            // Reason dropdown
            DropdownButtonFormField<String>(
              value: _selectedReason,
              isExpanded: true,
              decoration: InputDecoration(
                labelText: l.failureReason,
                prefixIcon: const Icon(Icons.error_outline, size: 20),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 12,
                ),
              ),
              items: _kReasonValues
                  .map(
                    (v) => DropdownMenuItem<String>(
                      value: v,
                      child: Text(
                        l.reasonLabel(v),
                        style: bodyStyle(fontSize: 14, color: m.textPrimary),
                      ),
                    ),
                  )
                  .toList(),
              onChanged: _isSubmitting
                  ? null
                  : (v) => setState(() => _selectedReason = v),
            ),
            const SizedBox(height: 16),

            // Notes field
            TextFormField(
              controller: _notesController,
              enabled: !_isSubmitting,
              maxLines: 3,
              textDirection: l.ar ? TextDirection.rtl : TextDirection.ltr,
              style: bodyStyle(fontSize: 14, color: m.textPrimary),
              decoration: InputDecoration(
                labelText: l.notesOptional,
                prefixIcon: const Padding(
                  padding: EdgeInsetsDirectional.only(bottom: 40),
                  child: Icon(Icons.notes_outlined, size: 20),
                ),
                alignLabelWithHint: true,
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 12,
                ),
              ),
            ),

            // Inline error
            if (_errorMessage != null) ...[
              const SizedBox(height: 14),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: m.dangerBg,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: m.danger.withValues(alpha: 0.3)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      Icons.warning_amber_outlined,
                      color: m.danger,
                      size: 18,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _errorMessage!,
                        style: bodyStyle(fontSize: 12, color: m.danger),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isSubmitting
              ? null
              : () => Navigator.of(context).pop(null),
          child: Text(
            l.cancel,
            style: bodyStyle(
              fontWeight: FontWeight.w600,
              color: m.textSecondary,
            ),
          ),
        ),
        ElevatedButton(
          onPressed: _canSubmit ? () => _submit(l) : null,
          style: ElevatedButton.styleFrom(
            backgroundColor: m.danger,
            foregroundColor: Colors.white,
            disabledBackgroundColor: m.danger.withValues(alpha: 0.4),
            disabledForegroundColor: Colors.white70,
          ),
          child: _isSubmitting
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : Text(
                  l.ar ? l.markFailed : l.markFailed.toUpperCase(),
                  style: bodyStyle(fontWeight: FontWeight.w600),
                ),
        ),
      ],
    );
  }
}

/// Dialog strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تحديد الشيك كمرتجع' : 'Mark cheque failed';

  String subtitle(int installment, String amount) => ar
      ? 'القسط رقم $installment — $amount درهم. اختر السبب؛ سيتم تطبيق غرامة.'
      : 'Installment #$installment — $amount AED. Choose a reason; a fine will be applied.';

  String get failureReason => ar ? 'سبب الارتجاع *' : 'Failure reason *';
  String get notesOptional => ar ? 'ملاحظات (اختياري)' : 'Notes (optional)';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get markFailed => ar ? 'تحديد كمرتجع' : 'Mark Failed';
  String get failedGeneric =>
      ar ? 'تعذّر تحديد الشيك كمرتجع' : 'Failed to mark cheque failed';

  String reasonLabel(String value) => switch (value) {
    'BOUNCE' => ar ? 'ارتجاع (رصيد غير كافٍ)' : 'Bounced (insufficient funds)',
    'SIGNATURE_MISMATCH' => ar ? 'عدم تطابق التوقيع' : 'Signature mismatch',
    'ACCOUNT_CLOSED' => ar ? 'الحساب مغلق' : 'Account closed',
    _ => value,
  };
}
