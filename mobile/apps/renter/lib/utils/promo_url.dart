import 'package:flutter/foundation.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

/// `launchUrl`, as a type, so tests can hand in a recorder instead of a
/// platform channel.
typedef PromoUrlLauncher = Future<bool> Function(Uri uri, {LaunchMode mode});

/// Which of a coupon's redirect links to open on [platform] — the raw string,
/// not yet checked: pass it through [parseSafeCouponRedirectUri] before
/// launching.
///
/// Android prefers `redirectUrlAndroid`, iOS prefers `redirectUrlIos`, and
/// every other platform has no link of its own. Whatever is preferred but
/// absent falls back to the general `redirectUrl`.
///
/// "Absent" means null or blank, and nothing else. A platform link that is
/// present but later refused by [parseSafeCouponRedirectUri] is **not**
/// absent: the
/// choice is made here, before the check, so a refused Android link ends in
/// "no launch" rather than quietly sending an Android renter to the general
/// link the admin meant for other phones. The platform link is the admin's
/// stated intent for that phone, and a broken one is a mistake to surface
/// (nothing opens), not one to paper over. (A *safe* app link that the phone
/// cannot open — the business's app is not installed — is a different case;
/// see [couponRedirectFallback].)
String? chooseRedirectUrl(PromoAd ad, TargetPlatform platform) {
  final own = switch (platform) {
    TargetPlatform.android => ad.redirectUrlAndroid,
    TargetPlatform.iOS => ad.redirectUrlIos,
    _ => null,
  };
  // The model already trims blanks to null; this keeps the rule true for an
  // ad built by hand, where `'  '` would otherwise beat the fallback.
  if (own != null && own.trim().isNotEmpty) return own;
  return ad.redirectUrl;
}

/// The client-side check a WEBSITE `ctaUrl` passes before it is launched, and
/// the https half of a coupon redirect's check ([parseSafeCouponRedirectUri]).
/// Returns the parsed [Uri] when it is safe to open, null when it is not (or is
/// absent).
///
/// It deliberately mirrors `PromotionUrlValidator.isAllowed` on the server:
/// https only, a real host, no userinfo. The server already validated on
/// write; this is defence in depth, the last point before a renter is sent
/// somewhere. The one rule it cannot repeat is the per-business domain
/// allowlist, which is not part of the renter-facing DTO, so host matching
/// stays server-only.
Uri? parseSafePromoUri(String? raw) {
  final url = raw?.trim();
  if (url == null || url.isEmpty) return null;
  final uri = Uri.tryParse(url);
  if (uri == null) return null;
  // https only. `Uri.tryParse` is far more forgiving than the server's
  // `new URI(...)` — it happily turns junk into a relative reference with an
  // empty scheme — so every part of the authority is checked explicitly
  // rather than inferred from "it parsed".
  if (uri.scheme.toLowerCase() != 'https') return null;
  // `https:///path` parses with an empty host; the server refuses it and so
  // does this.
  if (uri.host.isEmpty) return null;
  // Userinfo is refused outright, exactly as the server does:
  // `https://my-bank.com@spice-bazaar.ae/` reads as the bank in an in-app
  // browser's minimal URL chrome while navigating somewhere else entirely.
  if (uri.userInfo.isNotEmpty) return null;
  return uri;
}

/// An app link's scheme: a letter, then 1–31 letters, digits, `+`, `.` or `-`.
final _appScheme = RegExp(r'^[a-z][a-z0-9+.\-]{1,31}$', caseSensitive: false);

/// Whitespace, control or format characters (zero-width, bidi overrides):
/// none may appear anywhere in an app link.
final _appLinkBadChar = RegExp(r'[\s\p{Cc}\p{Cf}]', unicode: true);

/// Schemes an app link may never use. https and http stay under the https rule
/// (the server keeps those to the business's allowed domains); the rest run
/// script, read local data, reach system handlers (Android `intent:`, iOS
/// enterprise installs) or place calls and messages.
const refusedAppSchemes = {
  'http',
  'https',
  'javascript',
  'vbscript',
  'data',
  'file',
  'blob',
  'about',
  'content',
  'ftp',
  'ws',
  'wss',
  'intent',
  'itms-services',
  'chrome',
  'chrome-extension',
  'mailto',
  'tel',
  'sms',
  'facetime',
  'facetime-audio',
};

/// Whether [url] (already trimmed) is an app deep link with a custom scheme,
/// such as `glidewash://redeem` — accepted on a coupon's redirect links since
/// the owner's 2026-10-29 ruling. Mirrors `PromotionUrlValidator.isAppLink` on
/// the server: a valid scheme, something after `scheme:`, no whitespace,
/// control or format character anywhere, and not one of [refusedAppSchemes].
/// The domain allowlist does not apply to an app link (it has no web host);
/// admins check it when they review the ad.
bool isPromoAppLink(String url) {
  if (url.isEmpty || _appLinkBadChar.hasMatch(url)) return false;
  final colon = url.indexOf(':');
  if (colon < 0 || colon == url.length - 1) return false;
  final scheme = url.substring(0, colon);
  return _appScheme.hasMatch(scheme) &&
      !refusedAppSchemes.contains(scheme.toLowerCase());
}

/// The client-side check a coupon's redirect link (main, Android or iPhone)
/// passes before it is launched: a safe https link ([parseSafePromoUri]) or an
/// app link ([isPromoAppLink]). Returns the parsed [Uri], or null when the
/// link is absent or refused — and a refused link opens nothing.
///
/// The WEBSITE `ctaUrl` keeps [parseSafePromoUri] alone: it opens in an in-app
/// browser and stays https on the business's domains.
Uri? parseSafeCouponRedirectUri(String? raw) {
  final url = raw?.trim();
  if (url == null || url.isEmpty) return null;
  final https = parseSafePromoUri(url);
  if (https != null) return https;
  if (!isPromoAppLink(url)) return null;
  return Uri.tryParse(url);
}

/// What to open instead when [target], the checked link chosen for this phone,
/// is an app link the phone could not open (the business's app is not
/// installed): the ad's main `redirectUrl`, when it is a safe **https** link
/// and a different link from [target]. Null otherwise — an https target never
/// falls back, and neither does a link that was refused (that never reaches a
/// launch at all).
Uri? couponRedirectFallback(PromoAd ad, Uri target) {
  if (target.scheme.toLowerCase() == 'https') return null;
  final main = parseSafePromoUri(ad.redirectUrl);
  if (main == null || main == target) return null;
  return main;
}

/// A device with no browser, no dialer or no WhatsApp must not take the home
/// screen down with it — an ad tap is never worth an error dialog.
///
/// Returns whether the launch went through: false when the launcher said no
/// (nothing can open it, such as an app link whose app is not installed) or
/// threw.
Future<bool> launchPromoUri(
  PromoUrlLauncher launch,
  Uri uri,
  LaunchMode mode,
) async {
  try {
    return await launch(uri, mode: mode);
  } catch (_) {
    // Intentionally swallowed. See above.
    return false;
  }
}
