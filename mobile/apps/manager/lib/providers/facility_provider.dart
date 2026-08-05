/// Facility (amenities & parking) state for the manager app.
///
/// All `autoDispose`: inventory and the booking inbox change under the manager
/// (renters file requests, other admins decide them), so re-entering a screen
/// re-reads the server instead of showing a kept-alive copy.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// App-local: core exports [BuildingService] but no provider for it
/// (same situation as PropertyService in gate_pass_provider.dart).
final buildingServiceProvider = Provider<BuildingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return BuildingService(client.dio);
});

/// Towers of one property, for the scope multi-select chips.
final buildingsProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, propertyId) async {
  final service = ref.watch(buildingServiceProvider);
  final rows = await service.getBuildingsByProperty(propertyId);
  return _asRows(rows);
});

/// One page's rows plus the server's total row count, so the UI can tell
/// when `size` truncated the list (a property with more amenities/spots than
/// fit in one page).
typedef FacilityPage = ({List<Map<String, dynamic>> rows, int total});

/// Amenity inventory of one property. The endpoint is paged; one large page
/// keeps the screen simple (a property has tens of facilities, not thousands).
final amenitiesProvider = FutureProvider.autoDispose
    .family<FacilityPage, String>((ref, propertyId) async {
  final service = ref.watch(facilityServiceProvider);
  final page = await service.getAmenities(propertyId: propertyId, size: 200);
  return _asPage(page);
});

final parkingSpotsProvider = FutureProvider.autoDispose
    .family<FacilityPage, String>((ref, propertyId) async {
  final service = ref.watch(facilityServiceProvider);
  final page =
      await service.getParkingSpots(propertyId: propertyId, size: 200);
  return _asPage(page);
});

/// Admin booking inbox filter. A record so equal filters resolve to the same
/// family member (records have value equality).
typedef BookingFilter = ({String? propertyId, String? status});

/// Paged like the inventory providers above — a busy tenant can have more
/// open requests than fit in one page, and the screen needs `total` to show
/// a truncation footer rather than silently dropping rows.
final bookingsProvider = FutureProvider.autoDispose
    .family<FacilityPage, BookingFilter>((ref, filter) async {
  final service = ref.watch(facilityServiceProvider);
  final page = await service.getBookings(
    propertyId: filter.propertyId,
    status: filter.status,
    size: 200,
  );
  return _asPage(page);
});

/// One request + its competitors, re-fetched on open so the decision sheet
/// never acts on a row that went stale while the list sat on screen.
final bookingDetailProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, String>((ref, id) async {
  final service = ref.watch(facilityServiceProvider);
  return service.getBooking(id);
});

List<Map<String, dynamic>> _asRows(List<dynamic> rows) =>
    rows.whereType<Map>().map((r) => Map<String, dynamic>.from(r)).toList();

FacilityPage _asPage(Map<String, dynamic> page) {
  final rows = _asRows(page['content'] as List? ?? const []);
  final total = (page['totalElements'] as num?)?.toInt() ?? rows.length;
  return (rows: rows, total: total);
}
