import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/payment_service.dart';

void main() {
  group('PaymentService.getPayments', () {
    test('unwraps Spring Page responses ({content: [...]})', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(responseBody: {
        'content': [
          {'id': 'a', 'status': 'PENDING'},
          {'id': 'b', 'status': 'CLEARED'},
        ],
        'totalElements': 2,
        'pageable': {'pageNumber': 0},
      });

      final list = await PaymentService(dio).getPayments();

      expect(list, hasLength(2));
      expect(list.first['id'], 'a');
    });

    test('passes through bare-list responses (legacy backends)', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(responseBody: [
        {'id': 'a', 'status': 'PENDING'},
      ]);

      final list = await PaymentService(dio).getPayments();

      expect(list, hasLength(1));
    });

    test('requests a large page so all schedules load', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await PaymentService(dio).getPayments();

      expect(adapter.lastRequest?.queryParameters['size'], 500);
      expect(adapter.lastRequest?.queryParameters['page'], 0);
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
