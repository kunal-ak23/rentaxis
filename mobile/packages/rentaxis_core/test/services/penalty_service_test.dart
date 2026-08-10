import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/penalty_service.dart';

void main() {
  group('PenaltyService.getPenalties', () {
    test('calls GET /v1/penalties?leaseId= (no /leases/{id}/penalties route)',
        () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await PenaltyService(dio).getPenalties('lease-1');

      expect(adapter.lastRequest?.method, 'GET');
      expect(adapter.lastRequest?.path, '/v1/penalties');
      expect(adapter.lastRequest?.queryParameters['leaseId'], 'lease-1');
    });

    test('unwraps Spring Page responses ({content: [...]})', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(responseBody: {
        'content': [
          {'id': 'a', 'status': 'OPEN'},
          {'id': 'b', 'status': 'WAIVED'},
        ],
        'totalElements': 2,
      });

      final list = await PenaltyService(dio).getPenalties('lease-1');

      expect(list, hasLength(2));
      expect(list.first['id'], 'a');
    });
  });

  group('PenaltyService.waivePenalty', () {
    test('POSTs to /v1/penalties/{id}/waive with the reason', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'id': 'p1', 'waived': true});
      dio.httpClientAdapter = adapter;

      final result = await PenaltyService(dio)
          .waivePenalty('p1', reason: 'Goodwill waiver');

      expect(adapter.lastRequest?.method, 'POST');
      expect(adapter.lastRequest?.path, '/v1/penalties/p1/waive');
      expect(adapter.lastRequest?.data, {'reason': 'Goodwill waiver'});
      expect(result['waived'], true);
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
        'content-type': ['application/json']
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
