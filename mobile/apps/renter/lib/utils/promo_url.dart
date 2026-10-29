import 'package:flutter/foundation.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

/// `launchUrl`, as a type, so tests can hand in a recorder instead of a
/// platform channel.
typedef PromoUrlLauncher = Future<bool> Function(Uri uri, {LaunchMode mode});

/// Which of a coupon's redirect links to open on [platform] — the raw string,
/// not yet checked: pass it through [parseSafeCouponRedirect] before
/// launching.
///
/// Android prefers `redirectUrlAndroid`, iOS prefers `redirectUrlIos`, and
/// every other platform has no link of its own. Whatever is preferred but
/// absent falls back to the general `redirectUrl`.
///
/// "Absent" means null or blank, and nothing else. A platform link that is
/// present but later refused by [parseSafeCouponRedirect] is **not**
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
/// the https half of a coupon redirect's check ([parseSafeCouponRedirect]).
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

/// Printable ASCII only (`!` to `~`): no space, control, non-ASCII or
/// invisible character, so an app link reads on the admin's review screen
/// exactly as the phone opens it.
final _printableAscii = RegExp(r'^[\x21-\x7E]+$');

/// Schemes an app link may never use. https and http stay under the https rule
/// (the server keeps those to the business's allowed domains). Browser-opening
/// schemes (`googlechromes://host/path`, `firefox://open-url?url=…`) are
/// refused because they hand a web address to a browser and would get around
/// the domain allowlist; see also [isRefusedAppScheme] for the `-http`,
/// `-https` and `x-safari-` families. The rest run script, read local data,
/// reach system handlers (Android `intent:`, iOS enterprise installs) or place
/// calls and messages. Mirrors `PromotionUrlValidator.REFUSED_APP_SCHEMES`.
const refusedAppSchemes = {
  'http', 'https', 'javascript', 'vbscript', 'data', 'file', 'blob', 'about',
  'content', 'ftp', 'ws', 'wss', 'intent', 'itms-services', 'chrome',
  'chrome-extension', 'mailto', 'tel', 'sms', 'facetime', 'facetime-audio',
  // Browsers: these open a web address and would get around the allowlist.
  'googlechrome', 'googlechromes', 'firefox', 'focus', 'brave', 'opera',
  'opera-http', 'microsoft-edge', 'duckduckgo', 'edge',
  // Calls and messages.
  'telprompt', 'facetime-prompt', 'facetime-audio-prompt', 'smsto', 'mms',
  'mmsto',
};

/// Whether an app link may not use [scheme] (any case): one of
/// [refusedAppSchemes], or a browser bridge by pattern — ending in `-http` or
/// `-https`, or starting with `x-safari-`.
bool isRefusedAppScheme(String scheme) {
  final s = scheme.toLowerCase();
  return refusedAppSchemes.contains(s) ||
      s.endsWith('-http') ||
      s.endsWith('-https') ||
      s.startsWith('x-safari-');
}

/// Whether [url] (already trimmed) is an app deep link with a custom scheme,
/// such as `glidewash://redeem` — accepted on a coupon's redirect links since
/// the owner's 2026-10-29 ruling. Mirrors `PromotionUrlValidator.isAppLink` on
/// the server: a valid scheme, something after `scheme:`, printable ASCII
/// only, and not a refused scheme ([isRefusedAppScheme]). The domain allowlist
/// does not apply to an app link (it has no web host); admins check it when
/// they review the ad.
bool isPromoAppLink(String url) {
  if (!_printableAscii.hasMatch(url)) return false;
  final colon = url.indexOf(':');
  if (colon < 0 || colon == url.length - 1) return false;
  final scheme = url.substring(0, colon);
  return _appScheme.hasMatch(scheme) && !isRefusedAppScheme(scheme);
}

/// `launchUrlString`, as a type: how an app link is opened, so the exact
/// stored string reaches the phone. A test seam like [PromoUrlLauncher].
typedef PromoUrlStringLauncher =
    Future<bool> Function(String url, {LaunchMode mode});

/// A coupon redirect link that passed [parseSafeCouponRedirect]: either a safe
/// https [uri], or an [appLink] kept as the exact trimmed string the server
/// sent. An app link is never turned into a [Uri]: Dart's `Uri` lower-cases
/// the host, removes dot segments and re-escapes, so the business's app would
/// receive a different link from the one the admin reviewed — and a link
/// `Uri` cannot parse at all would not open, though the server accepted it.
class CouponRedirectTarget {
  const CouponRedirectTarget.https(Uri this.uri) : appLink = null;
  const CouponRedirectTarget.app(String this.appLink) : uri = null;

  final Uri? uri;
  final String? appLink;

  bool get isAppLink => appLink != null;

  @override
  String toString() => appLink ?? uri.toString();
}

/// The client-side check a coupon's redirect link (main, Android or iPhone)
/// passes before it is launched: a safe https link ([parseSafePromoUri]) or an
/// app link ([isPromoAppLink]). Null when the link is absent or refused — and
/// a refused link opens nothing.
///
/// The WEBSITE `ctaUrl` keeps [parseSafePromoUri] alone: it opens in an in-app
/// browser and stays https on the business's domains.
CouponRedirectTarget? parseSafeCouponRedirect(String? raw) {
  final url = raw?.trim();
  if (url == null || url.isEmpty) return null;
  final https = parseSafePromoUri(url);
  if (https != null) return CouponRedirectTarget.https(https);
  if (!isPromoAppLink(url)) return null;
  return CouponRedirectTarget.app(url);
}

/// What to open instead when [target], the checked link chosen for this phone,
/// is an app link the phone could not open (the business's app is not
/// installed): the ad's main `redirectUrl`, when it is a safe **https** link.
/// Null otherwise — an https target never falls back, and neither does a link
/// that was refused (that never reaches a launch at all).
Uri? couponRedirectFallback(PromoAd ad, CouponRedirectTarget target) {
  if (!target.isAppLink) return null;
  return parseSafePromoUri(ad.redirectUrl);
}

/// Opens a checked coupon redirect: an https link through [launchUri], an app
/// link through [launchString] as the exact stored string. Never throws;
/// returns whether the launch went through (see [launchPromoUri]).
Future<bool> launchCouponRedirect(
  PromoUrlLauncher launchUri,
  PromoUrlStringLauncher launchString,
  CouponRedirectTarget target,
  LaunchMode mode,
) async {
  final appLink = target.appLink;
  if (appLink == null) return launchPromoUri(launchUri, target.uri!, mode);
  try {
    return await launchString(appLink, mode: mode);
  } catch (_) {
    // Intentionally swallowed, as in launchPromoUri.
    return false;
  }
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
