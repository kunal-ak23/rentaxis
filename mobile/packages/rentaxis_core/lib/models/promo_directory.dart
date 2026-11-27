import '../utils/lang_pick.dart';
import '../utils/media_url.dart';

String? _str(Object? v) => v is String ? v : null;

/// A directory category, as the renter app renders it.
///
/// **A documented exception to the project's raw-`Map<String, dynamic>`
/// convention**, the same as `PromoAd`: bilingual fallback and media-URL
/// resolution are written once here instead of at every call site.
class DirectoryCategory {
  const DirectoryCategory({
    required this.id,
    this.nameEn,
    this.nameAr,
    this.iconUrl,
    this.businessCount = 0,
  });

  final String id;
  final String? nameEn;
  final String? nameAr;
  final String? iconUrl;
  final int businessCount;

  /// Never null — a tile always has a title slot to fill.
  String name(bool isAr) => pickLang(isAr, nameAr, nameEn) ?? '';

  factory DirectoryCategory.fromJson(Map<String, dynamic> json) => DirectoryCategory(
        id: _str(json['id']) ?? '',
        nameEn: _str(json['nameEn']),
        nameAr: _str(json['nameAr']),
        iconUrl: resolveMediaUrl(_str(json['iconUrl'])),
        businessCount: (json['businessCount'] as num?)?.toInt() ?? 0,
      );

  /// Tolerant of junk rows — a malformed entry drops out.
  static List<DirectoryCategory> listFromJson(List<dynamic> rows) => rows
      .whereType<Map>()
      .map((r) => DirectoryCategory.fromJson(Map<String, dynamic>.from(r)))
      .toList();
}

/// A business listed in the directory.
///
/// A documented exception to the raw-`Map` convention, as [DirectoryCategory].
class DirectoryBusiness {
  const DirectoryBusiness({
    required this.id,
    this.nameEn,
    this.nameAr,
    this.logoUrl,
    this.descriptionEn,
    this.descriptionAr,
    this.websiteUrl,
    this.phoneE164,
    this.whatsappE164,
  });

  final String id;
  final String? nameEn;
  final String? nameAr;
  final String? logoUrl;
  final String? descriptionEn;
  final String? descriptionAr;
  final String? websiteUrl;
  final String? phoneE164;
  final String? whatsappE164;

  String name(bool isAr) => pickLang(isAr, nameAr, nameEn) ?? '';

  /// Null when neither language has a description.
  String? description(bool isAr) => pickLang(isAr, descriptionAr, descriptionEn);

  factory DirectoryBusiness.fromJson(Map<String, dynamic> json) => DirectoryBusiness(
        id: _str(json['id']) ?? '',
        nameEn: _str(json['nameEn']),
        nameAr: _str(json['nameAr']),
        logoUrl: resolveMediaUrl(_str(json['logoUrl'])),
        descriptionEn: _str(json['descriptionEn']),
        descriptionAr: _str(json['descriptionAr']),
        websiteUrl: _str(json['websiteUrl']),
        phoneE164: _str(json['phoneE164']),
        whatsappE164: _str(json['whatsappE164']),
      );

  static List<DirectoryBusiness> listFromJson(List<dynamic> rows) => rows
      .whereType<Map>()
      .map((r) => DirectoryBusiness.fromJson(Map<String, dynamic>.from(r)))
      .toList();
}
