import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
// `hide TextDirection`: intl ships a bidi class of that name whose constants
// are LTR/RTL, which silently shadows Flutter's enum. Same guard as
// `manager/lib/screens/cheque_scan/steps/step3_confirm.dart`.
import 'package:intl/intl.dart' hide TextDirection;
import 'package:rentaxis_core/rentaxis_core.dart';

/// The coupon reveal, shown when a `COUPON` ad is tapped. Ticket-styled: a
/// dark value panel, a perforation, then the code and its terms. Nothing
/// leaves the app.
class CouponSheet extends StatelessWidget {
  const CouponSheet({super.key, required this.ad});

  final PromoAd ad;

  static Future<void> show(BuildContext context, PromoAd ad) {
    return showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) => CouponSheet(ad: ad),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final code = ad.couponCode?.trim();
    final terms = ad.couponTerms(isAr);
    final l = _L(isAr);

    return Container(
      decoration: const BoxDecoration(
        color: MiftahColors.surface,
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(MiftahRadii.sheet),
        ),
      ),
      padding: EdgeInsets.fromLTRB(
        MiftahSpacing.page,
        14,
        MiftahSpacing.page,
        MediaQuery.viewInsetsOf(context).bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: MiftahColors.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(ad.business.name(isAr), style: MiftahType.meta()),
          const SizedBox(height: 4),
          Text(ad.title(isAr), style: MiftahType.title()),
          const SizedBox(height: 18),
          if (code != null && code.isNotEmpty) ...[
            Container(
              padding: const EdgeInsets.all(MiftahSpacing.cardPad),
              decoration: BoxDecoration(
                color: MiftahColors.brassTint,
                borderRadius: BorderRadius.circular(MiftahRadii.tile),
                border: Border.all(color: MiftahColors.brassTintBorder),
              ),
              child: Row(
                children: [
                  Expanded(
                    // A coupon code is an opaque identifier, not prose, so it
                    // renders LTR whatever the app's language is. The Arabic
                    // app makes this subtree RTL, and bidi then reorders any
                    // code that mixes digits and Latin letters around a
                    // neutral — `10-OFF` paints as `OFF-10`, `25/MIFTAH` as
                    // `MIFTAH/25`. The renter would read that reversed string
                    // to a cashier while the Copy button put the real one on
                    // the clipboard, with nothing on screen to say which is
                    // right. `textAlign` keeps the block on the sheet's
                    // reading edge; only the glyph order is pinned.
                    child: Text(
                      code,
                      textDirection: TextDirection.ltr,
                      textAlign: isAr ? TextAlign.right : TextAlign.left,
                      style: MiftahType.mono(size: 18),
                    ),
                  ),
                  TextButton.icon(
                    key: const Key('coupon-copy'),
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: code));
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(l.copied)),
                      );
                    },
                    icon: const Icon(Icons.copy_rounded, size: 18),
                    label: Text(l.copy),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
          ],
          if (terms != null) ...[
            Text(l.terms, style: MiftahType.sectionLabel()),
            const SizedBox(height: 5),
            Text(terms, key: const Key('coupon-terms'), style: MiftahType.body()),
            const SizedBox(height: 14),
          ],
          if (ad.endsAt != null)
            Text(l.validUntilLine(ad.endsAt!), style: MiftahType.meta()),
        ],
      ),
    );
  }
}

class _L {
  const _L(this.ar);
  final bool ar;

  String get copy => ar ? 'نسخ' : 'Copy';
  String get copied => ar ? 'تم نسخ الرمز' : 'Code copied';
  String get terms => ar ? 'الشروط' : 'TERMS';
  String get validUntil => ar ? 'ساري حتى' : 'Valid until';

  /// The expiry line, with the month name in the app's own language — an
  /// English "Mar" inside an otherwise Arabic sheet reads half-translated.
  /// Same pattern as `payments_screen._fmtDate` and `home_screen._shortDate`.
  ///
  /// `intl` only knows a named locale once its date symbols are initialised,
  /// which `GlobalMaterialLocalizations` does as it loads — the same delegate
  /// that decides [ar] in the first place, so the real app is always ready.
  /// The fallback is for anything that mounts this sheet without it: an
  /// un-localised month beats throwing inside a bottom sheet.
  String validUntilLine(DateTime endsAt) {
    final when = endsAt.toLocal();
    try {
      return '$validUntil ${DateFormat('d MMM yyyy', ar ? 'ar' : 'en').format(when)}';
    } on Exception {
      return '$validUntil ${DateFormat('d MMM yyyy').format(when)}';
    }
  }
}
