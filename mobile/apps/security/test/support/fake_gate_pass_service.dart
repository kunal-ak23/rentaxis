import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Records gate-pass calls and replays canned outcomes.
///
/// Subclasses the real [GatePassApiService] rather than mocking a Dio adapter,
/// for the same reason [FakeAuthService] does: these are widget tests about what
/// the screens send and how they render what comes back, and the service's own
/// wire format is already pinned by
/// `rentaxis_core/test/services/gate_pass_service_test.dart`.
class FakeGatePassService extends GatePassApiService {
  FakeGatePassService({
    this.expectedTodayRows = const [],
    this.approvalRows = const [],
    this.myPropertyRows = const [
      {'id': 'p-default', 'name': 'Default Tower'},
    ],
    this.scanResponse = const {'result': 'ALLOWED'},
    this.expectedTodayError,
    this.approvalsError,
    this.myPropertiesError,
    this.scanError,
    this.decideError,
    this.latency,
  }) : super(Dio());

  List<dynamic> expectedTodayRows;
  List<dynamic> approvalRows;

  /// Defaults to one property — a posted guard — so that the many tests about
  /// the visitor board are not implicitly testing the unposted empty state.
  /// Set it to `[]` to exercise a guard with no assignments.
  List<dynamic> myPropertyRows;

  /// Returned by [scan]. A test that needs entry and exit to differ can swap
  /// this between calls.
  Map<String, dynamic> scanResponse;

  Object? expectedTodayError;
  Object? approvalsError;
  Object? myPropertiesError;
  Object? scanError;
  Object? decideError;

  /// Holds calls open so a test can observe an in-flight state that would
  /// otherwise resolve inside the same pump that dispatched it.
  final Duration? latency;

  final List<({String? qrToken, String? numericCode, String direction})> scans =
      [];
  final List<({String id, bool approved})> decisions = [];
  int expectedTodayCalls = 0;
  int approvalsCalls = 0;
  int myPropertiesCalls = 0;

  @override
  Future<List<dynamic>> expectedToday() async {
    expectedTodayCalls++;
    if (latency != null) await Future<void>.delayed(latency!);
    final error = expectedTodayError;
    if (error != null) throw error;
    return expectedTodayRows;
  }

  @override
  Future<List<dynamic>> myProperties() async {
    myPropertiesCalls++;
    if (latency != null) await Future<void>.delayed(latency!);
    final error = myPropertiesError;
    if (error != null) throw error;
    return myPropertyRows;
  }

  @override
  Future<List<dynamic>> approvals() async {
    approvalsCalls++;
    if (latency != null) await Future<void>.delayed(latency!);
    final error = approvalsError;
    if (error != null) throw error;
    return approvalRows;
  }

  @override
  Future<Map<String, dynamic>> scan({
    String? qrToken,
    String? numericCode,
    required String direction,
  }) async {
    scans.add((qrToken: qrToken, numericCode: numericCode, direction: direction));
    if (latency != null) await Future<void>.delayed(latency!);
    final error = scanError;
    if (error != null) throw error;
    return scanResponse;
  }

  @override
  Future<Map<String, dynamic>> decide(String id, bool approved) async {
    decisions.add((id: id, approved: approved));
    if (latency != null) await Future<void>.delayed(latency!);
    final error = decideError;
    if (error != null) throw error;
    return {'id': id, 'status': approved ? 'ACTIVE' : 'CANCELLED'};
  }
}

/// A `GatePassSummary` as `expected-today` / `approvals` return it.
///
/// Note what is *not* here: no `qrToken`, no `numericCode`, no renter identity.
/// The guard-facing payload omits them deliberately, and a fixture that invented
/// them would let a screen depend on a field production never sends.
Map<String, dynamic> summaryFixture({
  String id = 'pass-1',
  String propertyId = 'prop-1',
  String? propertyName,
  String? unitNumber = '101',
  String? guestName = 'Ahmed Khan',
  String? guestPhone = '+971501112222',
  String? vehicleNumber,
  String? purpose,
  String passType = 'SINGLE_USE',
  String? validFrom = '2026-07-16T05:00:00Z',
  String? validTo = '2026-07-16T13:00:00Z',
  String status = 'ACTIVE',
}) {
  return {
    'id': id,
    'propertyId': propertyId,
    'propertyName': propertyName,
    'unitId': 'unit-1',
    'unitNumber': unitNumber,
    'guestName': guestName,
    'guestPhone': guestPhone,
    'purpose': purpose,
    'vehicleNumber': vehicleNumber,
    'passType': passType,
    'validFrom': validFrom,
    'validTo': validTo,
    'status': status,
    'createdAt': '2026-07-15T09:00:00Z',
  };
}
