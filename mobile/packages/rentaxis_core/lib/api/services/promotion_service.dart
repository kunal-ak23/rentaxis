import 'package:dio/dio.dart';

import '../../models/promo_ad.dart';

/// Wrapper over the renter promotions endpoints (`/v1/promotions/*`).
///
/// Unlike the other services here this one returns typed [PromoAd] rows rather
/// than raw maps — see the note in `models/promo_ad.dart` for why that
/// exception is drawn here.
///
/// The feed arrives **already ordered and already capped at six** by the
/// server's rotation. Callers render it in the order received and must not
/// re-sort; the order is stable for a renter for a whole day, which is what
/// keeps impression counts honest.
class PromotionApiService {
  final Dio _dio;
  PromotionApiService(this._dio);

  /// GET /v1/promotions/feed — the home carousel slate, at most 6 cards.
  Future<List<PromoAd>> feed() async {
    final response = await _dio.get('/v1/promotions/feed');
    return PromoAd.listFromJson((response.data as List?) ?? const []);
  }

  /// GET /v1/promotions/offers — the full catalogue, optionally by category.
  Future<List<PromoAd>> offers({String? category}) async {
    final response = await _dio.get(
      '/v1/promotions/offers',
      queryParameters: category == null ? null : {'category': category},
    );
    return PromoAd.listFromJson((response.data as List?) ?? const []);
  }

  /// POST /v1/promotions/events — batched impressions and clicks, 202.
  ///
  /// Fire-and-forget: the server drops unknown or ineligible ids silently, so
  /// callers should swallow failures rather than retry. Analytics must never
  /// interrupt a renter.
  Future<void> postEvents(List<PromoEvent> events) async {
    if (events.isEmpty) return;
    await _dio.post('/v1/promotions/events', data: {
      'events': events.map((e) => e.toJson()).toList(),
    });
  }
}

/// One analytics event, queued client-side before flushing.
class PromoEvent {
  const PromoEvent(this.adId, this.type);

  final String adId;
  final PromoEventType type;

  Map<String, dynamic> toJson() => {
        'adId': adId,
        'type': type == PromoEventType.impression ? 'IMPRESSION' : 'CLICK',
      };

  @override
  bool operator ==(Object other) =>
      other is PromoEvent && other.adId == adId && other.type == type;

  @override
  int get hashCode => Object.hash(adId, type);
}

enum PromoEventType { impression, click }
