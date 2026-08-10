import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/lease_service.dart';

void main() {
  group('LeaseService.terminateLease', () {
    test('sends notes in the JSON body, never as a query parameter', () async {
      // POST /v1/leases/{id}/terminate binds a TerminateWithSettlementDTO
      // request body (notes, deductions) and declares no @RequestParam —
      // query-string notes would be silently dropped by the backend.
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'id': 'lease-1'});
      dio.httpClientAdapter = adapter;

      await LeaseService(dio)
          .terminateLease('lease-1', notes: 'Tenant vacated early');

      expect(adapter.lastRequest?.path, '/v1/leases/lease-1/terminate');
      expect(adapter.lastRequest?.queryParameters, isEmpty);
      expect(
        adapter.lastRequest?.data,
        {'notes': 'Tenant vacated early'},
      );
    });

    test('omits notes from the body when not provided', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'id': 'lease-1'});
      dio.httpClientAdapter = adapter;

      await LeaseService(dio).terminateLease('lease-1');

      expect(adapter.lastRequest?.queryParameters, isEmpty);
      expect(adapter.lastRequest?.data, isEmpty);
    });
  });
}

class _StubAdapter implements HttpClientAdapter {
  _StubAdapter({required this.responseBody});

  final Object responseBody;
  RequestOptions? lastRequest;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    lastRequest = options;
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
