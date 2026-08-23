import 'package:flutter/material.dart';

/// What a tap on an ad card does.
enum PromoCtaType { none, website, coupon, call, whatsapp }

/// The slice of a promoted business a renter is allowed to see.
class PromoBusinessRef {
  const PromoBusinessRef({
    required this.id,
    this.nameEn,
    this.nameAr,
    this.logoUrl,
    this.category,
  });

  final String id;
  final String? nameEn;
  final String? nameAr;
  final String? logoUrl;
  final String? category;

  String name(bool isAr) => _pick(isAr, nameAr, nameEn) ?? '';

  factory PromoBusinessRef.fromJson(Map<String, dynamic> json) => PromoBusinessRef(
        id: json['id'] as String? ?? '',
        nameEn: json['nameEn'] as String?,
        nameAr: json['nameAr'] as String?,
        logoUrl: json['logoUrl'] as String?,
        category: json['category'] as String?,
      );
}

/// One promotion, as the renter app renders it.
///
/// **A deliberate exception to the project's raw-`Map<String, dynamic>`
/// convention.** Every text field on a card exists in two languages and needs
/// the same fallback rule, the accent colour needs parsing, and the CTA type
/// drives a switch in three widgets. Repeating that at each call site is where
/// bugs would live, so it is written once, here, and tested once.
///
/// Both languages arrive from the API and are resolved on the client, so
/// changing the app's language re-renders the strip without a refetch.
class PromoAd {
  const PromoAd({
    required this.id,
    required this.business,
    required this.ctaType,
    this.titleEn,
    this.titleAr,
    this.subtitleEn,
    this.subtitleAr,
    this.backgroundImageUrl,
    this.accentColor,
    this.ctaLabelEn,
    this.ctaLabelAr,
    this.ctaUrl,
    this.ctaPhone,
    this.couponCode,
    this.couponTermsEn,
    this.couponTermsAr,
    this.endsAt,
  });

  final String id;
  final PromoBusinessRef business;
  final PromoCtaType ctaType;
  final String? titleEn;
  final String? titleAr;
  final String? subtitleEn;
  final String? subtitleAr;
  final String? backgroundImageUrl;
  final Color? accentColor;
  final String? ctaLabelEn;
  final String? ctaLabelAr;
  final String? ctaUrl;
  final String? ctaPhone;
  final String? couponCode;
  final String? couponTermsEn;
  final String? couponTermsAr;
  final DateTime? endsAt;

  /// Never null — a card always has a headline slot to fill.
  String title(bool isAr) => _pick(isAr, titleAr, titleEn) ?? '';

  String? subtitle(bool isAr) => _pick(isAr, subtitleAr, subtitleEn);

  String? couponTerms(bool isAr) => _pick(isAr, couponTermsAr, couponTermsEn);

  /// The configured label, or a sensible default for the CTA type. Empty when
  /// there is no call to action, so callers can test it rather than the enum.
  String ctaLabel(bool isAr) {
    // ctaType first, deliberately. The backend does not clear cta labels when
    // an ad is switched to NONE, so a leftover label can sit on a NONE ad —
    // and checking `configured` first would render a CTA pill for an ad that
    // does nothing when tapped. It would also disagree with the admin panel's
    // preview, which gates the pill on ctaType alone.
    if (ctaType == PromoCtaType.none) return '';
    final configured = _pick(isAr, ctaLabelAr, ctaLabelEn);
    if (configured != null) return configured;
    switch (ctaType) {
      case PromoCtaType.website:
        return isAr ? 'زيارة الموقع' : 'Visit site';
      case PromoCtaType.coupon:
        return isAr ? 'استخدام الكوبون' : 'Redeem coupon';
      case PromoCtaType.call:
        return isAr ? 'اتصال' : 'Call';
      case PromoCtaType.whatsapp:
        return isAr ? 'واتساب' : 'WhatsApp';
      case PromoCtaType.none:
        return '';
    }
  }

  bool get hasImage =>
      backgroundImageUrl != null && backgroundImageUrl!.trim().isNotEmpty;

  factory PromoAd.fromJson(Map<String, dynamic> json) => PromoAd(
        id: json['id'] as String? ?? '',
        business: PromoBusinessRef.fromJson(
            Map<String, dynamic>.from((json['business'] as Map?) ?? const {})),
        ctaType: _parseCtaType(json['ctaType'] as String?),
        titleEn: json['titleEn'] as String?,
        titleAr: json['titleAr'] as String?,
        subtitleEn: json['subtitleEn'] as String?,
        subtitleAr: json['subtitleAr'] as String?,
        backgroundImageUrl: json['backgroundImageUrl'] as String?,
        accentColor: parseHexColor(json['accentColor'] as String?),
        ctaLabelEn: json['ctaLabelEn'] as String?,
        ctaLabelAr: json['ctaLabelAr'] as String?,
        ctaUrl: json['ctaUrl'] as String?,
        ctaPhone: json['ctaPhone'] as String?,
        couponCode: json['couponCode'] as String?,
        couponTermsEn: json['couponTermsEn'] as String?,
        couponTermsAr: json['couponTermsAr'] as String?,
        endsAt: DateTime.tryParse(json['endsAt'] as String? ?? ''),
      );

  /// Tolerant of junk rows — a malformed entry drops out rather than taking
  /// the whole strip down with it.
  static List<PromoAd> listFromJson(List<dynamic> rows) => rows
      .whereType<Map>()
      .map((r) => PromoAd.fromJson(Map<String, dynamic>.from(r)))
      .toList();

  /// An unrecognised value degrades to [PromoCtaType.none] rather than
  /// throwing — a newer backend must never crash an older app.
  static PromoCtaType _parseCtaType(String? raw) {
    switch (raw) {
      case 'WEBSITE':
        return PromoCtaType.website;
      case 'COUPON':
        return PromoCtaType.coupon;
      case 'CALL':
        return PromoCtaType.call;
      case 'WHATSAPP':
        return PromoCtaType.whatsapp;
      default:
        return PromoCtaType.none;
    }
  }
}

/// `#RRGGBB` or `#AARRGGBB`; null for anything else.
Color? parseHexColor(String? hex) {
  if (hex == null) return null;
  var cleaned = hex.trim().replaceFirst('#', '');
  if (cleaned.length == 6) cleaned = 'FF$cleaned';
  if (cleaned.length != 8) return null;
  final value = int.tryParse(cleaned, radix: 16);
  return value == null ? null : Color(value);
}

/// Prefers [preferred] when [isAr], otherwise [fallback], then whichever is
/// present. A blank string counts as absent — the admin form saves empty
/// fields as empty strings often enough that trusting non-null is not enough.
String? _pick(bool isAr, String? arabic, String? english) {
  String? clean(String? s) {
    if (s == null) return null;
    final t = s.trim();
    return t.isEmpty ? null : t;
  }

  final ar = clean(arabic);
  final en = clean(english);
  return isAr ? (ar ?? en) : (en ?? ar);
}
