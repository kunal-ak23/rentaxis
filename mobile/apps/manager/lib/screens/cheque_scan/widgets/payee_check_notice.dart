import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The organisation's payee-name check on one scanned cheque, as the web
/// review shows it (owner ruling 2026-09-29):
///  - MISMATCH: red, with the name read and an "attach anyway" tick. The
///    server refuses an unconfirmed mismatch (`payee_mismatch_unconfirmed`),
///    so the wizard's submit stays off until it is ticked.
///  - UNREADABLE: a soft, non-blocking note.
///  - MATCH: the name read, quietly confirmed.
///  - null (check off, or no names listed): nothing at all.
class PayeeCheckNotice extends StatelessWidget {
  final String? payeeCheck;
  final String? payeeName;
  final bool confirmed;
  final ValueChanged<bool> onConfirmedChanged;

  /// Distinguishes the tick when several cheques are on screen.
  final Key? checkboxKey;

  const PayeeCheckNotice({
    super.key,
    required this.payeeCheck,
    required this.payeeName,
    required this.confirmed,
    required this.onConfirmedChanged,
    this.checkboxKey,
  });

  @override
  Widget build(BuildContext context) {
    final check = payeeCheck;
    if (check == null) return const SizedBox.shrink();
    final m = context.miftah;
    final l = PayeeL(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
    final name = payeeName ?? '—';

    if (check == PayeeCheck.mismatch) {
      return Container(
        key: const ValueKey('payeeMismatch'),
        padding: const EdgeInsetsDirectional.fromSTEB(12, 10, 8, 4),
        decoration: BoxDecoration(
          color: m.dangerBg,
          border: Border.all(color: m.danger),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.error_outline, size: 16, color: m.danger),
                const SizedBox(width: 8),
                Expanded(
                  child: Text.rich(
                    TextSpan(
                      children: [
                        TextSpan(
                          text: '${l.mismatch}: ',
                          style: bodyFont(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w600,
                            color: m.danger,
                          ),
                        ),
                        // The read name keeps its own direction (an English
                        // payee inside an Arabic sentence, or the reverse).
                        TextSpan(
                          text: '\u2068$name\u2069',
                          style: bodyFont(fontSize: 12.5, color: m.danger),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            CheckboxListTile(
              key: checkboxKey ?? const ValueKey('payeeMismatchConfirm'),
              value: confirmed,
              onChanged: (v) => onConfirmedChanged(v ?? false),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
              dense: true,
              visualDensity: VisualDensity.compact,
              activeColor: m.danger,
              title: Text(
                l.mismatchConfirm,
                style: bodyFont(fontSize: 12, color: m.textPrimary),
              ),
            ),
          ],
        ),
      );
    }

    final unreadable = check == PayeeCheck.unreadable;
    return Container(
      key: ValueKey(unreadable ? 'payeeUnreadable' : 'payeeMatch'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: unreadable ? m.surfaceAlt : m.successBg,
        border: Border.all(
          color: unreadable ? m.border : m.success.withValues(alpha: 0.4),
        ),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            unreadable ? Icons.help_outline : Icons.verified_outlined,
            size: 16,
            color: unreadable ? m.textMuted : m.success,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              unreadable ? l.unreadable : l.matched('\u2068$name\u2069'),
              style: bodyFont(
                fontSize: 12,
                color: unreadable ? m.textSecondary : m.success,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Payee-check strings (EN/AR), worded as the web's `bulkChequeUpload.payee*`.
class PayeeL {
  PayeeL(this.ar);
  final bool ar;

  String get mismatch =>
      ar ? 'اسم المستفيد غير مطابق' : 'Payee name does not match';
  String get mismatchConfirm => ar
      ? 'راجعتُ هذا الشيك وأريد إرفاقه على أي حال'
      : 'I have checked this cheque and want to attach it anyway';
  String get unreadable =>
      ar ? 'تعذّرت قراءة اسم المستفيد.' : 'Payee name could not be read.';
  String matched(String name) =>
      ar ? 'اسم المستفيد مطابق: $name' : 'Payee name matches: $name';
  String get missingConfirm =>
      ar ? 'تأكيد اسم المستفيد' : 'confirm the payee name';
  String get unconfirmedError => ar
      ? 'اسم المستفيد في أحد الشيكات غير مطابق ولم يُؤكَّد. ضع علامة التأكيد لكل شيك مُعلَّم، ثم حاول مجددًا.'
      : "A cheque's payee name does not match and was not confirmed. Tick the confirmation for each flagged cheque, then try again.";
}
