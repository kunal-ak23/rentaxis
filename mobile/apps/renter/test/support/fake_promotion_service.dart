import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// What the real Dio stack throws for a 404 — a hidden, archived or foreign
/// category or business.
DioException notFound(String path) {
  final options = RequestOptions(path: path);
  return DioException(
    requestOptions: options,
    type: DioExceptionType.badResponse,
    response: Response(requestOptions: options, statusCode: 404),
  );
}

/// Records what the app asked for and what it sent back, so tests can assert
/// on both without a Dio stack. Mirrors `fake_facility_service.dart`.
class FakePromotionService implements PromotionApiService {
  FakePromotionService({
    this.feedAds = const [],
    this.offerAds = const [],
    this.feedError,
    this.categories = const [],
    this.directoryError,
    Map<String, ({DirectoryCategory category, List<DirectoryBusiness> businesses})>?
        categoryDetails,
    Map<String, DirectoryBusiness>? businesses,
  })  : categoryDetails = categoryDetails ?? {},
        businesses = businesses ?? {};

  /// Category id → what `directoryCategory` returns. A missing id is a 404.
  final Map<String,
      ({DirectoryCategory category, List<DirectoryBusiness> businesses})>
      categoryDetails;
  Object? categoryError;
  final List<String> requestedCategoryIds = [];

  /// Business id → what `business` returns. A missing id is a 404.
  final Map<String, DirectoryBusiness> businesses;
  Object? businessError;
  final List<String> requestedBusinessIds = [];

  /// The `businessId` filter of every `offers` call, in order.
  final List<String?> requestedOfferBusinessIds = [];

  List<DirectoryCategory> categories;
  Object? directoryError;
  int directoryCalls = 0;

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
  Future<List<PromoAd>> offers({String? category, String? businessId}) async {
    requestedCategories.add(category);
    requestedOfferBusinessIds.add(businessId);
    if (offersError != null) throw offersError!;
    return offerAds
        .where((a) => category == null || a.business.category == category)
        .where((a) => businessId == null || a.business.id == businessId)
        .toList();
  }

  @override
  Future<List<DirectoryCategory>> directory() async {
    directoryCalls++;
    if (directoryError != null) throw directoryError!;
    return categories;
  }

  @override
  Future<({DirectoryCategory category, List<DirectoryBusiness> businesses})>
      directoryCategory(String id) async {
    requestedCategoryIds.add(id);
    if (categoryError != null) throw categoryError!;
    final detail = categoryDetails[id];
    if (detail == null) throw notFound('/v1/promotions/directory/$id');
    return detail;
  }

  @override
  Future<DirectoryBusiness> business(String id) async {
    requestedBusinessIds.add(id);
    if (businessError != null) throw businessError!;
    final found = businesses[id];
    if (found == null) {
      throw notFound('/v1/promotions/directory/businesses/$id');
    }
    return found;
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
  String? logoUrl,
  String? accentColor,
  String ctaType = 'NONE',
  String? ctaLabelEn,
  String? ctaUrl,
  String? ctaPhone,
  String? couponCode,
  String? couponTermsEn,
  String category = 'DINING',
  String businessId = 'b-1',
}) =>
    PromoAd.fromJson({
      'id': id,
      'business': {
        'id': businessId,
        'nameEn': 'Spice Bazaar',
        'nameAr': 'سبايس بازار',
        'logoUrl': logoUrl,
        'category': category,
      },
      'titleEn': titleEn,
      'titleAr': null,
      'subtitleEn': subtitleEn,
      'subtitleAr': null,
      'backgroundImageUrl': backgroundImageUrl,
      'accentColor': accentColor,
      'ctaType': ctaType,
      'ctaLabelEn': ctaLabelEn,
      'ctaLabelAr': null,
      'ctaUrl': ctaUrl,
      'ctaPhone': ctaPhone,
      'couponCode': couponCode,
      'couponTermsEn': couponTermsEn,
      'couponTermsAr': null,
      'endsAt': null,
    });
