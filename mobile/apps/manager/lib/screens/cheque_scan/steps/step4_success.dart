import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../widgets/step_header.dart';
import 'step3_confirm.dart' show Disposition;

/// Step 4 — success state. Shows the receipt + next-action buttons.
class Step4Success extends StatelessWidget {
  final ChequeExtractionResult result;
  final Map<String, dynamic>? matchedPayment;
  final Disposition disposition;
  final VoidCallback onScanAnother;
  final VoidCallback onDone;

  const Step4Success({
    super.key,
    required this.result,
    required this.matchedPayment,
    required this.disposition,
    required this.onScanAnother,
    required this.onDone,
  });

  @override
  Widget build(BuildContext context) {
    final ex = result.extracted ?? const {};
    final chequeNumber = ex['chequeNumber']?.toString() ?? '—';
    final amount = matchedPayment?['amount'];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 4,
          title: 'Cheque logged',
          showBack: false,
          onClose: onDone,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Container(
                  width: 96,
                  height: 96,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(
                      colors: [
                        AppColors.accentLight,
                        AppColors.accentLight.withValues(alpha: 0.0),
                      ],
                    ),
                  ),
                  child: Center(
                    child: Container(
                      width: 64,
                      height: 64,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: AppColors.accent,
                        boxShadow: [
                          BoxShadow(
                            color: AppColors.accent.withValues(alpha: 0.5),
                            blurRadius: 24,
                            offset: const Offset(0, 8),
                          ),
                        ],
                      ),
                      child: const Icon(Icons.check,
                          size: 32, color: AppColors.primary),
                    ),
                  ),
                ),
                const SizedBox(height: 18),
                Text(
                  'Cheque saved',
                  style: GoogleFonts.sourceSerif4(
                    fontSize: 24,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.4,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 6),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    _subtitle(chequeNumber),
                    textAlign: TextAlign.center,
                    style: GoogleFonts.inter(
                      fontSize: 13,
                      height: 1.5,
                      color: AppColors.textSecondary,
                    ),
                  ),
                ),
                const SizedBox(height: 22),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    border: Border.all(color: AppColors.border),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'LOGGED',
                                  style: GoogleFonts.inter(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w600,
                                    color: AppColors.textMuted,
                                    letterSpacing: 0.6,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  'CHQ-$chequeNumber',
                                  style: GoogleFonts.jetBrainsMono(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: AppColors.textPrimary,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  _matchLine(),
                                  style: GoogleFonts.inter(
                                    fontSize: 11,
                                    color: AppColors.textMuted,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          if (amount != null)
                            Text(
                              'AED ${_formatAmount(amount)}',
                              style: GoogleFonts.sourceSerif4(
                                fontSize: 22,
                                fontWeight: FontWeight.w600,
                                color: AppColors.textPrimary,
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Container(
                        height: 1,
                        color: AppColors.border,
                      ),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            'Status',
                            style: GoogleFonts.inter(
                              fontSize: 11.5,
                              color: AppColors.textMuted,
                            ),
                          ),
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 3),
                            decoration: BoxDecoration(
                              color: _statusColors().bg,
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Container(
                                  width: 6,
                                  height: 6,
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    color: _statusColors().fg,
                                  ),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  _statusLabel(),
                                  style: GoogleFonts.inter(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w600,
                                    color: _statusColors().fg,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 18),
                SizedBox(
                  width: double.infinity,
                  height: 46,
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.surface,
                      foregroundColor: AppColors.textPrimary,
                      side: const BorderSide(color: AppColors.border),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    onPressed: onScanAnother,
                    child: Text(
                      'Scan another cheque',
                      style: GoogleFonts.inter(
                          fontSize: 14, fontWeight: FontWeight.w500),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  height: 46,
                  child: TextButton(
                    onPressed: onDone,
                    child: Text(
                      'Done',
                      style: GoogleFonts.inter(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  String _subtitle(String chequeNumber) {
    final payer = (result.extracted ?? const {})['payerName']?.toString() ?? 'the payer';
    switch (disposition) {
      case Disposition.holdInSafe:
        return 'CHQ-$chequeNumber from $payer is logged and held for later deposit.';
      case Disposition.depositToday:
        return 'CHQ-$chequeNumber from $payer is queued for deposit today.';
      case Disposition.scheduleDeposit:
        return 'CHQ-$chequeNumber from $payer is scheduled.';
    }
  }

  String _matchLine() {
    if (matchedPayment == null) return 'No lease match';
    final prop = matchedPayment!['propertyName']?.toString() ?? '';
    final unit = matchedPayment!['unitIdentifier']?.toString() ?? '';
    final inst = matchedPayment!['installmentNumber'];
    return '$prop · $unit · Installment #$inst';
  }

  String _formatAmount(dynamic amount) {
    final n = amount is num ? amount : num.tryParse(amount.toString());
    if (n == null) return '—';
    return NumberFormat('#,##0').format(n);
  }

  String _statusLabel() => disposition == Disposition.depositToday
      ? 'queued for deposit'
      : 'logged';

  ({Color fg, Color bg}) _statusColors() => disposition == Disposition.depositToday
      ? (fg: AppColors.warning, bg: AppColors.warningLight)
      : (fg: AppColors.success, bg: AppColors.successLight);
}
