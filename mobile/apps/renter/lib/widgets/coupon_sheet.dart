import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
// `hide TextDirection`: intl ships a bidi class of that name whose constants
// are LTR/RTL, which silently shadows Flutter's enum. Same guard as
// `manager/lib/screens/cheque_scan/steps/step3_confirm.dart`.
import 'package:intl/intl.dart' hide TextDirection;
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

import '../utils/promo_url.dart';
import 'coupon_redirect_sheet.dart';

/// The coupon reveal, shown when a `COUPON` ad is tapped. Ticket-styled: a
/// dark value panel, a perforation, then the code and its terms.
///
/// Nothing leaves the app unless the ad has a `redirectUrl`: **Copy code**
/// then also opens that link in the business's own app or browser (see
/// [_copyAndRedirect]).
class CouponSheet extends StatelessWidget {
  const CouponSheet({super.key, required this.ad, this.launcher});

  final PromoAd ad;

  /// How the redirect is opened; defaults to `url_launcher`. A seam for tests,
  /// and how `PromoActions` routes this through its own launcher.
  final PromoUrlLauncher? launcher;

  static Future<void> show(
    BuildContext context,
    PromoAd ad, {
    PromoUrlLauncher? launcher,
  }) {
    return showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) => CouponSheet(ad: ad, launcher: launcher),
    );
  }

  /// Copy the code, then — only when the ad carries a redirect — send the
  /// renter on to the business.
  ///
  /// The copy always happens first and is never undone: Cancel on the consent
  /// sheet, a refused link and a failed launch all leave the code on the
  /// clipboard with nothing else changed, so the renter can still paste it by
  /// hand. The click for this interaction was already recorded when the card
  /// was tapped (`recordClick` in the strip and the Offers screen, before
  /// `handleTap`), so the redirect adds no event of its own.
  Future<void> _copyAndRedirect(BuildContext context, String code) async {
    final l = _L(context.isAr);
    final businessName = ad.business.name(context.isAr);
    await Clipboard.setData(ClipboardData(text: code));
    if (!context.mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(l.copied)));

    // Checked before asking for consent, so the renter is never asked to
    // approve a hop that would then be refused. An absent or unsafe link
    // (http, no host, userinfo) ends here: plain copy, exactly as before.
    final target = parseSafePromoUri(ad.redirectUrl);
    if (target == null) return;

    if (ad.sharedFields.isNotEmpty) {
      final confirmed = await CouponRedirectSheet.show(
        context,
        businessName: businessName,
        fields: ad.sharedFields,
      );
      if (confirmed != true) return;
    }

    // externalApplication, not the in-app browser the WEBSITE CTA uses: it is
    // what lets an https universal link open the business's own app.
    await launchPromoUri(
      launcher ?? launchUrl,
      target,
      LaunchMode.externalApplication,
    );
  }

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final m = context.miftah;
    final code = ad.couponCode?.trim();
    final terms = ad.couponTerms(isAr);
    final l = _L(isAr);

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
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
                color: m.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            ad.business.name(isAr),
            style: MiftahType.meta(color: m.textMuted),
          ),
          const SizedBox(height: 4),
          Text(ad.title(isAr), style: MiftahType.title(color: m.textPrimary)),
          const SizedBox(height: 18),
          if (code != null && code.isNotEmpty) ...[
            Container(
              padding: const EdgeInsets.all(MiftahSpacing.cardPad),
              decoration: BoxDecoration(
                color: m.isDark
                    ? MiftahColors.brass.withValues(alpha: 0.16)
                    : MiftahColors.brassTint,
                borderRadius: BorderRadius.circular(MiftahRadii.tile),
                border: Border.all(
                  color: m.isDark
                      ? MiftahColors.brass.withValues(alpha: 0.48)
                      : MiftahColors.brassTintBorder,
                ),
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
                      style: MiftahType.mono(size: 18, color: m.textPrimary),
                    ),
                  ),
                  TextButton.icon(
                    key: const Key('coupon-copy'),
                    onPressed: () => _copyAndRedirect(context, code),
                    icon: const Icon(Icons.copy_rounded, size: 18),
                    label: Text(l.copy),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
          ],
          if (terms != null) ...[
            Text(l.terms, style: MiftahType.sectionLabel(color: m.textPrimary)),
            const SizedBox(height: 5),
            Text(
              terms,
              key: const Key('coupon-terms'),
              style: MiftahType.body(color: m.textPrimary),
            ),
            const SizedBox(height: 14),
          ],
          if (ad.endsAt != null)
            Text(
              l.validUntilLine(ad.endsAt!),
              style: MiftahType.meta(color: m.textMuted),
            ),
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
