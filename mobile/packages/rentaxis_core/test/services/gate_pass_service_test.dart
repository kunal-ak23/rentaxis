import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/auth_service.dart';
import 'package:rentaxis_core/api/services/gate_pass_service.dart';

void main() {
  group('GatePassApiService.scan', () {
    test(
      'throws ArgumentError when neither qrToken nor numericCode is given',
      () async {
        final adapter = _StubAdapter(responseBody: {});
        final service = GatePassApiService(_dio(adapter));

        expect(() => service.scan(direction: 'ENTRY'), throwsArgumentError);
        expect(adapter.captured, isEmpty, reason: 'must not reach the network');
      },
    );

    test('throws ArgumentError when both are given', () async {
      final adapter = _StubAdapter(responseBody: {});
      final service = GatePassApiService(_dio(adapter));

      expect(
        () => service.scan(
          qrToken: 'qr-1',
          numericCode: '123456',
          direction: 'ENTRY',
        ),
        throwsArgumentError,
      );
      expect(adapter.captured, isEmpty);
    });

    test(
      'treats a blank string as absent, matching the server trimToNull',
      () async {
        final adapter = _StubAdapter(responseBody: {});
        final service = GatePassApiService(_dio(adapter));

        // Blank qrToken + real code is a valid single-credential scan...
        await service.scan(
          qrToken: '  ',
          numericCode: '123456',
          direction: 'ENTRY',
        );
        expect(adapter.captured.single.data, {
          'numericCode': '123456',
          'direction': 'ENTRY',
        });

        // ...and blank on both sides is still "neither".
        expect(
          () =>
              service.scan(qrToken: '', numericCode: '   ', direction: 'EXIT'),
          throwsArgumentError,
        );
      },
    );

    test('posts exactly one credential and the direction', () async {
      final adapter = _StubAdapter(
        responseBody: {'result': 'ALLOWED', 'reason': null},
      );
      final service = GatePassApiService(_dio(adapter));

      final result = await service.scan(qrToken: 'qr-1', direction: 'ENTRY');

      final request = adapter.captured.single;
      expect(request.path, '/v1/gatepass/scan');
      expect(request.method, 'POST');
      expect(request.data, {'qrToken': 'qr-1', 'direction': 'ENTRY'});
      expect(result['result'], 'ALLOWED');
    });
  });

  group('GatePassApiService.report', () {
    test(
      'sends from/to as UTC ISO-8601 so Jackson cannot misread the zone',
      () async {
        final adapter = _StubAdapter(responseBody: []);
        final service = GatePassApiService(_dio(adapter));

        // A local DateTime with a non-UTC offset: toIso8601String() alone would
        // emit no zone suffix and the backend would read the wall time as UTC.
        final from = DateTime.utc(2026, 7, 1, 6).toLocal();
        await service.report(from: from, to: DateTime.utc(2026, 7, 2, 6));

        final query = adapter.captured.single.queryParameters;
        expect(query['from'], '2026-07-01T06:00:00.000Z');
        expect(query['to'], '2026-07-02T06:00:00.000Z');
        expect(query.containsKey('propertyId'), isFalse);
      },
    );

    test('includes propertyId only when given', () async {
      final adapter = _StubAdapter(responseBody: []);
      final service = GatePassApiService(_dio(adapter));

      await service.report(
        from: DateTime.utc(2026, 7, 1),
        to: DateTime.utc(2026, 7, 2),
        propertyId: 'prop-1',
      );

      expect(adapter.captured.single.queryParameters['propertyId'], 'prop-1');
    });
  });

  group('GatePassApiService paths', () {
    test(
      'looks up repeat visitors by property, phone and selected unit',
      () async {
        final adapter = _StubAdapter(responseBody: {'id': 'visitor-1'});
        final service = GatePassApiService(_dio(adapter));

        await service.lookupWalkInVisitor(
          propertyId: 'property-1',
          phone: '+971501234567',
          unitId: 'unit-1',
        );

        final request = adapter.captured.single;
        expect(request.path, '/v1/gatepass/walk-in/visitor');
        expect(request.queryParameters, {
          'propertyId': 'property-1',
          'phone': '+971501234567',
          'unitId': 'unit-1',
        });
      },
    );

    test(
      'creates walk-in as multipart without blank optional fields',
      () async {
        final adapter = _StubAdapter(responseBody: {'id': 'pass-1'});
        final service = GatePassApiService(_dio(adapter));

        await service.createWalkIn(
          propertyId: 'property-1',
          unitId: 'unit-1',
          name: 'Rider One',
          phone: '+971501234567',
          visitorType: 'DELIVERY',
          purpose: ' ',
        );

        final request = adapter.captured.single;
        expect(request.path, '/v1/gatepass/walk-in');
        expect(request.method, 'POST');
        final form = request.data as FormData;
        expect(Map.fromEntries(form.fields), {
          'propertyId': 'property-1',
          'unitId': 'unit-1',
          'name': 'Rider One',
          'phone': '+971501234567',
          'visitorType': 'DELIVERY',
        });
        expect(form.files, isEmpty);
      },
    );

    test('resident decision uses the resident-scoped path', () async {
      final adapter = _StubAdapter(responseBody: {'status': 'ACTIVE'});
      final service = GatePassApiService(_dio(adapter));

      await service.decideAsResident('pass-1', true);

      expect(
        adapter.captured.single.path,
        '/v1/gatepass/resident-approvals/pass-1',
      );
      expect(adapter.captured.single.data, {'approved': true});
    });

    test('tower policy includes building id only for an override', () async {
      final adapter = _StubAdapter(responseBody: {'inherited': true});
      final service = GatePassApiService(_dio(adapter));

      await service.effectiveGatePolicy(
        propertyId: 'property-1',
        buildingId: 'tower-1',
      );

      expect(adapter.captured.single.queryParameters, {
        'propertyId': 'property-1',
        'buildingId': 'tower-1',
      });
    });

    test(
      'setGuardProperties PUTs a bare id list and returns the accepted list',
      () async {
        final adapter = _StubAdapter(responseBody: ['p1', 'p2']);
        final service = GatePassApiService(_dio(adapter));

        final accepted = await service.setGuardProperties('guard-1', [
          'p1',
          'p2',
        ]);

        final request = adapter.captured.single;
        expect(request.method, 'PUT');
        expect(request.path, '/v1/gatepass/guards/guard-1/properties');
        expect(request.data, ['p1', 'p2']);
        expect(accepted, ['p1', 'p2']);
      },
    );

    test('decide posts the approval decision', () async {
      final adapter = _StubAdapter(responseBody: {'id': 'gp-1'});
      final service = GatePassApiService(_dio(adapter));

      await service.decide('gp-1', false);

      final request = adapter.captured.single;
      expect(request.path, '/v1/gatepass/gp-1/approval');
      expect(request.data, {'approved': false});
    });

    test('cancel posts to the pass-scoped cancel path', () async {
      final adapter = _StubAdapter(responseBody: {'status': 'CANCELLED'});
      final service = GatePassApiService(_dio(adapter));

      await service.cancel('gp-1');

      expect(adapter.captured.single.path, '/v1/gatepass/gp-1/cancel');
      expect(adapter.captured.single.method, 'POST');
    });
  });

  group('AuthService Firebase login', () {
    test('posts the ID token and parses the AuthResponse', () async {
      final adapter = _StubAdapter(
        responseBody: {
          'id': 'u-1',
          'email': 'guard@example.com',
          'name': 'Guard One',
          'role': 'SECURITY_GUARD',
          'tenantId': 't-1',
          'tenantIds': ['t-1'],
        },
      );
      final service = AuthService(_dio(adapter));

      final auth = await service.loginWithFirebase('signed-firebase-token');

      final request = adapter.captured.single;
      expect(request.method, 'POST');
      expect(request.path, '/v1/auth/firebase');
      expect(request.data, {'idToken': 'signed-firebase-token'});
      expect(auth.id, 'u-1');
      expect(auth.role, 'SECURITY_GUARD');
      expect(auth.tenantId, 't-1');
      expect(auth.tenantIds, ['t-1']);
    });
  });
}

Dio _dio(_StubAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.example/api'));
  dio.httpClientAdapter = adapter;
  return dio;
}

class _StubAdapter implements HttpClientAdapter {
  _StubAdapter({required this.responseBody});

  final Object responseBody;
  final List<RequestOptions> captured = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    captured.add(options);
    return ResponseBody.fromBytes(
      utf8.encode(jsonEncode(responseBody)),
      200,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
