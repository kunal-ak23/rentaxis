import 'package:rentaxis_core/rentaxis_core.dart';

/// Records what the app asked for and what it sent back, so tests can assert
/// on both without a Dio stack. Mirrors `fake_facility_service.dart`.
class FakePromotionService implements PromotionApiService {
  FakePromotionService({
    this.feedAds = const [],
    this.offerAds = const [],
    this.feedError,
  });

  List<PromoAd> feedAds;
  List<PromoAd> offerAds;
  Object? feedError;
  Object? offersError;

  int feedCalls = 0;
  final List<String?> requestedCategories = [];
  final List<List<PromoEvent>> postedBatches = [];

  /// Every event across every flush, flattened — the usual assertion target.
  List<PromoEvent> get postedEvents =>
      postedBatches.expand((batch) => batch).toList();

  @override
  Future<List<PromoAd>> feed() async {
    feedCalls++;
    if (feedError != null) throw feedError!;
    return feedAds;
  }

  @override
  Future<List<PromoAd>> offers({String? category}) async {
    requestedCategories.add(category);
    if (offersError != null) throw offersError!;
    if (category == null) return offerAds;
    return offerAds.where((a) => a.business.category == category).toList();
  }

  @override
  Future<void> postEvents(List<PromoEvent> events) async {
    if (events.isEmpty) return;
    postedBatches.add(List.of(events));
  }
}

/// Minimal card builder for widget tests.
PromoAd testAd({
  String id = 'ad-1',
  String titleEn = 'Friday brunch',
  String? subtitleEn,
  String? backgroundImageUrl,
  String? accentColor,
  String ctaType = 'NONE',
  String? ctaUrl,
  String? ctaPhone,
  String? couponCode,
  String? couponTermsEn,
  String category = 'DINING',
}) =>
    PromoAd.fromJson({
      'id': id,
      'business': {
        'id': 'b-1',
        'nameEn': 'Spice Bazaar',
        'nameAr': 'سبايس بازار',
        'logoUrl': null,
        'category': category,
      },
      'titleEn': titleEn,
      'titleAr': null,
      'subtitleEn': subtitleEn,
      'subtitleAr': null,
      'backgroundImageUrl': backgroundImageUrl,
      'accentColor': accentColor,
      'ctaType': ctaType,
      'ctaLabelEn': null,
      'ctaLabelAr': null,
      'ctaUrl': ctaUrl,
      'ctaPhone': ctaPhone,
      'couponCode': couponCode,
      'couponTermsEn': couponTermsEn,
      'couponTermsAr': null,
      'endsAt': null,
    });
