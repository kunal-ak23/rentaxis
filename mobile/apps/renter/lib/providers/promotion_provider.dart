import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The home carousel slate. `autoDispose` because the server rotates it daily
/// and re-entering home should read the current day's slate rather than a
/// kept-alive copy from yesterday.
final homePromoFeedProvider = FutureProvider.autoDispose<List<PromoAd>>((ref) {
  return ref.watch(promotionServiceProvider).feed();
});

/// The full catalogue, optionally filtered. `null` family value = no filter.
final promoOffersProvider =
    FutureProvider.autoDispose.family<List<PromoAd>, String?>((ref, category) {
  return ref.watch(promotionServiceProvider).offers(category: category);
});

/// Deliberately **not** `autoDispose`: the queue has to outlive the carousel
/// so events recorded just before a navigation still get flushed.
final promoEventQueueProvider = Provider<PromoEventQueue>((ref) {
  return PromoEventQueue(ref.watch(promotionServiceProvider));
});

/// Buffers impressions and clicks and posts them in batches.
///
/// Impressions are de-duplicated for the life of this queue so a renter
/// swiping back and forth over the same card does not inflate the count; the
/// server also de-duplicates per day, so a lost flush costs nothing but a
/// missing row. Clicks are always queued — a second tap is a real second tap.
class PromoEventQueue {
  PromoEventQueue(this._service);

  /// The server rejects batches larger than this (PromoEventBatchRequest).
  static const _maxBatch = 50;

  final PromotionApiService _service;
  final List<PromoEvent> _pending = [];
  final Set<String> _impressed = {};

  void recordImpression(String adId) {
    if (!_impressed.add(adId)) return;
    _pending.add(PromoEvent(adId, PromoEventType.impression));
  }

  void recordClick(String adId) {
    _pending.add(PromoEvent(adId, PromoEventType.click));
  }

  /// Fire-and-forget. The queue is drained **before** the network call, so a
  /// failure drops the events rather than retrying forever — analytics must
  /// never interrupt a renter or pile up unbounded.
  Future<void> flush() async {
    if (_pending.isEmpty) return;
    final batch = List<PromoEvent>.of(_pending);
    _pending.clear();
    try {
      for (var i = 0; i < batch.length; i += _maxBatch) {
        final end = (i + _maxBatch < batch.length) ? i + _maxBatch : batch.length;
        await _service.postEvents(batch.sublist(i, end));
      }
    } catch (_) {
      // Swallowed on purpose. See the doc comment above.
    }
  }
}
