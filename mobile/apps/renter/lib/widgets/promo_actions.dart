import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

import 'coupon_sheet.dart';

typedef PromoUrlLauncher = Future<bool> Function(
  Uri uri, {
  LaunchMode mode,
});

/// What tapping a promotion does, in one place, shared by the home carousel
/// and the Offers screen.
///
/// Links open in an **in-app browser** rather than the system browser: the
/// renter stays in Miftah and sees the host in the in-app chrome. The URL is
/// re-checked here even though the backend already validated it against the
/// business's domain allowlist on write — defence in depth, since this is the
/// last point before a renter is sent somewhere.
///
/// The client-side checks deliberately mirror `PromotionUrlValidator` on the
/// server (https only, a real host, no userinfo). The one rule this side
/// cannot repeat is the per-business domain allowlist: the allowlist is not
/// part of the renter-facing DTO, so host matching stays server-only.
class PromoActions {
  PromoActions({PromoUrlLauncher? launcher}) : _launch = launcher ?? launchUrl;

  final PromoUrlLauncher _launch;

  Future<void> handleTap(BuildContext context, PromoAd ad) async {
    switch (ad.ctaType) {
      case PromoCtaType.website:
        await _openWebsite(ad.ctaUrl);
      case PromoCtaType.coupon:
        await CouponSheet.show(context, ad);
      case PromoCtaType.call:
        await _openPhone(ad.ctaPhone, whatsapp: false);
      case PromoCtaType.whatsapp:
        await _openPhone(ad.ctaPhone, whatsapp: true);
      case PromoCtaType.none:
        break;
    }
  }

  Future<void> _openWebsite(String? raw) async {
    final url = raw?.trim();
    if (url == null || url.isEmpty) return;
    final uri = Uri.tryParse(url);
    if (uri == null) return;
    // https only. `Uri.tryParse` is far more forgiving than the server's
    // `new URI(...)` — it happily turns junk into a relative reference with an
    // empty scheme — so every part of the authority is checked explicitly
    // rather than inferred from "it parsed".
    if (uri.scheme.toLowerCase() != 'https') return;
    // `https:///path` parses with an empty host; the server refuses it and so
    // does this.
    if (uri.host.isEmpty) return;
    // Userinfo is refused outright, exactly as the server does:
    // `https://my-bank.com@spice-bazaar.ae/` reads as the bank in an in-app
    // browser's minimal URL chrome while navigating somewhere else entirely.
    if (uri.userInfo.isNotEmpty) return;
    await _safeLaunch(uri, LaunchMode.inAppBrowserView);
  }

  Future<void> _openPhone(String? raw, {required bool whatsapp}) async {
    final phone = raw?.trim();
    if (phone == null || phone.isEmpty) return;
    final Uri uri;
    if (whatsapp) {
      // wa.me wants bare digits — no plus, and no spaces, dashes or brackets
      // either, all of which admins type into a phone field.
      final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
      if (digits.isEmpty) return;
      uri = Uri.parse('https://wa.me/$digits');
    } else {
      // Built rather than parsed so an admin-entered number with spaces is
      // percent-encoded instead of throwing a FormatException at the tap.
      uri = Uri(scheme: 'tel', path: phone);
    }
    await _safeLaunch(
      uri,
      whatsapp ? LaunchMode.externalApplication : LaunchMode.platformDefault,
    );
  }

  /// A device with no browser, no dialer or no WhatsApp must not take the home
  /// screen down with it — an ad tap is never worth an error dialog.
  Future<void> _safeLaunch(Uri uri, LaunchMode mode) async {
    try {
      await _launch(uri, mode: mode);
    } catch (_) {
      // Intentionally ignored. See above.
    }
  }
}

final promoActionsProvider = Provider<PromoActions>((ref) => PromoActions());
