import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// Reason options for cheque failure.
const _kReasonOptions = [
  _ReasonOption('BOUNCE', 'Bounced (insufficient funds)'),
  _ReasonOption('SIGNATURE_MISMATCH', 'Signature mismatch'),
  _ReasonOption('ACCOUNT_CLOSED', 'Account closed'),
];

class _ReasonOption {
  final String value;
  final String label;
  const _ReasonOption(this.value, this.label);
}

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

  Future<void> _submit() async {
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
      String message = 'Failed to mark cheque failed';
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
          _errorMessage = 'Failed to mark cheque failed';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final amountStr =
        Formatters.currency(widget.amount.toDouble());

    return AlertDialog(
      title: Row(
        children: [
          const Icon(Icons.cancel_outlined, color: AppColors.danger, size: 22),
          const SizedBox(width: 10),
          const Text('Mark cheque failed'),
        ],
      ),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Installment #${widget.installmentNumber} — $amountStr AED. '
              'Choose a reason; a fine will be applied.',
              style: const TextStyle(
                  fontSize: 13, color: AppColors.textSecondary),
            ),
            const SizedBox(height: 20),

            // Reason dropdown
            DropdownButtonFormField<String>(
              value: _selectedReason,
              isExpanded: true,
              decoration: const InputDecoration(
                labelText: 'Failure reason *',
                prefixIcon: Icon(Icons.error_outline, size: 20),
                contentPadding:
                    EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              ),
              items: _kReasonOptions
                  .map((opt) => DropdownMenuItem<String>(
                        value: opt.value,
                        child: Text(opt.label,
                            style: const TextStyle(fontSize: 14)),
                      ))
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
              decoration: const InputDecoration(
                labelText: 'Notes (optional)',
                prefixIcon: Padding(
                  padding: EdgeInsets.only(bottom: 40),
                  child: Icon(Icons.notes_outlined, size: 20),
                ),
                alignLabelWithHint: true,
                contentPadding:
                    EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              ),
            ),

            // Inline error
            if (_errorMessage != null) ...[
              const SizedBox(height: 14),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppColors.danger.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                      color: AppColors.danger.withValues(alpha: 0.3)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.warning_amber_outlined,
                        color: AppColors.danger, size: 18),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _errorMessage!,
                        style: const TextStyle(
                            fontSize: 12, color: AppColors.danger),
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
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: _canSubmit ? _submit : null,
          style: ElevatedButton.styleFrom(
            backgroundColor: AppColors.danger,
            foregroundColor: Colors.white,
            disabledBackgroundColor: AppColors.danger.withValues(alpha: 0.4),
            disabledForegroundColor: Colors.white70,
          ),
          child: _isSubmitting
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white),
                )
              : const Text('Mark Failed'),
        ),
      ],
    );
  }
}
