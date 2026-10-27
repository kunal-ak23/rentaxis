import 'package:url_launcher/url_launcher.dart';

/// `launchUrl`, as a type, so tests can hand in a recorder instead of a
/// platform channel.
typedef PromoUrlLauncher = Future<bool> Function(Uri uri, {LaunchMode mode});

/// The client-side check every link out of a promotion passes before it is
/// launched — the WEBSITE `ctaUrl` and a coupon's redirect alike. Returns the
/// parsed [Uri] when it is safe to open, null when it is not (or is absent).
///
/// It deliberately mirrors `PromotionUrlValidator` on the server: https only,
/// a real host, no userinfo. The server already validated on write; this is
/// defence in depth, the last point before a renter is sent somewhere. The one
/// rule it cannot repeat is the per-business domain allowlist, which is not
/// part of the renter-facing DTO, so host matching stays server-only.
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

/// A device with no browser, no dialer or no WhatsApp must not take the home
/// screen down with it — an ad tap is never worth an error dialog.
Future<void> launchPromoUri(
  PromoUrlLauncher launch,
  Uri uri,
  LaunchMode mode,
) async {
  try {
    await launch(uri, mode: mode);
  } catch (_) {
    // Intentionally ignored. See above.
  }
}
