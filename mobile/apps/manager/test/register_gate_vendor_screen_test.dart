/// What the register-vendor screen sends to `POST /v1/gatepass/visitors/registration`.
///
/// The endpoint upserts by phone + unit and is the manager app's only way to
/// time-bound or revoke a registered vendor (the audit found every submission
/// hardcoded `validFrom: null, validTo: null, active: true`, making
/// registrations permanently active). These tests pin the contract: defaults
/// stay unbounded-active, the validity dates go out as UTC instants, and the
/// access switch sends `active: false`.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/providers/gate_pass_provider.dart'
    show propertiesProvider;
import 'package:manager/screens/gatepass/register_gate_vendor_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/gatepass_harness.dart';

/// Answers `GET /v1/units/property/{id}` — the screen builds a real
/// `UnitService` from `apiClientProvider`'s Dio, so the fake sits at the
/// adapter seam like `fake_staff_api.dart`.
class _FakeUnitsApi implements HttpClientAdapter {
  _FakeUnitsApi(this.unitRows);

  final List<Map<String, dynamic>> unitRows;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.method == 'GET' &&
        options.path.contains('/v1/units/property/')) {
      return ResponseBody.fromString(
        jsonEncode(unitRows),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    return ResponseBody.fromString('not found', 404);
  }
}

ApiClient _unitsApiClient(List<Map<String, dynamic>> unitRows) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = _FakeUnitsApi(unitRows);
  return client;
}

Future<void> pumpRegister(
  WidgetTester tester, {
  required FakeGatePassService gatePass,
}) async {
  // Tall surface so the whole form (including the submit button) is mounted.
  tester.view.physicalSize = const Size(800, 1700);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await pumpScreen(
    tester,
    child: const RegisterGateVendorScreen(),
    overrides: [
      gatePassServiceProvider.overrideWithValue(gatePass),
      propertiesProvider.overrideWith(
        (ref) async => [
          {'id': 'prop-1', 'name': 'Marina Heights'},
        ],
      ),
      apiClientProvider.overrideWithValue(
        _unitsApiClient([
          {'id': 'unit-1', 'unitNumber': '101'},
        ]),
      ),
    ],
  );
}

/// Selects the property and unit, and types name and phone.
Future<void> fillRequiredFields(WidgetTester tester) async {
  await tester.tap(find.byType(DropdownButtonFormField<String>).first);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Marina Heights').last);
  await tester.pumpAndSettle();

  await tester.tap(find.byType(DropdownButtonFormField<String>).at(1));
  await tester.pumpAndSettle();
  await tester.tap(find.text('101').last);
  await tester.pumpAndSettle();

  await tester.enterText(find.byType(TextFormField).at(0), 'Maria Santos');
  await tester.enterText(find.byType(TextFormField).at(1), '+971501234567');
  await tester.pumpAndSettle();
}

Future<void> submit(WidgetTester tester) async {
  // GoldButton renders English labels uppercased.
  await tester.tap(find.text('REGISTER VENDOR'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

/// Runs the snackbar's auto-dismiss timer down so no timer is pending when the
/// test ends (the automated binding fails tests that leave one).
Future<void> flushSnackBars(WidgetTester tester) async {
  await tester.pump(const Duration(seconds: 5));
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('default submission registers unbounded and active', (
    tester,
  ) async {
    final gatePass = FakeGatePassService();
    await pumpRegister(tester, gatePass: gatePass);
    await fillRequiredFields(tester);
    await submit(tester);

    final sent = gatePass.registrations.single;
    expect(sent['propertyId'], 'prop-1');
    expect(sent['unitId'], 'unit-1');
    expect(sent['name'], 'Maria Santos');
    expect(sent['phone'], '+971501234567');
    expect(sent['visitorType'], 'MAID');
    expect(sent['validFrom'], isNull);
    expect(sent['validTo'], isNull);
    expect(sent['active'], isTrue);
    expect(find.text('Vendor registered for this unit.'), findsOneWidget);
    await flushSnackBars(tester);
  });

  testWidgets('turning the access switch off submits active=false '
      'and reports a revocation', (tester) async {
    final gatePass = FakeGatePassService();
    await pumpRegister(tester, gatePass: gatePass);
    await fillRequiredFields(tester);

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    await submit(tester);

    expect(gatePass.registrations.single['active'], isFalse);
    expect(find.text('Vendor access revoked for this unit.'), findsOneWidget);
    await flushSnackBars(tester);
  });

  testWidgets('a picked validity end date is sent as a UTC instant', (
    tester,
  ) async {
    final gatePass = FakeGatePassService();
    await pumpRegister(tester, gatePass: gatePass);
    await fillRequiredFields(tester);

    await tester.tap(find.text('Valid until (optional)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK')); // Accept the default (today).
    await tester.pumpAndSettle();
    await submit(tester);

    final sent = gatePass.registrations.single;
    expect(sent['validFrom'], isNull);
    final validTo = sent['validTo'] as String;
    // `Instant`-parseable: explicit UTC, end of the picked day.
    expect(validTo, endsWith('Z'));
    expect(DateTime.parse(validTo).toLocal().hour, 23);
    expect(sent['active'], isTrue);
    await flushSnackBars(tester);
  });
}
