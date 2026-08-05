import 'package:dio/dio.dart';

/// Thin wrapper over the amenities & parking booking endpoints
/// (`/v1/amenities`, `/v1/parking-spots`, `/v1/bookings`, `/v1/facilities/my`).
///
/// Raw `Map<String, dynamic>` rows, no models — house convention. Two shapes
/// matter to callers:
///  * Admin inventory/inbox reads are **Spring pages**: `{content: [...],
///    totalElements, ...}` — callers unwrap `content`. Ordering is the
///    server's `createdAt` ASC; do not re-sort.
///  * `POST /v1/bookings` is **idempotent for duplicates**: posting while the
///    caller already has a PENDING request for the same resource returns that
///    existing request, not an error. A 409 means a parking spot is already
///    APPROVED for someone else; a 400 means the amenity is not bookable.
///
/// A 409 from the booking endpoints (approve / a conflicting create) answers
/// `{error: "<message>", nextAvailableSlot}` — not the app's usual
/// `{error: true, message, ...}` envelope. Callers reading the error body off
/// a `DioException` for these calls should check both `error` (string here)
/// and `message` (standard shape) rather than assuming one or the other.
class FacilityApiService {
  final Dio _dio;
  FacilityApiService(this._dio);

  // ─── Admin: amenity inventory ──────────────────────────────────────────

