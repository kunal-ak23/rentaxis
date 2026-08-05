import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Renter-side facility state.
///
/// All `autoDispose`: pending counts, spot holds and decisions change
/// server-side while the renter is elsewhere, so re-entering a screen
/// re-reads the server rather than showing a kept-alive copy.

/// GET /v1/facilities/my — amenities + parking spots visible to the caller's
/// active-lease unit(s). Counts only; no other applicants' identities.
final myFacilitiesProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(facilityServiceProvider);
  return service.myFacilities();
});

/// The caller's own booking requests, all statuses, createdAt ASC
/// (server-ordered; kept as-is per the project's created-ascending standard).
final myBookingRequestsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
  final service = ref.watch(facilityServiceProvider);
  final rows = await service.myBookings();
  return rows.whereType<Map>().map((r) => Map<String, dynamic>.from(r)).toList();
});
