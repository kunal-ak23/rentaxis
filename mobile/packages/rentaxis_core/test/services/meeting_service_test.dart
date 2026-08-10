import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/meeting_service.dart';

void main() {
  group('MeetingService.listMeetingsPage', () {
    test('returns the full Spring Page map (content + totals)', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(
        responseBody: {
          'content': [
            {'id': 'a', 'status': 'REQUESTED'},
            {'id': 'b', 'status': 'APPROVED'},
          ],
          'totalElements': 73,
          'totalPages': 3,
          'pageable': {'pageNumber': 0},
        },
      );

      final page = await MeetingService(dio).listMeetingsPage();

      expect(page['content'], hasLength(2));
      expect((page['content'] as List).first['id'], 'a');
      expect(page['totalElements'], 73);
      expect(page['totalPages'], 3);
    });

    test(
      'normalizes bare-list responses (legacy backends) to one page',
      () async {
        final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
        dio.httpClientAdapter = _StubAdapter(
          responseBody: [
            {'id': 'a', 'status': 'REQUESTED'},
          ],
        );

        final page = await MeetingService(dio).listMeetingsPage();

        expect(page['content'], hasLength(1));
        expect(page['totalElements'], 1);
        expect(page['totalPages'], 1);
      },
    );

    test('passes real pagination params', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await MeetingService(dio).listMeetingsPage(page: 2, size: 25);

      expect(adapter.lastRequest?.path, '/v1/meetings');
      expect(adapter.lastRequest?.queryParameters['page'], 2);
      expect(adapter.lastRequest?.queryParameters['size'], 25);
    });
  });

  group('MeetingService.listMyMeetingsPage', () {
    test('passes perspective and pagination params', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _StubAdapter(responseBody: {'content': <dynamic>[]});
      dio.httpClientAdapter = adapter;

      await MeetingService(
        dio,
      ).listMyMeetingsPage(perspective: 'host', page: 1, size: 25);

      final params = adapter.lastRequest?.queryParameters;
      expect(adapter.lastRequest?.path, '/v1/meetings/my');
      expect(params?['perspective'], 'host');
      expect(params?['page'], 1);
      expect(params?['size'], 25);
    });

    test('returns the full Spring Page map', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(
        responseBody: {
          'content': [
            {'id': 'a', 'status': 'COMPLETED'},
          ],
          'totalElements': 51,
          'totalPages': 3,
        },
      );

      final page = await MeetingService(dio).listMyMeetingsPage();

      expect(page['content'], hasLength(1));
      expect(page['totalElements'], 51);
      expect(page['totalPages'], 3);
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
