/// Fakes and fixtures for the manager's gate-pass screens.
///
/// The services are subclassed rather than mocked behind a Dio adapter, matching
/// `apps/security/test/support/fake_gate_pass_service.dart`: these are widget
/// tests about what the screens send and how they render what comes back, and
/// `GatePassApiService`'s own wire format is already pinned by
/// `rentaxis_core/test/services/gate_pass_service_test.dart`.
library;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/gatepass/guard_admin_service.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pumps one screen with every network seam faked.
///
/// No `ApiClient` is ever constructed: `gatePassServiceProvider` and
/// `guardAdminServiceProvider` are overridden with fakes, and `propertiesProvider`
/// is overridden with a value rather than a fake service — which is also what
/// keeps `apiClientProvider` (and its real Dio) from ever building.
Future<void> pumpScreen(
  WidgetTester tester, {
  required Widget child,
  required List<Override> overrides,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: overrides,
      child: MaterialApp(theme: AppTheme.lightTheme, home: child),
    ),
  );
  await tester.pumpAndSettle();
}

/// A `DioException` shaped like one of `GlobalExceptionHandler`'s responses:
/// `{error: true, message: ..., status: ...}`.
DioException httpError(int status, {String? message}) {
  final options = RequestOptions(path: '/');
  return DioException(
    requestOptions: options,
    type: DioExceptionType.badResponse,
    response: Response<dynamic>(
      requestOptions: options,
      statusCode: status,
      data: message == null
          ? null
          : {'error': true, 'message': message, 'status': status},
    ),
  );
}

class FakeGatePassService extends GatePassApiService {
  FakeGatePassService({
    this.approvalRows = const [],
    this.guardPropertyIds = const {},
    this.approvalsError,
    this.decideError,
    this.setGuardPropertiesResult,
    this.setGuardPropertiesError,
    this.registerVisitorError,
  }) : super(Dio());

  List<dynamic> approvalRows;

  /// userId → assigned property ids, as `GET /guards/{id}/properties` returns.
  Map<String, List<dynamic>> guardPropertyIds;

  Object? approvalsError;
  Object? decideError;

  /// What `PUT` echoes back. Left null it de-duplicates the input, as the server
  /// does; set it to pin the case where the accepted list differs from what was
  /// sent.
  List<dynamic>? setGuardPropertiesResult;
  Object? setGuardPropertiesError;
  Object? registerVisitorError;

  int approvalsCalls = 0;
  final List<({String id, bool approved})> decisions = [];
  final List<({String userId, List<String> propertyIds})> assignments = [];

  /// Every body sent to `POST /v1/gatepass/visitors/registration`.
  final List<Map<String, dynamic>> registrations = [];

  @override
  Future<List<dynamic>> approvals() async {
    approvalsCalls++;
    final error = approvalsError;
    if (error != null) throw error;
    return approvalRows;
  }

  @override
  Future<Map<String, dynamic>> decide(String id, bool approved) async {
    decisions.add((id: id, approved: approved));
    final error = decideError;
    if (error != null) throw error;
    return {'id': id, 'status': approved ? 'ACTIVE' : 'CANCELLED'};
  }

  @override
  Future<List<dynamic>> guardProperties(String userId) async =>
      guardPropertyIds[userId] ?? const [];

  @override
  Future<List<dynamic>> setGuardProperties(
      String userId, List<String> propertyIds) async {
    assignments.add((userId: userId, propertyIds: propertyIds));
    final error = setGuardPropertiesError;
    if (error != null) throw error;
    return setGuardPropertiesResult ?? propertyIds.toSet().toList();
  }

  @override
  Future<Map<String, dynamic>> registerGateVisitor(
    Map<String, dynamic> registration,
  ) async {
    registrations.add(Map<String, dynamic>.from(registration));
    final error = registerVisitorError;
    if (error != null) throw error;
    // VisitorLookupResponse shape; the register screen ignores the body.
    return {
      'id': 'visitor-1',
      'name': registration['name'],
      'phone': registration['phone'],
      'visitorType': registration['visitorType'],
      'registeredForSelectedUnit': registration['active'] == true,
    };
  }
}

class FakeGuardAdminService extends GuardAdminService {
  FakeGuardAdminService({this.userRows = const [], this.createError})
      : super(Dio());

  /// Every user `/admin/users` returns — all roles, since the endpoint has no
  /// role filter and the app is what narrows it.
  List<dynamic> userRows;
  Object? createError;

  int usersCalls = 0;
  final List<({String name, String email, String phoneNumber})> created = [];

  @override
  Future<List<dynamic>> users() async {
    usersCalls++;
    return userRows;
  }

  @override
  Future<Map<String, dynamic>> createGuard({
    required String name,
    required String email,
    required String phoneNumber,
  }) async {
    created.add((name: name, email: email, phoneNumber: phoneNumber));
    final error = createError;
    if (error != null) throw error;
    return {
      'id': 'guard-new',
      'name': name,
      'email': email,
      'phoneNumber': phoneNumber,
      'role': 'SECURITY_GUARD',
      'status': 'ACTIVE',
    };
  }
}

/// A `GatePassSummary` as `/approvals` returns it.
///
/// Note what is *not* here: no `qrToken`, no `numericCode`. The approver-facing
/// payload omits the admission credential deliberately, and a fixture that
/// invented one would let a screen depend on a field production never sends.
Map<String, dynamic> summaryFixture({
  String id = 'pass-1',
  String propertyId = 'prop-1',
  String? propertyName = 'Marina Heights',
  String? unitNumber = '101',
  String? guestName = 'Ahmed Khan',
  String? purpose,
  String? vehicleNumber,
  String passType = 'RECURRING',
  String? validFrom = '2026-07-16T05:00:00Z',
  String? validTo = '2026-07-16T13:00:00Z',
  String status = 'PENDING_APPROVAL',
}) {
  return {
    'id': id,
    'propertyId': propertyId,
    'propertyName': propertyName,
    'unitId': 'unit-1',
    'unitNumber': unitNumber,
    'guestName': guestName,
    'guestPhone': '+971501112222',
    'purpose': purpose,
    'vehicleNumber': vehicleNumber,
    'passType': passType,
    'validFrom': validFrom,
    'validTo': validTo,
    'status': status,
    'createdAt': '2026-07-15T09:00:00Z',
  };
}

/// A `UserResponseDTO` row as `/admin/users` returns it.
Map<String, dynamic> userFixture({
  String id = 'guard-1',
  String name = 'Rakesh Kumar',
  String? phoneNumber = '+971501234567',
  String email = 'rakesh@example.com',
  String role = 'SECURITY_GUARD',
  String status = 'ACTIVE',
}) {
  return {
    'id': id,
    'name': name,
    'email': email,
    'role': role,
    'status': status,
    'tenantId': 'tenant-1',
    'phoneNumber': phoneNumber,
  };
}
