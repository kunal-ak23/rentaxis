import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

import '../utils/promo_url.dart';
import 'coupon_sheet.dart';

export '../utils/promo_url.dart' show PromoUrlLauncher;

/// What tapping a promotion does, in one place, shared by the home carousel
/// and the Offers screen.
///
/// Links open in an **in-app browser** rather than the system browser: the
/// renter stays in Miftah and sees the host in the in-app chrome. The URL is
/// re-checked here even though the backend already validated it against the
/// business's domain allowlist on write — defence in depth, since this is the
/// last point before a renter is sent somewhere.
///
/// The client-side checks live in `parseSafePromoUri` (`utils/promo_url.dart`),
/// shared with the coupon's redirect link.
class PromoActions {
  PromoActions({PromoUrlLauncher? launcher, TargetPlatform? platform})
    : _launch = launcher ?? launchUrl,
      _platform = platform;

  final PromoUrlLauncher _launch;

  /// Forced platform for a coupon's per-platform redirect link; null (the
  /// real app) means the phone's own. A test seam, like the launcher.
  final TargetPlatform? _platform;

  Future<void> handleTap(BuildContext context, PromoAd ad) async {
    switch (ad.ctaType) {
      case PromoCtaType.website:
        await _openWebsite(ad.ctaUrl);
      case PromoCtaType.coupon:
        // The sheet gets this class's launcher, so a coupon redirect opens
        // through the same seam as every other link out of a promotion.
        await CouponSheet.show(
          context,
          ad,
          launcher: _launch,
          platform: _platform,
        );
      case PromoCtaType.call:
        await _openPhone(ad.ctaPhone, whatsapp: false);
      case PromoCtaType.whatsapp:
        await _openPhone(ad.ctaPhone, whatsapp: true);
      case PromoCtaType.none:
        break;
    }
  }

  Future<void> _openWebsite(String? raw) async {
    final uri = parseSafePromoUri(raw);
    if (uri == null) return;
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

  Future<void> _safeLaunch(Uri uri, LaunchMode mode) =>
      launchPromoUri(_launch, uri, mode);
}

final promoActionsProvider = Provider<PromoActions>((ref) => PromoActions());
