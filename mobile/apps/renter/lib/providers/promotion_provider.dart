import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Directory categories for the home tile row. `autoDispose` so re-entering
/// home re-reads them rather than showing a kept-alive copy.
final directoryProvider = FutureProvider.autoDispose<List<DirectoryCategory>>((ref) {
  return ref.watch(promotionServiceProvider).directory();
});

/// One directory category and its visible businesses. A hidden, archived or
/// foreign category is a 404 (see [isNotFoundError]).
final directoryCategoryProvider = FutureProvider.autoDispose.family<
    ({DirectoryCategory category, List<DirectoryBusiness> businesses}),
    String>((ref, id) {
  return ref.watch(promotionServiceProvider).directoryCategory(id);
});

/// One directory business. 404 when it is not visible to this renter.
final directoryBusinessProvider =
    FutureProvider.autoDispose.family<DirectoryBusiness, String>((ref, id) {
  return ref.watch(promotionServiceProvider).business(id);
});

/// Live offers, optionally narrowed by category and/or business. A record
/// family value: records compare by value, so the same filter reuses the
/// same provider. All-null = the whole catalogue.
final promoOffersProvider = FutureProvider.autoDispose
    .family<List<PromoAd>, ({String? category, String? businessId})>(
        (ref, filter) {
  return ref.watch(promotionServiceProvider).offers(
        category: filter.category,
        businessId: filter.businessId,
      );
});

/// Whether [error] is the server saying "not here": a 404 for a hidden,
/// archived or foreign category or business. Drives the not-found state
/// rather than the retryable error state — retrying a 404 cannot help.
bool isNotFoundError(Object error) =>
    error is DioException && error.response?.statusCode == 404;

/// Deliberately **not** `autoDispose`: the queue has to outlive the business
/// screen that records into it, so events recorded just before the renter
/// leaves still get flushed by that screen's `dispose`.
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
