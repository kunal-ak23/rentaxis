import 'package:dio/dio.dart';

/// Thin wrapper over `/api/v1/gatepass`.
///
/// The backend returns two different shapes for a pass and the difference is a
/// security boundary, not a detail:
///   * [create], [mine], [byId], [cancel] → `GatePassResponse`, the creator's
///     view, which **carries the `qrToken` / `numericCode` credentials**. These
///     are renter-only paths.
///   * [expectedToday], [approvals], [decide] → `GatePassSummary`, the same pass
///     with the credentials and the renter identity stripped, plus `unitNumber`.
/// Do not assume a `qrToken` key exists on anything the guard-facing paths return.
class GatePassApiService {
  final Dio _dio;
  GatePassApiService(this._dio);

  // ---------------------------------------------------------------- renter

  /// POST /v1/gatepass — `body` is a CreateGatePassRequest:
  /// `unitId`, `guestName`, `guestPhone`, `purpose?`, `vehicleNumber?`,
  /// `passType` (SINGLE_USE|RECURRING), `validFrom?`, `validTo?`.
  /// Encode the two temporals with [instant] — they are `Instant` server-side.
  /// `propertyId` is derived server-side from the unit's active lease; sending
  /// it is pointless, it is ignored.
  Future<Map<String, dynamic>> create(Map<String, dynamic> body) async {
    final response = await _dio.post('/v1/gatepass', data: body);
    return response.data;
  }

  /// GET /v1/gatepass/mine — the caller's own passes, newest first.
  Future<List<dynamic>> mine() async {
    final response = await _dio.get('/v1/gatepass/mine');
    return response.data as List<dynamic>;
  }

  /// GET /v1/gatepass/{id} — creator-only; 404 on someone else's pass.
  Future<Map<String, dynamic>> byId(String id) async {
    final response = await _dio.get('/v1/gatepass/$id');
    return response.data;
  }

  /// POST /v1/gatepass/{id}/cancel — creator-only.
  Future<Map<String, dynamic>> cancel(String id) async {
    final response = await _dio.post('/v1/gatepass/$id/cancel');
    return response.data;
  }

  // ----------------------------------------------------------------- guard

  /// POST /v1/gatepass/scan → ScanResponse (`result`: ALLOWED|REJECTED,
  /// `reason`, and the guest fields — null when the gate declines to identify
  /// the pass to this guard).
  ///
  /// Exactly one of [qrToken] / [numericCode] must be given: the backend rejects
  /// both-null and both-present with a 400, so both are checked here rather than
  /// spent on a round-trip that cannot succeed. Blank strings count as absent,
  /// matching the server's own `trimToNull` normalization.
  ///
  /// [direction] is `ENTRY` or `EXIT`.
  Future<Map<String, dynamic>> scan({
    String? qrToken,
    String? numericCode,
    required String direction,
  }) async {
    final qr = _trimToNull(qrToken);
    final code = _trimToNull(numericCode);
    if ((qr == null) == (code == null)) {
      throw ArgumentError(
        'Provide exactly one of qrToken or numericCode (got '
        '${qr == null ? 'neither' : 'both'})',
      );
    }

    final response = await _dio.post('/v1/gatepass/scan', data: {
      if (qr != null) 'qrToken': qr,
      if (code != null) 'numericCode': code,
      'direction': direction,
    });
    return response.data;
  }

  /// GET /v1/gatepass/expected-today — ACTIVE passes overlapping today at the
  /// guard's assigned properties, in Asia/Dubai. Empty for an unposted guard.
  Future<List<dynamic>> expectedToday() async {
    final response = await _dio.get('/v1/gatepass/expected-today');
    return response.data as List<dynamic>;
  }

  // ------------------------------------------------------- approvals (shared)

  /// GET /v1/gatepass/approvals — passes awaiting approval. Scope depends on the
  /// caller's role: a guard sees only their assigned properties, a manager the
  /// whole tenant.
  Future<List<dynamic>> approvals() async {
    final response = await _dio.get('/v1/gatepass/approvals');
    return response.data as List<dynamic>;
  }

  /// POST /v1/gatepass/{id}/approval → the decided pass as a GatePassSummary.
  Future<Map<String, dynamic>> decide(String id, bool approved) async {
    final response = await _dio.post(
      '/v1/gatepass/$id/approval',
      data: {'approved': approved},
    );
    return response.data;
  }

  // --------------------------------------------------------------- manager

  /// GET /v1/gatepass/report — one row per scan over [from]..[to], joined to its
  /// pass. Manager-only. 400 if `to` is before `from`.
  Future<List<dynamic>> report({
    required DateTime from,
    required DateTime to,
    String? propertyId,
  }) async {
    final response = await _dio.get('/v1/gatepass/report', queryParameters: {
      'from': instant(from),
      'to': instant(to),
      if (propertyId != null) 'propertyId': propertyId,
    });
    return response.data as List<dynamic>;
  }

  /// GET /v1/gatepass/guards/{userId}/properties → a list of property id strings.
  Future<List<dynamic>> guardProperties(String userId) async {
    final response = await _dio.get('/v1/gatepass/guards/$userId/properties');
    return response.data as List<dynamic>;
  }

  /// PUT /v1/gatepass/guards/{userId}/properties — replace-all: [propertyIds]
  /// becomes the guard's complete posting. Returns the accepted list (the server
  /// de-duplicates). 400 if any property is outside the caller's tenant.
  ///
  /// This does not create the guard's tenant membership — see the note on the
  /// controller. A guard provisioned without one logs in with no tenant.
  Future<List<dynamic>> setGuardProperties(
      String userId, List<String> propertyIds) async {
    final response = await _dio.put(
      '/v1/gatepass/guards/$userId/properties',
      data: propertyIds,
    );
    return response.data as List<dynamic>;
  }

  static String? _trimToNull(String? value) {
    if (value == null) return null;
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }
}

/// Encodes a [DateTime] the way the backend's `Instant` fields expect it.
///
/// `DateTime.toIso8601String()` on a *local* DateTime emits no zone suffix, which
/// Jackson reads as UTC — silently shifting every timestamp by the device's
/// offset (4h in the UAE). Converting first makes the `Z` explicit.
String instant(DateTime value) => value.toUtc().toIso8601String();
