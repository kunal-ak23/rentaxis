import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Records gate-pass calls and replays canned outcomes.
///
/// Subclasses the real [GatePassApiService] rather than mocking a Dio adapter,
/// matching the guard app's fake: these are widget tests about what the screens
/// send and how they render what comes back, and the service's own wire format
/// is already pinned by
/// `rentaxis_core/test/services/gate_pass_service_test.dart`.
class FakeGatePassService extends GatePassApiService {
  FakeGatePassService({
    this.mineRows = const [],
    Map<String, dynamic>? createResponse,
    this.byIdResponse,
    this.mineError,
    this.byIdError,
    this.createError,
    this.cancelError,
  })  : createResponse = createResponse ?? {'id': 'pass-new'},
        super(Dio());

  List<dynamic> mineRows;
  Map<String, dynamic> createResponse;
  Map<String, dynamic>? byIdResponse;

  Object? mineError;
  Object? byIdError;
  Object? createError;
  Object? cancelError;

  /// Every body posted to `create`, in order.
  ///
  /// The whole point of the create tests: `validFrom`/`validTo` must arrive as
  /// UTC strings, and there is no other way to see what actually went up.
  final List<Map<String, dynamic>> created = [];
  final List<String> cancelled = [];
  int mineCalls = 0;

  @override
  Future<List<dynamic>> mine() async {
    mineCalls++;
    final error = mineError;
    if (error != null) throw error;
    return mineRows;
  }

  @override
  Future<Map<String, dynamic>> byId(String id) async {
    final error = byIdError;
    if (error != null) throw error;
    return byIdResponse ?? passFixture(id: id);
  }

  @override
  Future<Map<String, dynamic>> create(Map<String, dynamic> body) async {
    created.add(body);
    final error = createError;
    if (error != null) throw error;
    return createResponse;
  }

  @override
  Future<Map<String, dynamic>> cancel(String id) async {
    cancelled.add(id);
    final error = cancelError;
    if (error != null) throw error;
    return {'id': id, 'status': 'CANCELLED'};
  }
}

/// A `GatePassResponse` as `create` / `mine` / `byId` / `cancel` return it.
///
/// Note what IS here and is not on the guard's `GatePassSummary`: `qrToken` and
/// `numericCode`, the gate credential. And note what is NOT here: `propertyName`
/// and `unitNumber`. The creator-facing payload carries ids only, which is why
/// the detail screen resolves the property's name off the renter's lease — a
/// fixture that invented a name here would let a screen depend on a field
/// production never sends.
Map<String, dynamic> passFixture({
  String id = 'pass-1',
  String unitId = 'unit-1',
  String? guestName = 'Ahmed Khan',
  String? guestPhone = '+971501112222',
  String? purpose,
  String? vehicleNumber,
  String passType = 'SINGLE_USE',
  String? validFrom = '2026-07-16T05:00:00Z',
  String? validTo = '2026-07-16T13:00:00Z',
  String status = 'ACTIVE',
  String? qrToken = 'qr-token-abc123',
  String? numericCode = '481920',
}) {
  return {
    'id': id,
    'propertyId': 'prop-1',
    'unitId': unitId,
    'guestName': guestName,
    'guestPhone': guestPhone,
    'purpose': purpose,
    'vehicleNumber': vehicleNumber,
    'passType': passType,
    'validFrom': validFrom,
    'validTo': validTo,
    'status': status,
    'qrToken': qrToken,
    'numericCode': numericCode,
    'createdAt': '2026-07-15T09:00:00Z',
  };
}
