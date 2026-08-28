import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/payment_service.dart';

void main() {
  group('PaymentService.getPaymentsPage', () {
    test('returns the full Spring Page map (content + totals)', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(
        responseBody: {
          'content': [
            {'id': 'a', 'status': 'PENDING'},
            {'id': 'b', 'status': 'CLEARED'},
          ],
          'totalElements': 42,
          'totalPages': 3,
          'pageable': {'pageNumber': 0},
        },
      );

      final page = await PaymentService(dio).getPaymentsPage();

      expect(page['content'], hasLength(2));
      expect((page['content'] as List).first['id'], 'a');
      expect(page['totalElements'], 42);
      expect(page['totalPages'], 3);
    });

    test(
      'normalizes bare-list responses (legacy backends) to one page',
      () async {
        final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
        dio.httpClientAdapter = _StubAdapter(
          responseBody: [
            {'id': 'a', 'status': 'PENDING'},
          ],
        );

        final page = await PaymentService(dio).getPaymentsPage();

        expect(page['content'], hasLength(1));
        expect(page['totalElements'], 1);
        expect(page['totalPages'], 1);
      },
    );

    test('passes server-side filters and real pagination params', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await PaymentService(dio).getPaymentsPage(
        propertyId: 'prop-1',
        status: 'PENDING',
        search: 'Tower A 5000',
        sort: ['dueDate,asc', 'id,asc'],
        page: 2,
        size: 20,
      );

      final params = adapter.lastRequest?.queryParameters;
      expect(params?['propertyId'], 'prop-1');
      expect(params?['status'], 'PENDING');
      expect(params?['search'], 'Tower A 5000');
      expect(params?['sort'], ['dueDate,asc', 'id,asc']);
      expect(params?['page'], 2);
      expect(params?['size'], 20);
      // overdue defaults to false and must then be omitted entirely — the
      // backend ignores `status` whenever overdue is present and true.
      expect(params, isNot(contains('overdue')));
    });

    test('omits blank server-side search values', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await PaymentService(dio).getPaymentsPage(search: '   ');

      expect(adapter.lastRequest?.queryParameters, isNot(contains('search')));
    });

    test('sends overdue=true for the computed overdue view', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await PaymentService(dio).getPaymentsPage(overdue: true);

      expect(adapter.lastRequest?.queryParameters['overdue'], true);
    });
  });

  group('PaymentService.getPaymentsForLease', () {
    test('fetches the unpaginated lease-scoped endpoint', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(
        responseBody: [
          {'id': 'a', 'leaseId': 'lease-1', 'installmentNumber': 1},
          {'id': 'b', 'leaseId': 'lease-1', 'installmentNumber': 2},
        ],
      );
      dio.httpClientAdapter = adapter;

      final list = await PaymentService(dio).getPaymentsForLease('lease-1');

      expect(adapter.lastRequest?.path, '/v1/payments/lease/lease-1');
      expect(list, hasLength(2));
      expect(list.first['id'], 'a');
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
