import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/app_version_service.dart';

void main() {
  group('AppVersionInfo.fromJson', () {
    test('parses a full body', () {
      final info = AppVersionInfo.fromJson({
        'minSupportedBuild': 5,
        'latestBuild': 9,
        'latestVersionName': '1.4.0',
        'storeUrl': 'https://example/app',
      });
      expect(info.minSupportedBuild, 5);
      expect(info.latestBuild, 9);
      expect(info.latestVersionName, '1.4.0');
      expect(info.storeUrl, 'https://example/app');
    });

    test('missing / wrong-typed fields fall back to permissive defaults', () {
      final info = AppVersionInfo.fromJson({});
      expect(info.minSupportedBuild, 0);
      expect(info.latestBuild, 0);
      expect(info.latestVersionName, '');
      expect(info.storeUrl, '');
    });

    test('coerces numeric strings and nums for the build fields', () {
      final info = AppVersionInfo.fromJson({
        'minSupportedBuild': '7',
        'latestBuild': 8.0,
        'latestVersionName': 12, // not a string -> ''
        'storeUrl': null,
      });
      expect(info.minSupportedBuild, 7);
      expect(info.latestBuild, 8);
      expect(info.latestVersionName, '');
      expect(info.storeUrl, '');
    });
  });

  group('AppVersionService.fetch', () {
    test('happy path parses a 200 body', () async {
      final adapter = _StubAdapter(
        responseBody: {
          'minSupportedBuild': 2,
          'latestBuild': 3,
          'latestVersionName': '1.2.0',
          'storeUrl': 'https://store/renter',
        },
      );
      final service = AppVersionService(_dio(adapter));

      final info = await service.fetch(
        app: AppId.renter,
        platform: AppPlatform.android,
      );

      expect(info, isNotNull);
      expect(info!.minSupportedBuild, 2);
      expect(info.latestBuild, 3);
      expect(info.storeUrl, 'https://store/renter');
    });

    test('sends the app and platform as query parameters', () async {
      final adapter = _StubAdapter(responseBody: const {});
      final service = AppVersionService(_dio(adapter));

      await service.fetch(app: AppId.security, platform: AppPlatform.ios);

      final request = adapter.captured.single;
      expect(request.path, '/v1/public/app-version');
      expect(request.queryParameters['app'], 'SECURITY');
      expect(request.queryParameters['platform'], 'IOS');
    });

    test('a non-200 status fails open to null', () async {
      final adapter = _StubAdapter(responseBody: const {}, statusCode: 500);
      final service = AppVersionService(_dio(adapter));

      final info = await service.fetch(
        app: AppId.manager,
        platform: AppPlatform.android,
      );

      expect(info, isNull);
    });

    test('a 2xx-but-not-200 status (204) fails open to null', () async {
      // Dio accepts 2xx without throwing, so this reaches the status guard
      // rather than the catch — pins `!= 200` specifically.
      final adapter = _StubAdapter(statusCode: 204);
      final service = AppVersionService(_dio(adapter));

      final info = await service.fetch(
        app: AppId.manager,
        platform: AppPlatform.android,
      );

      expect(info, isNull);
    });

    test('a non-map body fails open to null', () async {
      final adapter = _StubAdapter(responseBody: const ['nope']);
      final service = AppVersionService(_dio(adapter));

      final info = await service.fetch(
        app: AppId.manager,
        platform: AppPlatform.android,
      );

      expect(info, isNull);
    });

    test('a transport error fails open to null (never throws)', () async {
      final service = AppVersionService(_dio(_ThrowingAdapter()));

      final info = await service.fetch(
        app: AppId.renter,
        platform: AppPlatform.android,
      );

      expect(info, isNull);
    });

    test('a slow response past the short timeout fails open to null', () async {
      // The adapter never completes; only the service's own timeout can end
      // this call. If the timeout regressed, this test would hang.
      final service = AppVersionService(
        _dio(_HangingAdapter()),
        timeout: const Duration(milliseconds: 80),
      );

      final info = await service.fetch(
        app: AppId.renter,
        platform: AppPlatform.android,
      );

      expect(info, isNull);
    });
  });
}

Dio _dio(HttpClientAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.example/api'));
  dio.httpClientAdapter = adapter;
  return dio;
}

class _StubAdapter implements HttpClientAdapter {
  _StubAdapter({this.responseBody, this.statusCode = 200});

  final Object? responseBody;
  final int statusCode;
  final List<RequestOptions> captured = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    captured.add(options);
    final bytes = responseBody == null
        ? Uint8List(0)
        : utf8.encode(jsonEncode(responseBody));
    return ResponseBody.fromBytes(
      bytes,
      statusCode,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

class _ThrowingAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    throw DioException.connectionError(
      requestOptions: options,
      reason: 'network down',
    );
  }

  @override
  void close({bool force = false}) {}
}

class _HangingAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    // Never completes — stands in for a stalled connection.
    return Completer<ResponseBody>().future;
  }

  @override
  void close({bool force = false}) {}
}