  /// GET /v1/amenities — paged AmenityDTO for one property.
  Future<Map<String, dynamic>> getAmenities({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/v1/amenities',
      queryParameters: {'propertyId': propertyId, 'page': page, 'size': size},
    );
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/amenities — AmenityCreateRequest: `propertyId`, `nameEn`,
  /// `nameAr?`, `description?`, `bookable?`, `buildingIds?`
  /// (empty/absent = visible to all towers).
  Future<Map<String, dynamic>> createAmenity(Map<String, dynamic> body) async {
    final response = await _dio.post('/v1/amenities', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// PUT /v1/amenities/{id} — patch semantics: absent/null = unchanged; a
  /// non-null `buildingIds` REPLACES the scope set (send `[]` to clear).
  Future<Map<String, dynamic>> updateAmenity(
    String id,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.put('/v1/amenities/$id', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// DELETE /v1/amenities/{id} — soft-deactivate (`active=false`), 204.
  /// Existing booking requests survive; the amenity just leaves renter view.
  Future<void> deactivateAmenity(String id) async {
    await _dio.delete('/v1/amenities/$id');
  }

  // ─── Admin: parking inventory ──────────────────────────────────────────

  Future<Map<String, dynamic>> getParkingSpots({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/v1/parking-spots',
      queryParameters: {'propertyId': propertyId, 'page': page, 'size': size},
    );
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> createParkingSpot(
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post('/v1/parking-spots', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/parking-spots/bulk — ParkingSpotBulkCreateRequest:
  /// `{propertyId, spotNumbers: [...], level?, covered?, buildingIds?}`.
  /// Returns the created ParkingSpotDTO list. `spotNumbers` is capped at 500
  /// entries per request server-side — split larger batches client-side.
  Future<List<dynamic>> bulkCreateParkingSpots(
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post('/v1/parking-spots/bulk', data: body);
    return response.data as List<dynamic>;
  }

  Future<Map<String, dynamic>> updateParkingSpot(
    String id,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.put('/v1/parking-spots/$id', data: body);
    return response.data as Map<String, dynamic>;
  }

  Future<void> deactivateParkingSpot(String id) async {
    await _dio.delete('/v1/parking-spots/$id');
  }

  // ─── Admin: booking inbox ──────────────────────────────────────────────

  /// GET /v1/bookings — paged BookingRequestDTO. All filters optional;
  /// omitting `propertyId` reads the whole tenant.
  Future<Map<String, dynamic>> getBookings({
    String? propertyId,
    String? status,
    String? resourceType,
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/v1/bookings',
      queryParameters: {
        if (propertyId != null) 'propertyId': propertyId,
        if (status != null) 'status': status,
        if (resourceType != null) 'resourceType': resourceType,
        'page': page,
        'size': size,
      },
    );
    return response.data as Map<String, dynamic>;
  }

  /// GET /v1/bookings/{id} — BookingDetailDTO `{request: {...},
  /// otherRequests: [...]}` where `otherRequests` is every other
  /// PENDING/APPROVED request for the same resource.
  Future<Map<String, dynamic>> getBooking(String id) async {
    final response = await _dio.get('/v1/bookings/$id');
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings/{id}/approve. PENDING only (else 400). For a parking
  /// spot already APPROVED to someone else the server answers 409 — surface
  /// that as "spot already held", not a retryable failure. Returns the
  /// updated BookingRequestDTO. `adminNote` is capped at 2000 chars
  /// server-side.
  Future<Map<String, dynamic>> approveBooking(
    String id, {
    String? adminNote,
  }) async {
    final response = await _dio.post(
      '/v1/bookings/$id/approve',
      data: {
        if (_trimToNull(adminNote) != null) 'adminNote': adminNote!.trim(),
      },
    );
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings/{id}/reject. Returns the updated BookingRequestDTO.
  /// `adminNote` is capped at 2000 chars server-side.
  Future<Map<String, dynamic>> rejectBooking(
    String id, {
    String? adminNote,
  }) async {
    final response = await _dio.post(
      '/v1/bookings/$id/reject',
      data: {
        if (_trimToNull(adminNote) != null) 'adminNote': adminNote!.trim(),
      },
    );
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings/{id}/release — APPROVED parking only. Shared endpoint:
  /// an admin releases any spot, a renter only their own (server branches on
  /// role, same as GatePassController.isGuard()). Returns the updated
  /// BookingRequestDTO.
  Future<Map<String, dynamic>> releaseBooking(String id) async {
    final response = await _dio.post('/v1/bookings/$id/release');
    return response.data as Map<String, dynamic>;
  }

  // ─── Renter ────────────────────────────────────────────────────────────

  /// GET /v1/facilities/my — MyFacilitiesDTO `{amenities: [...],
  /// parkingSpots: [...]}` visible to the caller's active-lease unit(s).
  ///
  /// Non-bookable amenities are still listed (`bookable=false`) — show them,
  /// but offer no Request button; the server 400s the attempt. Renters get
  /// `pendingCount` numbers only, never other applicants' identities — do not
  /// expect renter fields on these rows; they are absent by design.
  Future<Map<String, dynamic>> myFacilities() async {
    final response = await _dio.get('/v1/facilities/my');
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings — BookingCreateRequest `{resourceType, resourceId,
  /// unitId, preferredDate?, note?}`. `preferredDate` is a LocalDate: send
  /// `yyyy-MM-dd`, never an instant. The property is derived server-side from
  /// the resource; the unit must be on one of the caller's ACTIVE leases —
  /// 404 otherwise (deliberately not 403, so unit ids cannot be probed).
  /// `note` is capped at 2000 chars server-side.
  Future<Map<String, dynamic>> createBooking(Map<String, dynamic> body) async {
    final response = await _dio.post('/v1/bookings', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// GET /v1/bookings/my — the caller's own requests, all statuses,
  /// createdAt ASC (server-ordered; do not re-sort).
  Future<List<dynamic>> myBookings() async {
    final response = await _dio.get('/v1/bookings/my');
    return response.data as List<dynamic>;
  }

  /// POST /v1/bookings/{id}/cancel — own PENDING only. Returns the updated
  /// BookingRequestDTO.
  Future<Map<String, dynamic>> cancelBooking(String id) async {
    final response = await _dio.post('/v1/bookings/$id/cancel');
    return response.data as Map<String, dynamic>;
  }

  static String? _trimToNull(String? value) {
    if (value == null) return null;
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }
}
