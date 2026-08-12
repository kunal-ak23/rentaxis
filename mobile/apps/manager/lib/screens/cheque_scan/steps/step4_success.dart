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
    final m = context.miftah;
    final l = _L(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    final ex = result.extracted ?? const {};
    final chequeNumber = ex['chequeNumber']?.toString() ?? '—';
    final amount = matchedPayment?['amount'];
    final statusColors = _statusColors(m);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 4,
          title: l.chequeLogged,
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
                      child: const Icon(
                        Icons.check,
                        size: 32,
                        color: AppColors.primary,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 18),
                Text(
                  l.chequeSaved,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 22,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.cinzel(
                          fontSize: 22,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        ),
                ),
                const SizedBox(height: 6),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    l.subtitle(
                      disposition,
                      chequeNumber,
                      ex['payerName']?.toString(),
                    ),
                    textAlign: TextAlign.center,
                    style: bodyFont(
                      fontSize: 13,
                      height: 1.5,
                      color: m.textSecondary,
                    ),
                  ),
                ),
                const SizedBox(height: 22),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: m.surface,
                    border: Border.all(color: m.border),
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
                                  l.ar ? l.logged : l.logged.toUpperCase(),
                                  style: bodyFont(
                                    fontSize: l.ar ? 12 : 11,
                                    fontWeight: FontWeight.w600,
                                    color: m.textMuted,
                                    letterSpacing: l.ar ? 0 : 0.6,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  'CHQ-$chequeNumber',
                                  style: GoogleFonts.jetBrainsMono(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: m.textPrimary,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  l.matchLine(matchedPayment),
                                  style: bodyFont(
                                    fontSize: 11,
                                    color: m.textMuted,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          if (amount != null)
                            Flexible(
                              child: Text(
                                'AED ${_formatAmount(amount)}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: GoogleFonts.cinzel(
                                  fontSize: 20,
                                  fontWeight: FontWeight.w600,
                                  color: m.textPrimary,
                                ),
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Container(height: 1, color: m.divider),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            l.status,
                            style: bodyFont(fontSize: 11.5, color: m.textMuted),
                          ),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 3,
                            ),
                            decoration: BoxDecoration(
                              color: statusColors.bg,
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  statusColors.icon,
                                  size: 11,
                                  color: statusColors.fg,
                                ),
                                const SizedBox(width: 5),
                                Text(
                                  disposition == Disposition.depositToday
                                      ? l.queuedForDeposit
                                      : l.loggedStatus,
                                  style: bodyFont(
                                    fontSize: 10.5,
                                    fontWeight: FontWeight.w600,
                                    color: statusColors.fg,
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
                GoldButton.outlined(
                  label: l.scanAnother,
                  onPressed: onScanAnother,
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  height: 46,
                  child: TextButton(
                    onPressed: onDone,
                    child: Text(
                      l.done,
                      style: bodyFont(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: m.textSecondary,
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

  String _formatAmount(dynamic amount) {
    final n = amount is num ? amount : num.tryParse(amount.toString());
    if (n == null) return '—';
    return NumberFormat('#,##0').format(n);
  }

  /// Neither disposition is CLEARED (settled), so neither reads green — both
  /// stay bronze, distinguished by icon, matching the payments-screen
  /// convention (PENDING/COLLECTED/DEPOSITED are bronze; only CLEARED is
  /// green).
  ({Color fg, Color bg, IconData icon}) _statusColors(LegacyMiftahColors m) =>
      disposition == Disposition.depositToday
      ? (
          fg: AppColors.goldMid,
          bg: m.isDark
              ? AppColors.goldMid.withValues(alpha: 0.14)
              : AppColors.goldMid.withValues(alpha: 0.14),
          icon: Icons.account_balance_outlined,
        )
      : (
          fg: AppColors.accentDark,
          bg: AppColors.accentDark.withValues(alpha: 0.12),
          icon: Icons.move_to_inbox_outlined,
        );
}

/// Step 4 strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get chequeLogged => ar ? 'تم تسجيل الشيك' : 'Cheque logged';
  String get chequeSaved => ar ? 'تم حفظ الشيك' : 'Cheque saved';
  String get logged => ar ? 'مسجّل' : 'Logged';
  String get status => ar ? 'الحالة' : 'Status';
  String get queuedForDeposit => ar ? 'قيد الإيداع' : 'Queued for deposit';
  String get loggedStatus => ar ? 'مسجّل' : 'Logged';
  String get scanAnother => ar ? 'مسح شيك آخر' : 'Scan another cheque';
  String get done => ar ? 'تم' : 'Done';
  String get noLeaseMatch => ar ? 'لا يوجد تطابق مع عقد' : 'No lease match';

  String subtitle(Disposition disposition, String chequeNumber, String? payer) {
    final p = (payer == null || payer.isEmpty)
        ? (ar ? 'الدافع' : 'the payer')
        : payer;
    switch (disposition) {
      case Disposition.holdInSafe:
        return ar
            ? 'تم تسجيل شيك رقم $chequeNumber من $p وهو محفوظ للإيداع لاحقاً.'
            : 'CHQ-$chequeNumber from $p is logged and held for later deposit.';
      case Disposition.depositToday:
        return ar
            ? 'شيك رقم $chequeNumber من $p في قائمة الإيداع اليوم.'
            : 'CHQ-$chequeNumber from $p is queued for deposit today.';
      case Disposition.scheduleDeposit:
        return ar
            ? 'تمت جدولة شيك رقم $chequeNumber من $p.'
            : 'CHQ-$chequeNumber from $p is scheduled.';
    }
  }

  String matchLine(Map<String, dynamic>? matchedPayment) {
    if (matchedPayment == null) return noLeaseMatch;
    final prop = matchedPayment['propertyName']?.toString() ?? '';
    final unit = matchedPayment['unitIdentifier']?.toString() ?? '';
    final inst = matchedPayment['installmentNumber'];
    return ar
        ? '$prop · $unit · القسط رقم $inst'
        : '$prop · $unit · Installment #$inst';
  }
}
