import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'auth_provider.dart';
import '../api/services/listing_api_service.dart';

/// Caches the set of listing IDs that the current renter has wishlisted.
/// Used by ListingDetailScreen to show correct initial FAB state.
final wishlistIdsProvider =
    StateNotifierProvider<WishlistIdsNotifier, Set<String>>((ref) {
  final service = ref.watch(listingApiServiceProvider);
  return WishlistIdsNotifier(service);
});

class WishlistIdsNotifier extends StateNotifier<Set<String>> {
  final ListingApiService _service;

  WishlistIdsNotifier(this._service) : super(const {}) {
    _load();
  }

  Future<void> _load() async {
    try {
      final data = await _service.getWishlist(size: 200);
      final ids = (data['content'] as List? ?? [])
          .cast<Map<String, dynamic>>()
          .map((l) => l['id'] as String? ?? '')
          .where((id) => id.isNotEmpty)
          .toSet();
      state = ids;
    } catch (_) {
      // Non-fatal: FAB defaults to "Save" which is safe
    }
  }

  void add(String id) => state = {...state, id};

  void remove(String id) => state = state.difference({id});
}
